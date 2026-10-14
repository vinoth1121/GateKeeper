# Full local benchmark run: compose up -> toxiproxy setup -> health gate ->
# every k6 script -> results collected in results/.
#
# Usage (from repo root):
#   powershell -File scripts\run_load.ps1
#   powershell -File scripts\run_load.ps1 -Scripts steady,overhead
#   powershell -File scripts\run_load.ps1 -NoBuild    # reuse existing images
param(
    [string[]]$Scripts = @("steady", "ramp", "connections", "overhead", "burst"),
    [switch]$NoBuild
)
$ErrorActionPreference = "Stop"

$Repo = Split-Path -Parent $PSScriptRoot
$Compose = Join-Path $Repo "infra\docker-compose.yml"
$ResultsDir = Join-Path $Repo "results"

Write-Host "== GateKeeper load run =="

# --- 1. bring the stack up ------------------------------------------------
$upArgs = @("compose", "-f", $Compose, "up", "-d")
if (-not $NoBuild) { $upArgs += "--build" }
& docker @upArgs
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed" }

# The gateway reaches Redis through toxiproxy, so the proxy must exist.
& (Join-Path $PSScriptRoot "toxiproxy_setup.ps1") "setup"

# --- 2. wait for gateway health -------------------------------------------
$deadline = (Get-Date).AddMinutes(5)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 3
        if ($h.status -eq "UP") { $healthy = $true; break }
    } catch { }
    Start-Sleep -Seconds 3
}
if (-not $healthy) { throw "gateway did not become healthy within 5 minutes" }
Write-Host "gateway healthy"

# --- 3. record the run machine --------------------------------------------
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$osInfo = Get-CimInstance Win32_OperatingSystem
$machine = [ordered]@{
    os       = "$($osInfo.Caption) $($osInfo.Version)"
    cpu      = "$($cpu.Name.Trim()) @ $($cpu.MaxClockSpeed) MHz"
    cores    = $cpu.NumberOfLogicalProcessors
    memory   = "$([math]::Round($osInfo.TotalVisibleMemorySize / 1MB, 1)) GB"
    collected = (Get-Date).ToUniversalTime().ToString("o")
}
[IO.File]::WriteAllText(
    (Join-Path $ResultsDir "machine.json"),
    ($machine | ConvertTo-Json),
    (New-Object System.Text.UTF8Encoding($false))
)
Write-Host "machine.json written"

# --- 4. run the k6 scripts (cwd = repo root so results/ resolves) ----------
# A failed threshold in one script should not abort the whole suite.
Push-Location $Repo
try {
    foreach ($s in $Scripts) {
        Write-Host "== k6 loadtest/$s.js =="
        if ($s -eq "overhead") {
            # The 'direct' scenario must reach the mock upstream, which is only
            # reachable inside the compose network (mock-upstream publishes no
            # host port), so run overhead with the k6 image on that network.
            & docker run --rm --network gatekeeper_default `
                -e BASE_URL=http://gateway:8080 `
                -e DIRECT_URL=http://mock-upstream:8081 `
                -v "${Repo}:/work" -w /work `
                grafana/k6:2.3.0 run loadtest/overhead.js
        } elseif ($s -eq "connections") {
            # On Windows, default ephemeral port range is ~16k ports (49152-65535).
            # Default to 2000 connections unless explicitly overridden to prevent WSAEADDRINUSE.
            $connCount = if ($env:CONNECTIONS) { $env:CONNECTIONS } else { "2000" }
            Write-Host "Running connections test with CONNECTIONS=$connCount (Windows ephemeral port safeguard)"
            & k6 run -e CONNECTIONS=$connCount "loadtest/$s.js"
        } else {
            & k6 run "loadtest/$s.js"
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "loadtest/$s.js exited with code $LASTEXITCODE (continuing)"
        }
    }
} finally {
    Pop-Location
}

# --- 5. report what we collected -------------------------------------------
Write-Host ""
Write-Host "== results =="
Get-ChildItem $ResultsDir | Format-Table Name, Length, LastWriteTime
Write-Host "Next: python scripts/generate_benchmarks.py"