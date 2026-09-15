[CmdletBinding()]
param(
    [string]$RunId = ([DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')),
    [string[]]$Scenarios = @(
        'baseline',
        'duplicate-retries',
        'mixed-reserve-cancel',
        'sold-out-spike'
    ),
    [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $PSScriptRoot 'docker-compose.yml'
$monitorScript = Join-Path $PSScriptRoot 'monitor-day6.ps1'
$resultRoot = Join-Path $PSScriptRoot "results/$RunId"
$projectName = 'ticketsystem-day6'
$dbPort = 15432
$redisPort = 16379
$appPort = 18080
$baseUrl = "http://127.0.0.1:$appPort"
$appProcess = $null

foreach ($command in @('docker', 'java', 'mvn', 'k6')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command '$command' was not found on PATH."
    }
}
if (Test-Path -LiteralPath $resultRoot) {
    throw "Result directory already exists: $resultRoot. Choose a different -RunId."
}
New-Item -ItemType Directory -Path $resultRoot | Out-Null

$env:DAY6_DB_PORT = $dbPort
$env:DAY6_REDIS_PORT = $redisPort

function Wait-ForApplication {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
    do {
        if ($null -ne $appProcess -and $appProcess.HasExited) {
            throw "Application exited during startup. Inspect $resultRoot/app-stderr.log."
        }
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw 'Application did not become healthy within 90 seconds.'
}

function Get-K6Count([object]$summary, [string]$metricName) {
    $metric = $summary.metrics.PSObject.Properties[$metricName]
    if ($null -eq $metric) { return 0 }
    $metricValue = $metric.Value
    if ($metricValue.PSObject.Properties['values']) {
        return [int]$metricValue.values.count
    }
    if ($metricValue.PSObject.Properties['count']) {
        return [int]$metricValue.count
    }
    return 0
}

function Assert-Count([object]$summary, [string]$metricName, [int]$expected) {
    $actual = Get-K6Count $summary $metricName
    if ($actual -ne $expected) {
        throw "Metric $metricName expected $expected but was $actual."
    }
}

function Assert-K6Correctness([string]$scenario, [object]$summary) {
    Assert-Count $summary 'reservation_unexpected_responses' 0
    Assert-Count $summary 'errors_user_limit' 0
    Assert-Count $summary 'errors_idempotency_in_progress' 0
    Assert-Count $summary 'errors_rate_limited' 0
    switch ($scenario) {
        'baseline' {
            Assert-Count $summary 'reservations_created' 100
            $rejected = (Get-K6Count $summary 'errors_sold_out') `
                + (Get-K6Count $summary 'reservation_server_faults')
            if ($rejected -ne 400) {
                throw "Baseline expected 400 rejected attempts but recorded $rejected."
            }
        }
        'duplicate-retries' {
            Assert-Count $summary 'reservations_created' 1
            Assert-Count $summary 'reservations_replayed' 24
        }
        'mixed-reserve-cancel' {
            Assert-Count $summary 'reservations_created' 100
            Assert-Count $summary 'cancellations_succeeded' 50
        }
        'sold-out-spike' {
            Assert-Count $summary 'reservations_created' 0
            $rejected = (Get-K6Count $summary 'errors_sold_out') `
                + (Get-K6Count $summary 'reservation_server_faults')
            if ($rejected -ne 500) {
                throw "Sold-out spike expected 500 rejected attempts but recorded $rejected."
            }
        }
        default { throw "Unknown scenario: $scenario" }
    }
}

function Write-Reconciliation([string]$scenario) {
    $eventName = "day6-$scenario-$RunId"
    if ($eventName -notmatch '^[A-Za-z0-9-]+$') {
        throw "RunId produced an unsafe SQL fixture name: $eventName"
    }
    $sql = @"
SELECT r.total, r.available, r.held, r.sold,
       r.conservation_drift, r.held_drift, r.sold_drift,
       (SELECT count(*) FROM reservation x WHERE x.event_id = r.event_id),
       (SELECT count(*) FROM reservation x
          WHERE x.event_id = r.event_id AND x.status = 'CANCELLED')
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
    if ($fields.Count -ne 9) {
        throw "Malformed reconciliation output for ${scenario}: $raw"
    }
    $result = [ordered]@{
        eventName = $eventName
        total = [int]$fields[0]
        available = [int]$fields[1]
        held = [int]$fields[2]
        sold = [int]$fields[3]
        conservationDrift = [int]$fields[4]
        heldDrift = [int]$fields[5]
        soldDrift = [int]$fields[6]
        reservationRows = [int]$fields[7]
        cancelledRows = [int]$fields[8]
    }
    $result | ConvertTo-Json | Set-Content -LiteralPath `
        (Join-Path $resultRoot "$scenario-reconciliation.json")

    if ($result.available -lt 0 -or $result.held -lt 0 -or $result.sold -lt 0 `
            -or $result.conservationDrift -ne 0 -or $result.heldDrift -ne 0 `
            -or $result.soldDrift -ne 0) {
        throw "Inventory reconciliation failed for $scenario."
    }

    $expected = switch ($scenario) {
        'baseline' { @(100, 0, 100, 0, 100, 0) }
        'duplicate-retries' { @(100, 99, 1, 0, 1, 0) }
        'mixed-reserve-cancel' { @(100, 50, 50, 0, 100, 50) }
        'sold-out-spike' { @(5, 0, 5, 0, 2, 0) }
    }
    $actual = @($result.total, $result.available, $result.held, $result.sold,
        $result.reservationRows, $result.cancelledRows)
    if (($actual -join ',') -ne ($expected -join ',')) {
        throw "Unexpected final state for ${scenario}: $($actual -join ',')."
    }
}

function Write-TelemetrySummary([string]$scenario, [string]$telemetryPath) {
    $samples = @(Import-Csv -LiteralPath $telemetryPath)
    function Maximum([object[]]$rows, [string]$property) {
        $numbers = @($rows | ForEach-Object {
            $value = $_.$property
            if ($null -ne $value -and $value -ne '') { [double]$value }
        })
        if ($numbers.Count -eq 0) { return $null }
        return ($numbers | Measure-Object -Maximum).Maximum
    }
    [ordered]@{
        samples = $samples.Count
        maxHikariActive = Maximum $samples 'hikari_active'
        maxHikariPending = Maximum $samples 'hikari_pending'
        configuredHikariMax = Maximum $samples 'hikari_max'
        maxPostgresActive = Maximum $samples 'postgres_active'
        maxPostgresLockWaiters = Maximum $samples 'postgres_lock_waiters'
    } | ConvertTo-Json | Set-Content -LiteralPath `
        (Join-Path $resultRoot "$scenario-telemetry-summary.json")
}

try {
    Push-Location $repoRoot
    & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    & docker compose --project-name $projectName -f $composeFile up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Day 6 dependency containers failed to start.' }

    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }

    $env:SPRING_PROFILES_ACTIVE = 'load'
    $env:SERVER_PORT = $appPort
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$dbPort/ticketsystem"
    $env:DB_USERNAME = 'ticketsystem'
    $env:DB_PASSWORD = 'ticketsystem'
    $env:REDIS_HOST = '127.0.0.1'
    $env:REDIS_PORT = $redisPort
    $jarPath = Join-Path $repoRoot 'target/ticket-system-0.2.0-SNAPSHOT.jar'
    # Oracle's Windows javapath executable launches a child JVM and exits immediately,
    # which makes process cleanup track the wrong PID. Resolve java.home and launch the
    # real executable so the runner can always stop and await the exact application JVM.
    $javaSettings = & java -XshowSettings:properties -version 2>&1
    $javaHomeLine = $javaSettings | Where-Object { $_ -match '^\s*java\.home\s*=' } |
        Select-Object -First 1
    if (-not $javaHomeLine) { throw 'Could not resolve java.home.' }
    $javaHome = ($javaHomeLine -split '=', 2)[1].Trim()
    $javaExecutable = Join-Path $javaHome 'bin/java.exe'
    $appProcess = Start-Process -FilePath $javaExecutable -ArgumentList @(
        '-jar', "`"$jarPath`""
    ) -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
      -RedirectStandardOutput (Join-Path $resultRoot 'app-stdout.log') `
      -RedirectStandardError (Join-Path $resultRoot 'app-stderr.log')
    Wait-ForApplication

    $loginBody = @{ username = 'load-admin'; password = 'password' } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$baseUrl/api/v1/auth/login" -Method Post `
        -ContentType 'application/json' -Body $loginBody
    $adminToken = $login.accessToken

    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $computer = Get-CimInstance Win32_ComputerSystem
    $disk = Get-PhysicalDisk -ErrorAction SilentlyContinue | Select-Object -First 1
    [ordered]@{
        runId = $RunId
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
        operatingSystem = [Environment]::OSVersion.VersionString
        cpu = $cpu.Name.Trim()
        logicalProcessors = $cpu.NumberOfLogicalProcessors
        memoryGiB = [Math]::Round($computer.TotalPhysicalMemory / 1GB, 2)
        diskMediaType = if ($disk) { $disk.MediaType.ToString() } else { 'unknown' }
        java = (& java -version 2>&1 | Select-Object -First 1).ToString()
        k6 = (& k6 version).ToString()
        docker = (& docker version --format '{{.Server.Version}}').ToString()
        postgres = (& docker compose --project-name $projectName -f $composeFile exec -T `
            postgres psql -U ticketsystem -d ticketsystem -At -c 'SHOW server_version;').ToString()
        redis = (& docker compose --project-name $projectName -f $composeFile exec -T `
            redis redis-server --version).ToString()
        topology = 'one Spring Boot process; PostgreSQL and Redis in Docker; k6 on host'
        hikariMaximumPoolSize = 20
        rateLimitEnabled = $false
        expirySchedulerEnabled = $false
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'run-metadata.json')

    $env:BASE_URL = $baseUrl
    $env:RUN_ID = $RunId
    $env:JWT_SIGNING_KEY = 'day6-load-only-signing-key-0123456789abcdef'
    $env:JWT_ISSUER = 'ticket-system-load'
    $env:JWT_AUDIENCE = 'ticket-system-api'
    $env:ADMIN_USERNAME = 'load-admin'
    $env:ADMIN_PASSWORD = 'password'
    $env:K6_SUMMARY_TREND_STATS = 'avg,min,med,p(90),p(95),p(99),max'

    foreach ($scenario in $Scenarios) {
        $scriptPath = Join-Path $PSScriptRoot "k6/$scenario.js"
        if (-not (Test-Path -LiteralPath $scriptPath)) {
            throw "Scenario script was not found: $scriptPath"
        }
        $summaryPath = Join-Path $resultRoot "$scenario-summary.json"
        $consolePath = Join-Path $resultRoot "$scenario-console.log"
        $dashboardPath = Join-Path $resultRoot "$scenario-dashboard.html"
        $telemetryPath = Join-Path $resultRoot "$scenario-telemetry.csv"
        $stopPath = Join-Path $resultRoot "$scenario-monitor.stop"

        $env:K6_WEB_DASHBOARD = 'true'
        $env:K6_WEB_DASHBOARD_EXPORT = $dashboardPath
        $env:K6_WEB_DASHBOARD_PERIOD = '1s'
        $monitorJob = Start-Job -FilePath $monitorScript -ArgumentList @(
            $baseUrl, $adminToken, $composeFile, $projectName,
            $telemetryPath, $stopPath, 500
        )
        try {
            & k6 run "--summary-export=$summaryPath" $scriptPath 2>&1 |
                Tee-Object -FilePath $consolePath
            $k6ExitCode = $LASTEXITCODE
        }
        finally {
            New-Item -ItemType File -Force -Path $stopPath | Out-Null
            Wait-Job -Job $monitorJob -Timeout 15 | Out-Null
            if ($monitorJob.State -eq 'Running') { Stop-Job -Job $monitorJob }
            Receive-Job -Job $monitorJob
            Remove-Job -Job $monitorJob -Force
            Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
        }

        $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
        Assert-K6Correctness $scenario $summary
        Write-Reconciliation $scenario
        Write-TelemetrySummary $scenario $telemetryPath
        [ordered]@{
            scenario = $scenario
            k6ExitCode = $k6ExitCode
            performanceThresholdsPassed = ($k6ExitCode -eq 0)
        } | ConvertTo-Json | Set-Content -LiteralPath `
            (Join-Path $resultRoot "$scenario-run-status.json")
    }

    Write-Host "Day 6 run completed: $resultRoot"
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
    if (-not $KeepRunning) {
        if ($null -ne $appProcess -and -not $appProcess.HasExited) {
            Stop-Process -Id $appProcess.Id -Force
            Wait-Process -Id $appProcess.Id -Timeout 15 -ErrorAction SilentlyContinue
        }
        & docker compose --project-name $projectName -f $composeFile down --volumes --remove-orphans
    }
}
