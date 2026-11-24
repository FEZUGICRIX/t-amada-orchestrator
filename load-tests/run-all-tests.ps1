param(
    [switch]$IncludeSoak = $false,
    [string]$K6Path = "C:\Program Files\k6\k6.exe"
)

$ErrorActionPreference = "Continue"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "   T-Amada Saga Orchestrator - Automated Test Battery" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$results = @()

function Run-K6Test {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [hashtable]$EnvVars = @{}
    )

    Write-Host "`n>>> Running: $Name ($ScriptPath)..." -ForegroundColor Yellow

    $envArgs = @()
    foreach ($key in $EnvVars.Keys) {
        $envArgs += "-e"
        $envArgs += "$key=$($EnvVars[$key])"
    }

    $allArgs = @("run") + $envArgs + @($ScriptPath)

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $p = Start-Process -FilePath $K6Path -ArgumentList $allArgs -NoNewWindow -Wait -PassThru
    $stopwatch.Stop()

    $exitCode = $p.ExitCode
    $duration = [math]::Round($stopwatch.Elapsed.TotalSeconds, 1)

    $status = if ($exitCode -eq 0) { "PASSED" } else { "FAILED (ExitCode: $exitCode)" }
    $color = if ($exitCode -eq 0) { "Green" } else { "Red" }

    Write-Host "Result for $Name : $status in ${duration}s" -ForegroundColor $color

    # Query outbox backlog & active connections
    $backlog = "N/A"
    $activeConns = "N/A"
    try {
        $m = (Invoke-RestMethod -Uri "http://localhost:8080/actuator/metrics/orchestrator.outbox.pending.backlog" -Method Get -TimeoutSec 3).measurements[0].value
        $backlog = "$m"
    } catch {}

    try {
        $c = (Invoke-RestMethod -Uri "http://localhost:8080/actuator/metrics/hikaricp.connections.active" -Method Get -TimeoutSec 3).measurements[0].value
        $activeConns = "$c"
    } catch {}

    # Wait for outbox drain if needed
    if ($backlog -ne "N/A" -and [double]$backlog -gt 0) {
        Write-Host "  Draining outbox backlog ($backlog pending)..." -ForegroundColor DarkGray
        $drainWait = 0
        while ($drainWait -lt 40) {
            Start-Sleep -Seconds 2
            $drainWait += 2
            try {
                $cur = (Invoke-RestMethod -Uri "http://localhost:8080/actuator/metrics/orchestrator.outbox.pending.backlog" -Method Get -TimeoutSec 3).measurements[0].value
                if ($cur -eq 0) {
                    $backlog = "0 (drained)"
                    break
                }
                $backlog = "$cur"
            } catch { break }
        }
    }

    return [PSCustomObject]@{
        TestName        = $Name
        Status          = $status
        Duration        = "${duration}s"
        OutboxBacklog   = $backlog
        HikariActive    = $activeConns
    }
}

# 1. Concurrent Saga Lock Test (Check 1)
$results += Run-K6Test -Name "Check 1: Concurrent Saga Lock" -ScriptPath "load-tests/concurrent-saga-lock-test.js"

# 2. Fault Injection & Compensation Test
$results += Run-K6Test -Name "Fault Injection & Compensation" -ScriptPath "load-tests/fault-injection-test.js"

# 3. Idempotency Stress Test
$results += Run-K6Test -Name "Idempotency Parallel Stress" -ScriptPath "load-tests/idempotency-stress.js"

# 4. DB Stress Test (PostgreSQL)
$results += Run-K6Test -Name "DB Stress (PostgreSQL)" -ScriptPath "load-tests/db-stress.js"

# 5. 50 VU Load Test
$results += Run-K6Test -Name "50 VU Load Test" -ScriptPath "load-tests/create-orders.js" -EnvVars @{ TEST_MODE = "50vu" }

# 6. 100 VU Scalability Test
$results += Run-K6Test -Name "100 VU Scalability Test" -ScriptPath "load-tests/create-orders.js" -EnvVars @{ TEST_MODE = "100vu" }

# 7. 300 VU Performance Limit Test
$results += Run-K6Test -Name "300 VU Performance Limit Test" -ScriptPath "load-tests/create-orders.js" -EnvVars @{ TEST_MODE = "300vu" }

# 8. Optional Soak Test
if ($IncludeSoak) {
    $results += Run-K6Test -Name "10-min Soak Test" -ScriptPath "load-tests/create-orders.js" -EnvVars @{ TEST_MODE = "soak"; SOAK_DURATION = "10m" }
}

Write-Host "`n================================================================" -ForegroundColor Cyan
Write-Host "                   TEST BATTERY SUMMARY REPORT                  " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
$results | Format-Table -AutoSize
