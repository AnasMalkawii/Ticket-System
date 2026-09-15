[CmdletBinding()]
param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [ValidateRange(1, 10000)]
    [int]$VirtualUsers = 500,
    [ValidateRange(1, 1000000)]
    [int]$ReservationRequests = 10000,
    [switch]$KeepRunning,
    [switch]$K6InDocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'deploy/day10/docker-compose.yml'
$monitorScript = Join-Path $PSScriptRoot 'monitor-day11.ps1'
$resultRoot = Join-Path $PSScriptRoot "results/high-traffic-$RunId"
$projectName = "ticket-high-$($RunId.ToLowerInvariant() -replace '[^a-z0-9]', '')"
$apiBase = 'http://127.0.0.1:28100'
$prometheusBase = 'http://127.0.0.1:29090'
$expectedRequests = $ReservationRequests
$expectedVirtualUsers = $VirtualUsers
$eventName = "high-traffic-high-traffic-hot-row-$RunId"

if ($RunId -notmatch '^[A-Za-z0-9-]+$') {
    throw 'RunId may contain only letters, digits, and hyphens.'
}

New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null
$summaryPath = Join-Path $resultRoot 'k6-summary.json'
$consolePath = Join-Path $resultRoot 'k6-console.log'
$telemetryPath = Join-Path $resultRoot 'telemetry.csv'
$resultPath = Join-Path $resultRoot 'result.json'
$stopPath = Join-Path $resultRoot 'monitor.stop'

function Wait-ForHttp([string]$url, [int]$minutes = 3) {
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes($minutes)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $url -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        }
        catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "Endpoint did not become ready: $url"
}

function Wait-ForPrometheusSamples {
    $query = [Uri]::EscapeDataString('hikaricp_connections_active')
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(1)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri "$prometheusBase/api/v1/query?query=$query" -TimeoutSec 3
            $samples = @($response.data.result)
            if ($samples.Count -eq 2) { return }
        }
        catch {
            Start-Sleep -Seconds 1
        }
        Start-Sleep -Seconds 1
    }
    throw 'Prometheus did not collect connection samples from both replicas.'
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

function Get-Reconciliation {
    $sql = @"
SELECT r.total, r.available, r.held, r.sold,
       r.conservation_drift, r.held_drift, r.sold_drift,
       (SELECT count(*) FROM reservation x WHERE x.event_id = r.event_id)
FROM v_inventory_reconciliation r
JOIN event e ON e.id = r.event_id
WHERE e.name = '$eventName';
"@
    $raw = & docker compose --project-name $projectName -f $composeFile exec -T postgres psql -U ticketsystem -d ticketsystem -At -F '|' -c $sql
    if ($LASTEXITCODE -ne 0 -or -not $raw) {
        throw "No reconciliation row returned for $eventName."
    }
    $fields = ($raw | Select-Object -Last 1) -split '\|'
    if ($fields.Count -ne 8) { throw "Malformed reconciliation output: $raw" }
    return [ordered]@{
        total = [int]$fields[0]
        available = [int]$fields[1]
        held = [int]$fields[2]
        sold = [int]$fields[3]
        conservationDrift = [int]$fields[4]
        heldDrift = [int]$fields[5]
        soldDrift = [int]$fields[6]
        reservationRows = [int]$fields[7]
    }
}

