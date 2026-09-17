[CmdletBinding()]
param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$Duration = '8m',
    [ValidateRange(1, 200)][int]$VirtualUsers = 30,
    [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'deploy/day10/docker-compose.yml'
$monitorScript = Join-Path $PSScriptRoot 'monitor-day11.ps1'
$resultRoot = Join-Path $PSScriptRoot "results/resilience-$RunId"
$projectName = "ticket-resilience-$($RunId.ToLowerInvariant() -replace '[^a-z0-9]', '')"
$apiBase = 'http://127.0.0.1:28100'
$prometheusBase = 'http://127.0.0.1:29090'
$summaryPath = Join-Path $resultRoot 'k6-summary.json'
$consolePath = Join-Path $resultRoot 'k6-console.log'
$errorPath = Join-Path $resultRoot 'k6-stderr.log'
$telemetryPath = Join-Path $resultRoot 'telemetry.csv'
$timelinePath = Join-Path $resultRoot 'failure-timeline.csv'
$resultPath = Join-Path $resultRoot 'result.json'
$stopPath = Join-Path $resultRoot 'monitor.stop'
$eventName = "resilience-failure-drill-$RunId"

if ($RunId -notmatch '^[A-Za-z0-9-]+$') {
    throw 'RunId may contain only letters, digits, and hyphens.'
}
if ($Duration -notmatch '^\d+(s|m|h)$') {
    throw 'Duration must be a k6 duration such as 8m.'
}

New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null
[IO.File]::WriteAllText($timelinePath, "timestamp_utc,event,detail`n")

function Add-Timeline([string]$event, [string]$detail) {
    $safe = $detail -replace '[\r\n,]', ' '
    [IO.File]::AppendAllText($timelinePath,
        "$([DateTimeOffset]::UtcNow.ToString('O')),$event,$safe`n")
    Write-Host "[$event] $detail"
}

function Wait-ForHttp([string]$url, [int]$seconds = 180) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($seconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $url -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        }
        catch { Start-Sleep -Seconds 1 }
    }
    throw "Endpoint did not become ready: $url"
}

function Wait-ForServiceHealthy([string]$service, [int]$seconds = 180) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($seconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $containerId = & docker compose --project-name $projectName -f $composeFile ps -q $service
        if ($containerId) {
            $health = & docker inspect --format `
                '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' `
                ($containerId | Select-Object -Last 1)
            if ($health -eq 'healthy' -or $health -eq 'running') { return }
        }
        Start-Sleep -Seconds 1
    }
    throw "Service did not recover: $service"
}

function Get-HttpStatus([string]$url) {
    try {
        return [int](Invoke-WebRequest -Uri $url -TimeoutSec 3).StatusCode
    }
    catch {
        $responseProperty = $_.Exception.PSObject.Properties['Response']
        if ($null -ne $responseProperty -and $null -ne $responseProperty.Value) {
            $statusProperty = $responseProperty.Value.PSObject.Properties['StatusCode']
            if ($null -ne $statusProperty) {
                return [int]$statusProperty.Value
            }
        }
        return 0
    }
}

function Get-K6Value([object]$summary, [string]$metricName, [string]$statistic) {
    $metricProperty = $summary.metrics.PSObject.Properties[$metricName]
    if ($null -eq $metricProperty) { return 0 }
    $metric = $metricProperty.Value
    $valuesProperty = $metric.PSObject.Properties['values']
    $values = if ($null -ne $valuesProperty) { $valuesProperty.Value } else { $metric }
    $valueProperty = $values.PSObject.Properties[$statistic]
    if ($null -eq $valueProperty) { return 0 }
    return [double]$valueProperty.Value
}

function Get-Maximum([double[]]$values) {
    if ($values.Count -eq 0) { return 0 }
    return [double](($values | Measure-Object -Maximum).Maximum)
}

function Assert-LoadStillRunning([Diagnostics.Process]$process) {
    if ($process.HasExited) {
        throw "k6 exited early with code $($process.ExitCode)."
    }
}

