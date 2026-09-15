[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PrometheusBaseUrl,
    [Parameter(Mandatory = $true)][string]$ComposeFile,
    [Parameter(Mandatory = $true)][string]$ProjectName,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$StopPath,
    [int]$IntervalSeconds = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-PrometheusResult([string]$query) {
    try {
        $encoded = [Uri]::EscapeDataString($query)
        $response = Invoke-RestMethod -Uri "$PrometheusBaseUrl/api/v1/query?query=$encoded" `
            -TimeoutSec 3
        return @($response.data.result)
    }
    catch {
        return @()
    }
}

function Get-InstanceValue([object[]]$result, [string]$instance) {
    $sample = $result | Where-Object {
        if ($null -eq $_ -or $null -eq $_.metric) { return $false }
        $target = $_.metric.PSObject.Properties['instance']
        $exported = $_.metric.PSObject.Properties['exported_instance']
        ($null -ne $target -and $target.Value -eq "$instance`:8080") -or
        ($null -ne $exported -and $exported.Value -eq $instance)
    } | Select-Object -First 1
    if ($null -eq $sample) { return $null }
    return [double]$sample.value[1]
}

$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
[IO.File]::WriteAllText($OutputPath,
    "timestamp_utc,app1_heap_bytes,app2_heap_bytes,app1_active,app2_active,app1_pending,app2_pending,postgres_active`n")

while (-not (Test-Path -LiteralPath $StopPath)) {
    try {
        $heap = Get-PrometheusResult 'sum(jvm_memory_used_bytes{area="heap"}) by (instance, exported_instance)'
        $active = Get-PrometheusResult 'hikaricp_connections_active'
        $pending = Get-PrometheusResult 'hikaricp_connections_pending'
        $sql = @"
SELECT count(*) FILTER (WHERE state = 'active')
FROM pg_stat_activity
WHERE datname = 'ticketsystem';
"@
        $postgresActive = & docker compose --project-name $ProjectName -f $ComposeFile exec -T `
            postgres psql -U ticketsystem -d ticketsystem -At -c $sql 2>$null
        if ($LASTEXITCODE -ne 0) { $postgresActive = $null }

        $values = @(
            [DateTimeOffset]::UtcNow.ToString('O'),
            (Get-InstanceValue $heap 'app1'),
            (Get-InstanceValue $heap 'app2'),
            (Get-InstanceValue $active 'app1'),
            (Get-InstanceValue $active 'app2'),
            (Get-InstanceValue $pending 'app1'),
            (Get-InstanceValue $pending 'app2'),
            ($postgresActive | Select-Object -Last 1)
        ) -join ','
        [IO.File]::AppendAllText($OutputPath, "$values`n")
    }
    catch {
        $failure = $_.Exception.Message -replace '[\r\n,]', ' '
        [IO.File]::AppendAllText("$OutputPath.errors.log",
            "$([DateTimeOffset]::UtcNow.ToString('O')),$failure`n")
    }
    Start-Sleep -Seconds $IntervalSeconds
}
