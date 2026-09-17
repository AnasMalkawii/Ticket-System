[CmdletBinding()]
param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [ValidateSet('PESSIMISTIC', 'ATOMIC')]
    [string[]]$Strategies = @('PESSIMISTIC', 'ATOMIC'),
    [int[]]$VirtualUsers = @(500, 1000, 2000),
    [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'deploy/day9/docker-compose.yml'
$monitorScript = Join-Path $PSScriptRoot 'monitor-day9.ps1'
$resultRoot = Join-Path $PSScriptRoot "results/day9-$RunId"
$projectName = "ticket-day9-$($RunId.ToLowerInvariant() -replace '[^a-z0-9]', '')"
$nginxBase = 'http://127.0.0.1:28090'
$app1Base = 'http://127.0.0.1:28081'
$app2Base = 'http://127.0.0.1:28082'
$allResults = [System.Collections.Generic.List[object]]::new()

if ($VirtualUsers.Count -eq 0 -or ($VirtualUsers | Where-Object { $_ -lt 1 })) {
    throw 'VirtualUsers must contain positive integers.'
}
if ($RunId -notmatch '^[A-Za-z0-9-]+$') {
    throw 'RunId may contain only letters, digits, and hyphens.'
}

New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null

function Wait-ForReady([string]$url) {
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri "$url/readyz" -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        }
        catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "Application did not become ready: $url"
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

function Maximum([object[]]$rows, [string[]]$properties) {
    $numbers = foreach ($row in $rows) {
        $sum = 0.0
        $present = $false
        foreach ($property in $properties) {
            $value = $row.$property
            if ($null -ne $value -and $value -ne '') {
                $sum += [double]$value
                $present = $true
            }
        }
        if ($present) { $sum }
    }
    if (@($numbers).Count -eq 0) { return 0 }
    return [double](($numbers | Measure-Object -Maximum).Maximum)
}

function Assert-StatelessTopology {
    $loginBody = @{ username = 'load-admin'; password = 'password' } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$app1Base/api/v1/auth/login" -Method Post `
        -ContentType 'application/json' -Body $loginBody
    $token = $login.accessToken

    # A token issued by replica 1 must work on replica 2 without a shared HTTP session.
    Invoke-RestMethod -Uri "$app2Base/actuator/metrics" `
        -Headers @{ Authorization = "Bearer $token" } | Out-Null

    $upstreams = for ($i = 0; $i -lt 20; $i++) {
        $response = Invoke-WebRequest -Uri "$nginxBase/livez" -TimeoutSec 3
        $response.Headers['X-Upstream-Replica']
    }
    $unique = @($upstreams | Where-Object { $_ } | Sort-Object -Unique)
    if ($unique.Count -ne 2) {
        throw "Nginx did not demonstrate both replicas; observed: $($unique -join ', ')"
    }
    return [pscustomobject]@{ token = $token; upstreams = $unique }
}

function Get-Reconciliation([string]$eventName) {
    $sql = @"
SELECT r.total, r.available, r.held, r.sold,
       r.conservation_drift, r.held_drift, r.sold_drift,
       (SELECT count(*) FROM reservation x WHERE x.event_id = r.event_id)
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
    $keyBytes = [byte[]]::new(48)
    [Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    $env:JWT_ACCESS_SECRET = [Convert]::ToBase64String($keyBytes)
    [Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    $env:JWT_REFRESH_SECRET = [Convert]::ToBase64String($keyBytes)
    $env:INVENTORY_STRATEGY = $Strategies[0]
    & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    & docker compose --project-name $projectName -f $composeFile up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw 'Day 9 topology failed to start.' }

    foreach ($strategy in $Strategies) {
        $env:INVENTORY_STRATEGY = $strategy
        & docker compose --project-name $projectName -f $composeFile up -d --wait `
            --force-recreate app1 app2 nginx
        if ($LASTEXITCODE -ne 0) { throw "Failed to start $strategy replicas." }
        Wait-ForReady $app1Base
        Wait-ForReady $app2Base
        Wait-ForReady $nginxBase
        $topology = Assert-StatelessTopology

        foreach ($vus in $VirtualUsers) {
            $strategyName = $strategy.ToLowerInvariant()
            $caseName = "$strategyName-$vus"
            $caseRunId = "$RunId-$caseName"
            $eventName = "day9-hot-row-$caseRunId"
            $summaryPath = Join-Path $resultRoot "$caseName-summary.json"
            $consolePath = Join-Path $resultRoot "$caseName-console.log"
            $telemetryPath = Join-Path $resultRoot "$caseName-telemetry.csv"
            $stopPath = Join-Path $resultRoot "$caseName-monitor.stop"

            $env:BASE_URL = $nginxBase
            $env:RUN_ID = $caseRunId
            $env:SUITE_NAME = 'day9'
            $env:VUS = $vus.ToString()
            $env:ARRIVAL_SPREAD_SECONDS = '2'
            $env:ADMIN_USERNAME = 'load-admin'
            $env:ADMIN_PASSWORD = 'password'
            $env:K6_SUMMARY_TREND_STATS = 'avg,min,med,p(90),p(95),p(99),max'

            Write-Host "Running Day 9 benchmark: strategy=$strategy VUs=$vus"
            $monitorJob = Start-Job -FilePath $monitorScript -ArgumentList @(
                "$app1Base;$app2Base", $topology.token, $composeFile, $projectName,
                $telemetryPath, $stopPath, 250
            )
            try {
                & k6 run "--summary-export=$summaryPath" `
                    (Join-Path $PSScriptRoot 'k6/day9-hot-row.js') 2>&1 |
                    Tee-Object -FilePath $consolePath
                $k6ExitCode = $LASTEXITCODE
            }
            finally {
                New-Item -ItemType File -Force -Path $stopPath | Out-Null
                Wait-Job -Job $monitorJob -Timeout 20 | Out-Null
                if ($monitorJob.State -eq 'Running') { Stop-Job -Job $monitorJob }
                Receive-Job -Job $monitorJob
                Remove-Job -Job $monitorJob -Force
                Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
            }
            if ($k6ExitCode -ne 0) { throw "k6 failed for $caseName (exit $k6ExitCode)." }

            $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
            $reconciliation = Get-Reconciliation $eventName
            if ($reconciliation.available -lt 0 -or $reconciliation.held -lt 0 `
                    -or $reconciliation.sold -lt 0 `
                    -or $reconciliation.conservationDrift -ne 0 `
                    -or $reconciliation.heldDrift -ne 0 `
                    -or $reconciliation.soldDrift -ne 0 `
                    -or $reconciliation.held -gt $reconciliation.total `
                    -or $reconciliation.reservationRows -ne $reconciliation.held) {
                throw "Inventory acceptance gate failed for $caseName."
            }

            $telemetry = @(Import-Csv -LiteralPath $telemetryPath)
            $attempts = [int](Get-K6Value $summary 'iterations' 'count')
            $created = [int](Get-K6Value $summary 'reservations_created' 'count')
            $soldOut = [int](Get-K6Value $summary 'errors_sold_out' 'count')
            $iterationsPerSecond = Get-K6Value $summary 'iterations' 'rate'
            $usefulResponsesPerSecond = if ($attempts -eq 0) {
                0
            }
            else {
                ($created + $soldOut) * $iterationsPerSecond / $attempts
            }
            $caseResult = [ordered]@{
                strategy = $strategy
                virtualUsers = $vus
                attempts = $attempts
                responsesCreated = $created
                responsesSoldOut = $soldOut
                serverOrConnectionFaults = [int](Get-K6Value $summary 'reservation_server_faults' 'count')
                unexpectedResponses = [int](Get-K6Value $summary 'reservation_unexpected_responses' 'count')
                p50Milliseconds = [Math]::Round((Get-K6Value $summary 'reserve_latency' 'med'), 2)
                p95Milliseconds = [Math]::Round((Get-K6Value $summary 'reserve_latency' 'p(95)'), 2)
                p99Milliseconds = [Math]::Round((Get-K6Value $summary 'reserve_latency' 'p(99)'), 2)
                iterationsPerSecond = [Math]::Round($iterationsPerSecond, 2)
                usefulResponsesPerSecond = [Math]::Round($usefulResponsesPerSecond, 2)
                maxHikariActive = [int](Maximum $telemetry @('app1_active', 'app2_active'))
                maxHikariPending = [int](Maximum $telemetry @('app1_pending', 'app2_pending'))
                maxPostgresActive = [int](Maximum $telemetry @('postgres_active'))
                maxPostgresLockWaiters = [int](Maximum $telemetry @('postgres_lock_waiters'))
                inventory = $reconciliation
                zeroOversell = $true
                replicasObserved = $topology.upstreams
            }
            $caseResult | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath `
                (Join-Path $resultRoot "$caseName-result.json")
            $allResults.Add([pscustomobject]$caseResult)
        }
    }

    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $computer = Get-CimInstance Win32_ComputerSystem
    $postgresMax = & docker compose --project-name $projectName -f $composeFile exec -T `
        postgres psql -U ticketsystem -d ticketsystem -At -c 'SHOW max_connections;'
    [ordered]@{
        runId = $RunId
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
        cpu = $cpu.Name.Trim()
        logicalProcessors = $cpu.NumberOfLogicalProcessors
        memoryGiB = [Math]::Round($computer.TotalPhysicalMemory / 1GB, 2)
        java = (& java -version 2>&1 | Select-Object -First 1).ToString()
        k6 = (& k6 version).ToString()
        docker = (& docker version --format '{{.Server.Version}}').ToString()
        topology = 'Nginx -> two stateless Spring Boot replicas -> one PostgreSQL primary'
        hikariPoolPerReplica = 8
        combinedApplicationPool = 16
        postgresMaxConnections = [int]($postgresMax | Select-Object -Last 1)
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'run-metadata.json')
    $allResults | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath `
        (Join-Path $resultRoot 'all-results.json')

    Write-Host "Day 9 benchmark completed: $resultRoot"
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
    if (-not $KeepRunning) {
        & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    }
    Remove-Item Env:INVENTORY_STRATEGY -ErrorAction SilentlyContinue
}
