[CmdletBinding()]
param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$Duration = '30m',
    [ValidateRange(1, 200)][int]$VirtualUsers = 10,
    [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'deploy/day10/docker-compose.yml'
$monitorScript = Join-Path $PSScriptRoot 'monitor-day11.ps1'
$resultRoot = Join-Path $PSScriptRoot "results/day11-$RunId"
$projectName = "ticket-day11-$($RunId.ToLowerInvariant() -replace '[^a-z0-9]', '')"
$apiBase = 'http://127.0.0.1:28100'
$prometheusBase = 'http://127.0.0.1:29090'

if ($RunId -notmatch '^[A-Za-z0-9-]+$') {
    throw 'RunId may contain only letters, digits, and hyphens.'
}
if ($Duration -notmatch '^\d+(s|m|h)$') {
    throw 'Duration must be a k6 duration such as 5m, 30m, or 1h.'
}

New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null
$summaryPath = Join-Path $resultRoot 'soak-summary.json'
$consolePath = Join-Path $resultRoot 'soak-console.log'
$telemetryPath = Join-Path $resultRoot 'soak-telemetry.csv'
$resultPath = Join-Path $resultRoot 'release-metrics.json'
$stopPath = Join-Path $resultRoot 'monitor.stop'
$eventName = "day11-soak-cycle-$RunId"

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
    $query = [Uri]::EscapeDataString(
        'sum(jvm_memory_used_bytes{area="heap"}) by (instance, exported_instance)')
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(1)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $samples = @((Invoke-RestMethod `
                -Uri "$prometheusBase/api/v1/query?query=$query" -TimeoutSec 3).data.result)
            if ($samples.Count -eq 2) { return }
        }
        catch {
            Start-Sleep -Seconds 1
        }
        Start-Sleep -Seconds 1
    }
    throw 'Prometheus did not collect heap samples from both replicas.'
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

function Get-Median([double[]]$values) {
    if ($values.Count -eq 0) { return 0 }
    $sorted = @($values | Sort-Object)
    $middle = [Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}

function Get-Reconciliation {
    $sql = @"
SELECT r.total, r.available, r.held, r.sold,
       r.conservation_drift, r.held_drift, r.sold_drift,
       (SELECT count(*) FROM reservation x WHERE x.event_id = r.event_id),
       (SELECT count(*) FROM reservation x WHERE x.event_id = r.event_id AND x.status = 'CANCELLED'),
       (SELECT count(*) FROM reservation x WHERE x.event_id = r.event_id AND x.status = 'PENDING')
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
    if ($fields.Count -ne 10) { throw "Malformed reconciliation output: $raw" }
    return [ordered]@{
        total = [int]$fields[0]
        available = [int]$fields[1]
        held = [int]$fields[2]
        sold = [int]$fields[3]
        conservationDrift = [int]$fields[4]
        heldDrift = [int]$fields[5]
        soldDrift = [int]$fields[6]
        reservationRows = [int]$fields[7]
        cancelledRows = [int]$fields[8]
        pendingRows = [int]$fields[9]
    }
}

try {
    Push-Location $repoRoot
    & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    & docker compose --project-name $projectName -f $composeFile up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw 'Day 11 soak topology failed to start.' }

    Wait-ForHttp "$apiBase/readyz"
    Wait-ForHttp "$prometheusBase/-/ready"
    Wait-ForPrometheusSamples

    $env:BASE_URL = $apiBase
    $env:RUN_ID = $RunId
    $env:SUITE_NAME = 'day11'
    $env:VUS = $VirtualUsers.ToString()
    $env:SOAK_DURATION = $Duration
    $env:JWT_SIGNING_KEY = 'day10-shared-signing-key-0123456789abcdef'
    $env:JWT_ISSUER = 'ticket-system-load'
    $env:JWT_AUDIENCE = 'ticket-system-api'
    $env:ADMIN_USERNAME = 'load-admin'
    $env:ADMIN_PASSWORD = 'password'
    $env:K6_SUMMARY_TREND_STATS = 'avg,min,med,p(90),p(95),p(99),max'

    $monitorJob = Start-Job -FilePath $monitorScript -ArgumentList @(
        $prometheusBase, $composeFile, $projectName, $telemetryPath, $stopPath, 5
    )
    try {
        & k6 run "--summary-export=$summaryPath" `
            (Join-Path $PSScriptRoot 'k6/day11-soak.js') 2>&1 |
            Tee-Object -FilePath $consolePath
        $k6ExitCode = $LASTEXITCODE
        Start-Sleep -Seconds 10
    }
    finally {
        New-Item -ItemType File -Force -Path $stopPath | Out-Null
        Wait-Job -Job $monitorJob -Timeout 20 | Out-Null
        if ($monitorJob.State -eq 'Running') { Stop-Job -Job $monitorJob }
        Receive-Job -Job $monitorJob
        Remove-Job -Job $monitorJob -Force
        Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    }
    if ($k6ExitCode -ne 0) { throw "Day 11 k6 soak failed (exit $k6ExitCode)." }

    $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
    $reconciliation = Get-Reconciliation
    if ($reconciliation.available -ne $reconciliation.total `
            -or $reconciliation.held -ne 0 -or $reconciliation.sold -ne 0 `
            -or $reconciliation.conservationDrift -ne 0 `
            -or $reconciliation.heldDrift -ne 0 `
            -or $reconciliation.soldDrift -ne 0 `
            -or $reconciliation.pendingRows -ne 0 `
            -or $reconciliation.reservationRows -ne $reconciliation.cancelledRows) {
        throw 'Day 11 inventory/reconciliation acceptance gate failed.'
    }

    $telemetry = @(Import-Csv -LiteralPath $telemetryPath | Where-Object {
        $_.app1_heap_bytes -match '^\d' -and $_.app2_heap_bytes -match '^\d'
    })
    if ($telemetry.Count -lt 6) {
        throw "Only $($telemetry.Count) complete telemetry samples were captured."
    }
    $heap = @($telemetry | ForEach-Object {
        [double]$_.app1_heap_bytes + [double]$_.app2_heap_bytes
    })
    $window = [Math]::Max(2, [Math]::Floor($heap.Count * 0.2))
    $firstHeap = Get-Median @($heap | Select-Object -First $window)
    $lastHeap = Get-Median @($heap | Select-Object -Last $window)
    $heapGrowth = $lastHeap - $firstHeap
    $allowedGrowth = [Math]::Max(64MB, $firstHeap * 0.5)
    $heapLeakSignal = $heapGrowth -gt $allowedGrowth

    $activeConnections = @($telemetry | ForEach-Object {
        [double]$_.app1_active + [double]$_.app2_active
    })
    $pendingConnections = @($telemetry | ForEach-Object {
        [double]$_.app1_pending + [double]$_.app2_pending
    })
    $postgresConnections = @($telemetry | Where-Object {
        $_.postgres_active -match '^\d'
    } | ForEach-Object { [double]$_.postgres_active })
    $finalPending = Get-Maximum @($pendingConnections | Select-Object -Last $window)
    if ($heapLeakSignal -or $finalPending -ne 0) {
        throw 'Day 11 resource-leak acceptance gate failed; inspect release-metrics.json.'
    }

    $result = [ordered]@{
        runId = $RunId
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
        requestedDuration = $Duration
        virtualUsers = $VirtualUsers
        iterations = [int](Get-K6Value $summary 'iterations' 'count')
        httpRequests = [int](Get-K6Value $summary 'http_reqs' 'count')
        reservationsCreated = [int](Get-K6Value $summary 'reservations_created' 'count')
        idempotentReplays = [int](Get-K6Value $summary 'reservations_replayed' 'count')
        cancellations = [int](Get-K6Value $summary 'cancellations_succeeded' 'count')
        serverFaults = [int](Get-K6Value $summary 'reservation_server_faults' 'count')
        unexpectedResponses = [int](Get-K6Value $summary 'reservation_unexpected_responses' 'count')
        httpP95Milliseconds = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(95)'), 2)
        reserveP95Milliseconds = [Math]::Round((Get-K6Value $summary 'reserve_latency' 'p(95)'), 2)
        cancelP95Milliseconds = [Math]::Round((Get-K6Value $summary 'cancel_latency' 'p(95)'), 2)
        telemetrySamples = $telemetry.Count
        heap = [ordered]@{
            initialWindowMedianMiB = [Math]::Round($firstHeap / 1MB, 2)
            finalWindowMedianMiB = [Math]::Round($lastHeap / 1MB, 2)
            growthMiB = [Math]::Round($heapGrowth / 1MB, 2)
            peakMiB = [Math]::Round((Get-Maximum $heap) / 1MB, 2)
            allowedGrowthMiB = [Math]::Round($allowedGrowth / 1MB, 2)
            leakSignal = $heapLeakSignal
        }
        connections = [ordered]@{
            maximumHikariActive = [int](Get-Maximum $activeConnections)
            maximumHikariPending = [int](Get-Maximum $pendingConnections)
            finalWindowMaximumPending = [int]$finalPending
            maximumPostgresActive = [int](Get-Maximum $postgresConnections)
            configuredHikariMaximum = 16
        }
        reconciliation = $reconciliation
        passed = $true
    }
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resultPath
    Write-Host "Day 11 soak passed: $resultPath"
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
    if (-not $KeepRunning) {
        & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    }
    @('BASE_URL', 'RUN_ID', 'SUITE_NAME', 'VUS', 'SOAK_DURATION', 'JWT_SIGNING_KEY',
      'JWT_ISSUER', 'JWT_AUDIENCE', 'ADMIN_USERNAME', 'ADMIN_PASSWORD',
      'K6_SUMMARY_TREND_STATS') | ForEach-Object {
        Remove-Item "Env:$_" -ErrorAction SilentlyContinue
    }
}