try {
    Push-Location $repoRoot
    & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    & docker compose --project-name $projectName -f $composeFile up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw 'High-traffic topology failed to start.' }

    Wait-ForHttp "$apiBase/readyz"
    Wait-ForHttp "$prometheusBase/-/ready"
    Wait-ForPrometheusSamples

    $env:BASE_URL = $apiBase
    $env:RUN_ID = $RunId
    $env:SUITE_NAME = 'high-traffic'
    $env:JWT_SIGNING_KEY = 'day10-shared-signing-key-0123456789abcdef'
    $env:JWT_ISSUER = 'ticket-system-load'
    $env:JWT_AUDIENCE = 'ticket-system-api'
    $env:ADMIN_USERNAME = 'load-admin'
    $env:ADMIN_PASSWORD = 'password'
    $env:VUS = $VirtualUsers
    $env:REQUESTS = $ReservationRequests
    $env:K6_SUMMARY_TREND_STATS = 'avg,min,med,p(90),p(95),p(99),max'

    $monitorJob = Start-Job -FilePath $monitorScript -ArgumentList @(
        $prometheusBase, $composeFile, $projectName, $telemetryPath, $stopPath, 1
    )
    try {
        if ($K6InDocker) {
            $dockerArguments = @(
                'run', '--rm',
                '--network', "${projectName}_default",
                '-v', "${repoRoot}:/work:ro",
                '-v', "${resultRoot}:/results",
                '-e', 'BASE_URL=http://nginx:8080',
                '-e', "RUN_ID=$RunId",
                '-e', 'SUITE_NAME=high-traffic',
                '-e', 'JWT_SIGNING_KEY=day10-shared-signing-key-0123456789abcdef',
                '-e', 'JWT_ISSUER=ticket-system-load',
                '-e', 'JWT_AUDIENCE=ticket-system-api',
                '-e', 'ADMIN_USERNAME=load-admin',
                '-e', 'ADMIN_PASSWORD=password',
                '-e', "VUS=$VirtualUsers",
                '-e', "REQUESTS=$ReservationRequests",
                '-e', 'K6_SUMMARY_TREND_STATS=avg,min,med,p(90),p(95),p(99),max',
                'grafana/k6:2.1.0',
                'run', '--summary-export=/results/k6-summary.json',
                '/work/load-tests/k6/high-traffic-500vu-10000.js'
            )
            & docker @dockerArguments 2>&1 | Tee-Object -FilePath $consolePath
        }
        else {
            & k6 run "--summary-export=$summaryPath" (Join-Path $PSScriptRoot 'k6/high-traffic-500vu-10000.js') 2>&1 | Tee-Object -FilePath $consolePath
        }
        $k6ExitCode = $LASTEXITCODE
        Start-Sleep -Seconds 5
    }
    finally {
        New-Item -ItemType File -Force -Path $stopPath | Out-Null
        Wait-Job -Job $monitorJob -Timeout 20 | Out-Null
        if ($monitorJob.State -eq 'Running') { Stop-Job -Job $monitorJob }
        Receive-Job -Job $monitorJob
        Remove-Job -Job $monitorJob -Force
        Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    }

    $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
    $reconciliation = Get-Reconciliation
    $telemetry = @(Import-Csv -LiteralPath $telemetryPath)
    $activeConnections = @($telemetry | Where-Object {
        $_.app1_active -match '^\d' -and $_.app2_active -match '^\d'
    } | ForEach-Object { [double]$_.app1_active + [double]$_.app2_active })
    $pendingConnections = @($telemetry | Where-Object {
        $_.app1_pending -match '^\d' -and $_.app2_pending -match '^\d'
    } | ForEach-Object { [double]$_.app1_pending + [double]$_.app2_pending })
    $postgresConnections = @($telemetry | Where-Object {
        $_.postgres_active -match '^\d'
    } | ForEach-Object { [double]$_.postgres_active })

    $nginxContainer = (& docker compose --project-name $projectName -f $composeFile ps -q nginx).Trim()
    $app1Container = (& docker compose --project-name $projectName -f $composeFile ps -q app1).Trim()
    $app2Container = (& docker compose --project-name $projectName -f $composeFile ps -q app2).Trim()
    $nginxLog = @(& docker logs $nginxContainer 2>&1 | ForEach-Object { "$_" })
    $reservationGatewayLog = @($nginxLog | Where-Object { $_ -match '/reservations' })
    $app1Log = @(& docker logs $app1Container 2>&1 | ForEach-Object { "$_" })
    $app2Log = @(& docker logs $app2Container 2>&1 | ForEach-Object { "$_" })

    $attempts = [int](Get-K6Value $summary 'iterations' 'count')
    $created = [int](Get-K6Value $summary 'reservations_created' 'count')
    $soldOut = [int](Get-K6Value $summary 'errors_sold_out' 'count')
    $userLimit = [int](Get-K6Value $summary 'errors_user_limit' 'count')
    $idempotencyInProgress = [int](Get-K6Value $summary 'errors_idempotency_in_progress' 'count')
    $rateLimited = [int](Get-K6Value $summary 'errors_rate_limited' 'count')
    $serverFaults = [int](Get-K6Value $summary 'reservation_server_faults' 'count')
    $unexpected = [int](Get-K6Value $summary 'reservation_unexpected_responses' 'count')
    $accounted = $created + $soldOut + $userLimit + $idempotencyInProgress +
        $rateLimited + $serverFaults + $unexpected
    $p95 = Get-K6Value $summary 'reserve_latency' 'p(95)'
    $p99 = Get-K6Value $summary 'reserve_latency' 'p(99)'
    $availability = if ($attempts -eq 0) { 0 } else {
        100.0 * ($attempts - $serverFaults) / $attempts
    }

    $correctnessPassed = $reconciliation.available -ge 0 -and
        $reconciliation.held -eq $created -and
        $reconciliation.sold -eq 0 -and
        $reconciliation.reservationRows -eq $created -and
        $reconciliation.conservationDrift -eq 0 -and
        $reconciliation.heldDrift -eq 0 -and
        $reconciliation.soldDrift -eq 0
    $requestAccountingPassed = $attempts -eq $expectedRequests -and $accounted -eq $attempts
    $latencyPassed = $p95 -le 300 -and $p99 -le 800
    $availabilitySloPassed = $availability -ge 99.9
    $availabilityRequestedPassed = $availability -ge 99.99
    $good = $correctnessPassed -and $requestAccountingPassed -and
        $latencyPassed -and $availabilityRequestedPassed -and $unexpected -eq 0

    $result = [ordered]@{
        runId = $RunId
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
        topology = 'Nginx -> two stateless Spring Boot replicas -> PostgreSQL 16'
        loadGeneratorPath = if ($K6InDocker) { 'Docker internal network' } else { 'Windows published port' }
        virtualUsers = $expectedVirtualUsers
        reservationRequests = $attempts
        setupRequestsExcluded = 3
        reservationsCreated = $created
        soldOut = $soldOut
        userLimit = $userLimit
        idempotencyInProgress = $idempotencyInProgress
        rateLimited = $rateLimited
        serverOrTransportFaults = $serverFaults
        unexpectedResponses = $unexpected
        accountedResponses = $accounted
        availabilityPercent = [Math]::Round($availability, 3)
        reserveLatencyMilliseconds = [ordered]@{
            median = [Math]::Round((Get-K6Value $summary 'reserve_latency' 'med'), 2)
            p95 = [Math]::Round($p95, 2)
            p99 = [Math]::Round($p99, 2)
            maximum = [Math]::Round((Get-K6Value $summary 'reserve_latency' 'max'), 2)
        }
        completedRequestsPerSecond = [Math]::Round(
            (Get-K6Value $summary 'iterations' 'rate'), 2)
        successfulReservationsPerSecond = [Math]::Round(
            (Get-K6Value $summary 'reservations_created' 'rate'), 2)
        expectedResponseLatencyMilliseconds = [ordered]@{
            median = [Math]::Round((Get-K6Value $summary 'http_req_duration{expected_response:true}' 'med'), 2)
            p95 = [Math]::Round((Get-K6Value $summary 'http_req_duration{expected_response:true}' 'p(95)'), 2)
            p99 = [Math]::Round((Get-K6Value $summary 'http_req_duration{expected_response:true}' 'p(99)'), 2)
        }
        peakConnections = [ordered]@{
            hikariActive = [int](Get-Maximum $activeConnections)
            hikariPending = [int](Get-Maximum $pendingConnections)
            hikariConfiguredMaximum = 8
            postgresActive = [int](Get-Maximum $postgresConnections)
        }
        gatewayDiagnostics = [ordered]@{
            reservationStatus201 = @($reservationGatewayLog | Select-String -SimpleMatch '"status":201').Count
            reservationStatus499 = @($reservationGatewayLog | Select-String -SimpleMatch '"status":499').Count
            reservationStatus502 = @($reservationGatewayLog | Select-String -SimpleMatch '"status":502').Count
            reservationStatus503 = @($reservationGatewayLog | Select-String -SimpleMatch '"status":503').Count
            noLiveUpstreamErrors = @($nginxLog | Select-String -SimpleMatch 'no live upstreams').Count
            upstreamTimeoutErrors = @($nginxLog | Select-String -SimpleMatch 'upstream timed out').Count
        }
        applicationPoolTimeouts = [ordered]@{
            app1 = @($app1Log | Select-String -SimpleMatch 'Connection is not available').Count
            app2 = @($app2Log | Select-String -SimpleMatch 'Connection is not available').Count
        }
        serviceRestarts = [ordered]@{
            nginx = [int](& docker inspect --format '{{.RestartCount}}' $nginxContainer)
            app1 = [int](& docker inspect --format '{{.RestartCount}}' $app1Container)
            app2 = [int](& docker inspect --format '{{.RestartCount}}' $app2Container)
        }
        reconciliation = $reconciliation
        gates = [ordered]@{
            correctness = $correctnessPassed
            exactRequestAccounting = $requestAccountingPassed
            latencyP95AtMost300msAndP99AtMost800ms = $latencyPassed
            availabilityAtLeast99Point9Percent = $availabilitySloPassed
            availabilityAtLeast99Point99Percent = $availabilityRequestedPassed
        }
        k6ExitCode = $k6ExitCode
        goodOverall = $good
    }
    $result | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $resultPath
    Write-Host "High-traffic result written to $resultPath"
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
    if (-not $KeepRunning) {
        & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    }
    @('BASE_URL', 'RUN_ID', 'SUITE_NAME', 'JWT_SIGNING_KEY', 'JWT_ISSUER',
      'JWT_AUDIENCE', 'ADMIN_USERNAME', 'ADMIN_PASSWORD', 'VUS', 'REQUESTS',
      'K6_SUMMARY_TREND_STATS') |
        ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
}
