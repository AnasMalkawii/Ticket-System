[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][string]$AdminToken,
    [Parameter(Mandatory = $true)][string]$ComposeFile,
    [Parameter(Mandatory = $true)][string]$ProjectName,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$StopPath,
    [int]$IntervalMilliseconds = 500
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$headers = @{ Authorization = "Bearer $AdminToken" }

function Get-GaugeValue([string]$metricName) {
    try {
        $metric = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/$metricName" `
            -Headers $headers -TimeoutSec 2
        $measurement = $metric.measurements |
            Where-Object { $_.statistic -eq 'VALUE' } |
            Select-Object -First 1
        if ($null -eq $measurement) { return $null }
        return [double]$measurement.value
    }
    catch {
        return $null
    }
}

$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
[IO.File]::WriteAllText($OutputPath,
    "timestamp_utc,hikari_active,hikari_pending,hikari_max,postgres_active,postgres_lock_waiters`n")

while (-not (Test-Path -LiteralPath $StopPath)) {
    $active = Get-GaugeValue 'hikaricp.connections.active'
    $pending = Get-GaugeValue 'hikaricp.connections.pending'
    $maximum = Get-GaugeValue 'hikaricp.connections.max'

    $sql = @"
SELECT count(*) FILTER (WHERE state = 'active'),
       count(*) FILTER (WHERE wait_event_type = 'Lock')
FROM pg_stat_activity
WHERE datname = 'ticketsystem';
"@
    $pg = & docker compose --project-name $ProjectName -f $ComposeFile exec -T postgres `
        psql -U ticketsystem -d ticketsystem -At -F '|' -c $sql 2>$null
    $pgFields = if ($LASTEXITCODE -eq 0 -and $pg) {
        ($pg | Select-Object -Last 1) -split '\|'
    }
    else {
        @('', '')
    }

    $values = @(
        [DateTimeOffset]::UtcNow.ToString('O'),
        $active,
        $pending,
        $maximum,
        $pgFields[0],
        $pgFields[1]
    ) -join ','
    [IO.File]::AppendAllText($OutputPath, "$values`n")
    Start-Sleep -Milliseconds $IntervalMilliseconds
}
