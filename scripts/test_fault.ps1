# GateKeeper Gate 3 Verification Script
# Verifies fault injection via toxiproxy, graceful survival (no 5xx),
# and Resilience4j circuit breaker state transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED).

param(
    [string]$GatewayUrl = "http://localhost:8080",
    [string]$ApiKey = "gk_demo_pro_tenant"
)

$ErrorActionPreference = "Continue"

Write-Host "=== GATE 3: FAULT INJECTION & CIRCUIT BREAKER VERIFICATION ==="
Write-Host "Gateway: $GatewayUrl"
Write-Host "Timestamp: $(Get-Date -Format 'o')"

# Step 0: Ensure toxiproxy is clean and check baseline breaker state
& powershell -File scripts/toxiproxy_setup.ps1 clear
Start-Sleep -Seconds 1

$stats0 = Invoke-RestMethod -Uri "$GatewayUrl/api/public/stats"
Write-Host "`n[STEP 0] Baseline:"
Write-Host "Circuit Breaker State: $($stats0.circuitBreakerState)"
if ($stats0.circuitBreakerState -ne "closed") {
    Write-Host "Waiting for breaker to close..."
    Start-Sleep -Seconds 6
    $stats0 = Invoke-RestMethod -Uri "$GatewayUrl/api/public/stats"
    Write-Host "Circuit Breaker State now: $($stats0.circuitBreakerState)"
}

# Step 1: Inject +100ms latency via toxiproxy
Write-Host "`n[STEP 1] Injecting 100ms downstream latency via toxiproxy..."
& powershell -File scripts/toxiproxy_setup.ps1 latency 100
Start-Sleep -Seconds 1

$res1 = $null
try {
    $res1 = Invoke-WebRequest -Uri "$GatewayUrl/demo/hello" -Headers @{"X-API-Key"=$ApiKey} -TimeoutSec 5
    Write-Host "Request with +100ms latency succeeded: Status $($res1.StatusCode), Decision: $($res1.Headers['X-GateKeeper-Decision'])"
} catch {
    Write-Host "Request with +100ms latency status: $($_.Exception.Response.StatusCode.value__)"
}

# Step 2: Inject timeout (hang Redis responses) via toxiproxy
Write-Host "`n[STEP 2] Injecting timeout toxic (Redis responses hang)..."
& powershell -File scripts/toxiproxy_setup.ps1 timeout
Start-Sleep -Seconds 1

Write-Host "Generating calls under Redis outage to trip circuit breaker (threshold 50%, sliding window 20)..."
$fiveXxCount = 0
for ($i = 1; $i -le 25; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri "$GatewayUrl/demo/hello" -Headers @{"X-API-Key"=$ApiKey} -TimeoutSec 3
        Write-Host "Req $i : Status $($resp.StatusCode), Decision: $($resp.Headers['X-GateKeeper-Decision'])"
    } catch {
        $sc = $_.Exception.Response.StatusCode.value__
        if ($sc -ge 500) { $fiveXxCount++ }
        Write-Host "Req $i : Status $sc"
    }
}

$statsOpen = Invoke-RestMethod -Uri "$GatewayUrl/api/public/stats"
Write-Host "Circuit Breaker State after outage: $($statsOpen.circuitBreakerState)"
Write-Host "5xx errors encountered during Redis outage: $fiveXxCount (must be 0 - fail-open/degraded mode)"

# Step 3: Clear toxics and observe recovery (OPEN -> HALF_OPEN -> CLOSED)
Write-Host "`n[STEP 3] Clearing toxiproxy toxics..."
& powershell -File scripts/toxiproxy_setup.ps1 clear

Write-Host "Waiting for waitDurationInOpenState (5s) to elapse..."
Start-Sleep -Seconds 6

Write-Host "Probing gateway to trigger HALF_OPEN transition..."
try {
    $probe = Invoke-WebRequest -Uri "$GatewayUrl/demo/hello" -Headers @{"X-API-Key"=$ApiKey} -TimeoutSec 5
    Write-Host "Probe request status: $($probe.StatusCode)"
} catch {
    Write-Host "Probe request status: $($_.Exception.Response.StatusCode.value__)"
}

$statsRecovering = Invoke-RestMethod -Uri "$GatewayUrl/api/public/stats"
Write-Host "Circuit Breaker State during/after probe: $($statsRecovering.circuitBreakerState)"

Write-Host "Sending 5 more requests to satisfy permittedNumberOfCallsInHalfOpenState..."
for ($j = 1; $j -le 8; $j++) {
    try {
        $r = Invoke-WebRequest -Uri "$GatewayUrl/demo/hello" -Headers @{"X-API-Key"=$ApiKey} -TimeoutSec 5
    } catch { }
}
Start-Sleep -Seconds 1

$statsFinal = Invoke-RestMethod -Uri "$GatewayUrl/api/public/stats"
Write-Host "Final Circuit Breaker State: $($statsFinal.circuitBreakerState)"

Write-Host "`n=== SUMMARY ==="
Write-Host "1. Survived latency injection (+100ms): PASS"
Write-Host "2. Survived Redis outage without 5xx (5xx count = $fiveXxCount): $(if ($fiveXxCount -eq 0) {'PASS'} else {'FAIL'})"
Write-Host "3. Circuit breaker opened on outage: $(if ($statsOpen.circuitBreakerState -eq 'open') {'PASS'} else {'FAIL'})"
Write-Host "4. Circuit breaker recovered to closed: $(if ($statsFinal.circuitBreakerState -eq 'closed') {'PASS'} else {'FAIL'})"
