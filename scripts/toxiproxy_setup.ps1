# Manage the 'redis' toxiproxy proxy (the gateway reaches Redis through
# redis://toxiproxy:26379) so Redis faults can be injected from the host.
#
# Usage:
#   scripts/toxiproxy_setup.ps1 setup           # create the redis proxy (idempotent)
#   scripts/toxiproxy_setup.ps1 latency 100     # add +100ms downstream latency
#   scripts/toxiproxy_setup.ps1 timeout         # hang Redis responses (timeout toxic)
#   scripts/toxiproxy_setup.ps1 clear           # remove all toxics (recreate proxy)
#   scripts/toxiproxy_setup.ps1 list            # show current proxies as JSON
param(
    [Parameter(Position = 0)][string]$Action = "setup",
    [Parameter(Position = 1)][string]$Ms = "100"
)
$ErrorActionPreference = "Stop"
$PSDefaultParameterValues['Invoke-RestMethod:UserAgent'] = 'toxiproxy/2.11'

$Api = if ($env:TOXIPROXY_URL) { $env:TOXIPROXY_URL } else { "http://localhost:8474" }
$Proxy = "redis"
$Listen = "0.0.0.0:26379"
$Upstream = "redis:6379"

function Ensure-Proxy {
    try {
        Invoke-RestMethod -Uri "$Api/proxies/$Proxy" | Out-Null
    }
    catch {
        $body = @{ name = $Proxy; listen = $Listen; upstream = $Upstream } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri "$Api/proxies" -ContentType "application/json" -Body $body | Out-Null
        Write-Host "proxy '$Proxy' created ($Listen -> $Upstream)"
    }
}

function Remove-Toxic([string]$Name) {
    try {
        Invoke-RestMethod -Method Delete -Uri "$Api/proxies/$Proxy/toxics/$Name" | Out-Null
    }
    catch { }
}

switch ($Action) {
    "setup" {
        Ensure-Proxy
        Write-Host "toxiproxy '$Proxy' ready"
    }
    "latency" {
        if (-not $Ms) { Write-Error "usage: toxiproxy_setup.ps1 latency <ms>"; exit 2 }
        Ensure-Proxy
        Remove-Toxic "latency_downstream"
        $body = @{
            name = "latency_downstream"; type = "latency"; stream = "downstream"
            toxicity = 1.0; attributes = @{ latency = [int]$Ms }
        } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri "$Api/proxies/$Proxy/toxics" -ContentType "application/json" -Body $body | Out-Null
        Write-Host "latency toxic added: +$Ms ms on downstream traffic"
    }
    "timeout" {
        Ensure-Proxy
        Remove-Toxic "timeout_downstream"
        $body = @{
            name = "timeout_downstream"; type = "timeout"; stream = "downstream"
            toxicity = 1.0; attributes = @{ timeout = 0 }
        } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri "$Api/proxies/$Proxy/toxics" -ContentType "application/json" -Body $body | Out-Null
        Write-Host "timeout toxic added: Redis responses now hang"
    }
    "clear" {
        try { Invoke-RestMethod -Method Delete -Uri "$Api/proxies/$Proxy" | Out-Null } catch { }
        Ensure-Proxy
        Write-Host "all toxics cleared (proxy recreated clean)"
    }
    "list" {
        (Invoke-RestMethod -Uri "$Api/proxies") | ConvertTo-Json -Depth 10
    }
    default {
        Write-Error "usage: toxiproxy_setup.ps1 setup|latency <ms>|timeout|clear|list"
        exit 2
    }
}