function Get-Reconciliation {
    $sql = @"
SELECT r.total, r.available, r.held, r.sold,
       r.conservation_drift, r.held_drift, r.sold_drift,
       (SELECT count(*) FROM reservation x WHERE x.event_id = r.event_id),
       (SELECT count(*) FROM outbox_event o JOIN reservation x ON x.id = o.aggregate_id
          WHERE x.event_id = r.event_id),
       (SELECT count(*) FROM outbox_event o JOIN reservation x ON x.id = o.aggregate_id
          WHERE x.event_id = r.event_id AND o.published_at IS NULL),
       (SELECT count(*) FROM reservation x
          WHERE x.event_id = r.event_id AND x.status = 'PENDING'),
       (SELECT count(*) FROM reservation x
          WHERE x.event_id = r.event_id AND x.status = 'EXPIRED')
FROM v_inventory_reconciliation r
JOIN event e ON e.id = r.event_id
WHERE e.name = '$eventName';
"@
    $raw = & docker compose --project-name $projectName -f $composeFile exec -T postgres `
        psql -U ticketsystem -d ticketsystem -At -F '|' -c $sql
    if ($LASTEXITCODE -ne 0 -or -not $raw) {
        throw "No reconciliation row returned for $eventName."
    }
    $fields = ($raw | Select-Object -Last 1) -split '\|'
    if ($fields.Count -ne 12) { throw "Malformed reconciliation output: $raw" }
    return [ordered]@{
        total = [int]$fields[0]
        available = [int]$fields[1]
        held = [int]$fields[2]
        sold = [int]$fields[3]
        conservationDrift = [int]$fields[4]
        heldDrift = [int]$fields[5]
        soldDrift = [int]$fields[6]
        reservationRows = [int]$fields[7]
        outboxRows = [int]$fields[8]
        unpublishedOutboxRows = [int]$fields[9]
        pendingReservationRows = [int]$fields[10]
        expiredReservationRows = [int]$fields[11]
    }
}

$monitorJob = $null
$k6 = $null
try {
    Push-Location $repoRoot
    $keyBytes = [byte[]]::new(48)
    [Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    $env:JWT_ACCESS_SECRET = [Convert]::ToBase64String($keyBytes)
    [Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    $env:JWT_REFRESH_SECRET = [Convert]::ToBase64String($keyBytes)
    & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    & docker compose --project-name $projectName -f $composeFile up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw 'Resilience topology failed to start.' }
    Wait-ForHttp "$apiBase/readyz"
    Wait-ForHttp "$prometheusBase/-/ready"

    $env:BASE_URL = $apiBase
    $env:RUN_ID = $RunId
    $env:SUITE_NAME = 'resilience'
    $env:VUS = $VirtualUsers.ToString()
    $env:DRILL_DURATION = $Duration
    $env:MAXIMUM_ATTEMPTS = '30'
    $env:ADMIN_USERNAME = 'load-admin'
    $env:ADMIN_PASSWORD = 'password'
    $env:K6_SUMMARY_TREND_STATS = 'avg,min,med,p(90),p(95),p(99),max'

    $monitorJob = Start-Job -FilePath $monitorScript -ArgumentList @(
        $prometheusBase, $composeFile, $projectName, $telemetryPath, $stopPath, 2
    )
    $scriptPath = Join-Path $PSScriptRoot 'k6/resilience-failure-drill.js'
    $arguments = @('run', ('--summary-export="' + $summaryPath + '"'),
        ('"' + $scriptPath + '"'))
    $k6 = Start-Process -FilePath 'k6.exe' -ArgumentList $arguments -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput $consolePath -RedirectStandardError $errorPath
    Add-Timeline 'traffic-started' "$VirtualUsers VUs for $Duration"

    Start-Sleep -Seconds 30
    Assert-LoadStillRunning $k6
    Add-Timeline 'app1-kill-start' "gateway ready status $(Get-HttpStatus "$apiBase/readyz")"
    & docker compose --project-name $projectName -f $composeFile kill app1 | Out-Null
    Start-Sleep -Seconds 15
    Add-Timeline 'app1-kill-observed' "gateway live status $(Get-HttpStatus "$apiBase/livez")"
    & docker compose --project-name $projectName -f $composeFile start app1 | Out-Null
    Wait-ForServiceHealthy 'app1'
    Wait-ForHttp "$apiBase/readyz"
    Add-Timeline 'app1-recovered' 'app1 and gateway readiness healthy'

    Start-Sleep -Seconds 20
    Assert-LoadStillRunning $k6
    Add-Timeline 'redis-outage-start' 'stopping Redis for 20 seconds'
    & docker compose --project-name $projectName -f $composeFile stop redis | Out-Null
    Start-Sleep -Seconds 20
    & docker compose --project-name $projectName -f $composeFile start redis | Out-Null
    Wait-ForServiceHealthy 'redis'
    Add-Timeline 'redis-recovered' 'Redis healthy'

    Start-Sleep -Seconds 20
    Assert-LoadStillRunning $k6
    Add-Timeline 'rabbitmq-outage-start' 'stopping RabbitMQ for 20 seconds'
    & docker compose --project-name $projectName -f $composeFile stop rabbitmq | Out-Null
    Start-Sleep -Seconds 20
    & docker compose --project-name $projectName -f $composeFile start rabbitmq | Out-Null
    Wait-ForServiceHealthy 'rabbitmq'
    Add-Timeline 'rabbitmq-recovered' 'RabbitMQ healthy; outbox allowed to drain'

    Start-Sleep -Seconds 30
    Assert-LoadStillRunning $k6
    Add-Timeline 'postgres-outage-start' "gateway ready status $(Get-HttpStatus "$apiBase/readyz")"
    & docker compose --project-name $projectName -f $composeFile stop postgres | Out-Null
    Start-Sleep -Seconds 10
    Add-Timeline 'postgres-outage-observed' `
        "gateway ready $(Get-HttpStatus "$apiBase/readyz"); live $(Get-HttpStatus "$apiBase/livez")"
    & docker compose --project-name $projectName -f $composeFile start postgres | Out-Null
    Wait-ForServiceHealthy 'postgres'
    Wait-ForServiceHealthy 'app1'
    Wait-ForServiceHealthy 'app2'
    Wait-ForHttp "$apiBase/readyz"
    Add-Timeline 'postgres-recovered' 'PostgreSQL and gateway readiness healthy'

    $nextHeartbeat = [DateTimeOffset]::UtcNow.AddSeconds(30)
    while (-not $k6.WaitForExit(1000)) {
        if ([DateTimeOffset]::UtcNow -ge $nextHeartbeat) {
            Write-Host '[traffic-running] waiting for the planned drill duration to complete'
            $nextHeartbeat = [DateTimeOffset]::UtcNow.AddSeconds(30)
        }
    }
    if ($k6.ExitCode -ne 0) { throw "k6 failed with exit code $($k6.ExitCode)." }
    Add-Timeline 'traffic-completed' 'k6 completed normally'

    Start-Sleep -Seconds 60
    Wait-ForHttp "$apiBase/readyz"
    $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
    $reconciliation = Get-Reconciliation

    New-Item -ItemType File -Force -Path $stopPath | Out-Null
    Wait-Job -Job $monitorJob -Timeout 20 | Out-Null
    if ($monitorJob.State -eq 'Running') { Stop-Job -Job $monitorJob }
    Receive-Job -Job $monitorJob
    Remove-Job -Job $monitorJob -Force
    $monitorJob = $null
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue

    $nginxLogs = (& docker compose --project-name $projectName -f $composeFile logs `
        --no-color nginx 2>&1 | Out-String)
    $appLogs = (& docker compose --project-name $projectName -f $composeFile logs `
        --no-color app1 app2 2>&1 | Out-String)
    [IO.File]::WriteAllText((Join-Path $resultRoot 'nginx.log'), $nginxLogs)
    [IO.File]::WriteAllText((Join-Path $resultRoot 'applications.log'), $appLogs)

    $reservationStatuses = @{}
    [regex]::Matches($nginxLogs,
        '"method":"POST","path":"/api/v1/events/[^\"]+/reservations","status":(\d+)') |
        ForEach-Object {
            $status = $_.Groups[1].Value
            if (-not $reservationStatuses.ContainsKey($status)) { $reservationStatuses[$status] = 0 }
            $reservationStatuses[$status]++
        }

    $telemetry = @(Import-Csv -LiteralPath $telemetryPath)
    $completeTelemetry = @($telemetry | Where-Object {
        $_.app1_active -match '^\d' -and $_.app2_active -match '^\d'
    })
    $active = @($completeTelemetry | ForEach-Object {
        [double]$_.app1_active + [double]$_.app2_active
    })
    $pending = @($completeTelemetry | ForEach-Object {
        [double]$_.app1_pending + [double]$_.app2_pending
    })
    $postgres = @($telemetry | Where-Object { $_.postgres_active -match '^\d' } |
        ForEach-Object { [double]$_.postgres_active })

    $logicalStarted = [int](Get-K6Value $summary 'logical_reservations_started' 'count')
    $logicalSucceeded = [int](Get-K6Value $summary 'logical_reservations_succeeded' 'count')
    $logicalFailed = [int](Get-K6Value $summary 'logical_reservations_failed' 'count')
    $attemptSuccesses = [int](Get-K6Value $summary 'reservations_accepted' 'count')
    $attemptFaults = [int](Get-K6Value $summary 'reservation_server_faults' 'count')
    $attemptUnexpected = [int](Get-K6Value $summary 'reservation_unexpected_responses' 'count')
    $attemptSoldOut = [int](Get-K6Value $summary 'errors_sold_out' 'count')
    $attemptUserLimit = [int](Get-K6Value $summary 'errors_user_limit' 'count')
    $attemptIdempotencyInProgress = [int](Get-K6Value $summary 'errors_idempotency_in_progress' 'count')
    $attemptRateLimited = [int](Get-K6Value $summary 'errors_rate_limited' 'count')
    $attemptTotal = $attemptSuccesses + $attemptFaults + $attemptUnexpected + `
        $attemptSoldOut + $attemptUserLimit + $attemptIdempotencyInProgress + $attemptRateLimited

    $correctnessPassed = $reconciliation.available -ge 0 `
        -and ($reconciliation.available + $reconciliation.held + $reconciliation.sold) `
            -eq $reconciliation.total `
        -and $reconciliation.conservationDrift -eq 0 `
        -and $reconciliation.heldDrift -eq 0 `
        -and $reconciliation.soldDrift -eq 0
    $logicalRecoveryPassed = $logicalFailed -eq 0 `
        -and $logicalStarted -eq $logicalSucceeded `
        -and $logicalSucceeded -eq $reconciliation.reservationRows

    $result = [ordered]@{
        runId = $RunId
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
        requestedDuration = $Duration
        virtualUsers = $VirtualUsers
        injections = @('app1 killed for 15s', 'Redis stopped for 20s',
            'RabbitMQ stopped for 20s', 'PostgreSQL stopped for 10s')
        logicalReservations = [ordered]@{
            started = $logicalStarted
            succeeded = $logicalSucceeded
            recoveredAfterRetry = [int](Get-K6Value $summary 'logical_reservations_recovered' 'count')
            failed = $logicalFailed
            availabilityPercent = if ($logicalStarted -eq 0) { 0 } else {
                [Math]::Round(100 * $logicalSucceeded / $logicalStarted, 5)
            }
            recoveryP95Milliseconds = [Math]::Round(
                (Get-K6Value $summary 'logical_recovery_latency' 'p(95)'), 2)
        }
        httpAttempts = [ordered]@{
            total = $attemptTotal
            acceptedIncludingReplays = $attemptSuccesses
            retries = [int](Get-K6Value $summary 'reservation_retry_attempts' 'count')
            serverOrTransportFaults = $attemptFaults
            unexpectedResponses = $attemptUnexpected
            soldOut = $attemptSoldOut
            userLimit = $attemptUserLimit
            idempotencyInProgress = $attemptIdempotencyInProgress
            rateLimited = $attemptRateLimited
            availabilityPercent = if ($attemptTotal -eq 0) { 0 } else {
                [Math]::Round(100 * $attemptSuccesses / $attemptTotal, 5)
            }
            reserveP95Milliseconds = [Math]::Round(
                (Get-K6Value $summary 'reserve_latency' 'p(95)'), 2)
            reserveP99Milliseconds = [Math]::Round(
                (Get-K6Value $summary 'reserve_latency' 'p(99)'), 2)
        }
        gatewayReservationStatuses = $reservationStatuses
        connectionEvidence = [ordered]@{
            telemetrySamples = $telemetry.Count
            completeApplicationSamples = $completeTelemetry.Count
            maximumHikariActive = [int](Get-Maximum $active)
            maximumHikariPending = [int](Get-Maximum $pending)
            maximumPostgresActive = [int](Get-Maximum $postgres)
            applicationPoolTimeoutLogCount = [regex]::Matches(
                $appLogs, 'Connection is not available, request timed out').Count
            applicationConnectionLossLogCount = [regex]::Matches(
                $appLogs, 'I/O error occurred while sending to the backend|Connection to .* refused|This connection has been closed').Count
        }
        finalHealth = [ordered]@{
            gatewayReadyStatus = Get-HttpStatus "$apiBase/readyz"
            gatewayLiveStatus = Get-HttpStatus "$apiBase/livez"
        }
        reconciliation = $reconciliation
        gates = [ordered]@{
            noOversellOrDrift = $correctnessPassed
            allLogicalReservationsResolved = $logicalRecoveryPassed
            noUnexpectedOrBusinessRejections = ($attemptUnexpected + $attemptSoldOut + `
                $attemptUserLimit + $attemptIdempotencyInProgress + $attemptRateLimited) -eq 0
            finalHealthRecovered = (Get-HttpStatus "$apiBase/readyz") -eq 200
            outboxDrained = $reconciliation.unpublishedOutboxRows -eq 0
        }
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath
    Write-Host "Resilience drill result written to $resultPath"
}
finally {
    if ($null -ne $k6 -and -not $k6.HasExited) {
        Stop-Process -Id $k6.Id -Force -ErrorAction SilentlyContinue
    }
    if ($null -ne $monitorJob) {
        New-Item -ItemType File -Force -Path $stopPath | Out-Null
        Wait-Job -Job $monitorJob -Timeout 10 | Out-Null
        if ($monitorJob.State -eq 'Running') { Stop-Job -Job $monitorJob }
        Receive-Job -Job $monitorJob -ErrorAction SilentlyContinue
        Remove-Job -Job $monitorJob -Force -ErrorAction SilentlyContinue
    }
    Pop-Location -ErrorAction SilentlyContinue
    if (-not $KeepRunning) {
        & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    }
    @('BASE_URL', 'RUN_ID', 'SUITE_NAME', 'VUS', 'DRILL_DURATION', 'MAXIMUM_ATTEMPTS',
      'JWT_ACCESS_SECRET', 'JWT_REFRESH_SECRET', 'JWT_ISSUER', 'JWT_AUDIENCE', 'ADMIN_USERNAME', 'ADMIN_PASSWORD',
      'K6_SUMMARY_TREND_STATS') | ForEach-Object {
        Remove-Item "Env:$_" -ErrorAction SilentlyContinue
    }
}
