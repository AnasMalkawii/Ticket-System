[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseUrls,
    [Parameter(Mandatory = $true)][string]$AdminToken,
    [Parameter(Mandatory = $true)][string]$ComposeFile,
    [Parameter(Mandatory = $true)][string]$ProjectName,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$StopPath,
    [int]$IntervalMilliseconds = 250
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$urls = $BaseUrls -split ';'
$headers = @{ Authorization = "Bearer $AdminToken" }

function Get-GaugeValue([string]$baseUrl, [string]$metricName) {
    try {
        $metric = Invoke-RestMethod -Uri "$baseUrl/actuator/metrics/$metricName" `
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
    "timestamp_utc,app1_active,app1_pending,app2_active,app2_pending,postgres_active,postgres_lock_waiters`n")

while (-not (Test-Path -LiteralPath $StopPath)) {
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
        (Get-GaugeValue $urls[0] 'hikaricp.connections.active'),
        (Get-GaugeValue $urls[0] 'hikaricp.connections.pending'),
        (Get-GaugeValue $urls[1] 'hikaricp.connections.active'),
        (Get-GaugeValue $urls[1] 'hikaricp.connections.pending'),
        $pgFields[0],
        $pgFields[1]
    ) -join ','
    [IO.File]::AppendAllText($OutputPath, "$values`n")
    Start-Sleep -Milliseconds $IntervalMilliseconds
}
