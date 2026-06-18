# Behavioral eval suite. Requires imini + llama-server running. Heuristic smoke checks: posts each
# case to /ask in auto mode and looks for expected substrings in the answer. The deterministic
# guarantees live in src/test (mvn test); these check end-to-end model behavior.
param([string]$BaseUrl = "http://localhost:8080")

$casesPath = Join-Path $PSScriptRoot "..\evals\cases.json"
$cases = Get-Content $casesPath -Raw | ConvertFrom-Json
$fail = 0

foreach ($c in $cases) {
    $body = @{ question = $c.question; mode = "auto" } | ConvertTo-Json
    try {
        $resp = Invoke-RestMethod -Uri "$BaseUrl/ask" -Method Post -ContentType "application/json" -Body $body
        $ans = "$($resp.answer)"
    } catch {
        $ans = "REQUEST_ERROR: $_"
    }

    $ok = $true
    if ($c.expect_contains)     { foreach ($e in $c.expect_contains)     { if ($ans -notlike "*$e*") { $ok = $false } } }
    if ($c.expect_not_contains) { foreach ($e in $c.expect_not_contains) { if ($ans -like   "*$e*") { $ok = $false } } }

    if ($ok) {
        Write-Host "PASS  $($c.name)" -ForegroundColor Green
    } else {
        Write-Host "FAIL  $($c.name)" -ForegroundColor Red
        Write-Host "      answer: $ans"
        $fail++
    }
}

Write-Host ""
if ($fail -gt 0) { Write-Host "$fail eval(s) failed."; exit 1 } else { Write-Host "All evals passed."; exit 0 }
