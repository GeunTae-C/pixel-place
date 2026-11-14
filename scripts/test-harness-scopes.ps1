[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-process.ps1")
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("scope harness 검증 " + [guid]::NewGuid().ToString("N"))
$passed = 0
$complete = $false
function Assert-That { param([bool]$Condition,[string]$Message) if (-not $Condition) { throw $Message } }
function Pass { param([string]$Name) $script:passed++; Write-Host "[test-harness-scopes] PASS $Name" }
function New-FixtureResult {
    param([string]$Mode="IMPLEMENT",[string]$Status="PASS",[int]$ExitCode=0)
    $attempt = if ($Mode -eq "VALIDATE_ONLY") { 0 } else { 1 }
    return [pscustomobject]@{ status=$Status;exit_code=$ExitCode;attempt=$attempt;detail="fixture";mode=$Mode;completion="IMPLEMENTED";implementation_completed=$true }
}
try {
    [IO.Directory]::CreateDirectory($testRoot) | Out-Null
    foreach ($name in @("harness.ps1","harness-worker.ps1","harness-process.ps1","test-harness-scopes.ps1","test-harness-writer.ps1","test-harness-budgets.ps1")) {
        $tokens=$null;$errors=$null
        [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors) | Out-Null
        Assert-That (@($errors).Count -eq 0) "PowerShell 구문 오류: $name / $errors"
    }
    $cases = @(
        @{Mode="VALIDATE_ONLY";Status="PASS";Code=0;Completion="VERIFIED";Implemented=$false},
        @{Mode="VALIDATE_ONLY";Status="FAILED";Code=10;Completion="NONE";Implemented=$false},
        @{Mode="VALIDATE_ONLY";Status="BLOCKED";Code=20;Completion="NONE";Implemented=$false},
        @{Mode="VALIDATE_ONLY";Status="HARNESS_ERROR";Code=30;Completion="NONE";Implemented=$false},
        @{Mode="IMPLEMENT";Status="PASS";Code=0;Completion="IMPLEMENTED";Implemented=$true},
        @{Mode="IMPLEMENT";Status="FAILED";Code=10;Completion="NONE";Implemented=$false},
        @{Mode="IMPLEMENT";Status="BLOCKED";Code=20;Completion="NONE";Implemented=$false},
        @{Mode="IMPLEMENT";Status="HARNESS_ERROR";Code=30;Completion="NONE";Implemented=$false}
    )
    foreach ($case in $cases) {
        $result = New-FixtureResult -Mode $case.Mode -Status $case.Status -ExitCode $case.Code
        Add-CompletionScope -Result $result -Mode $case.Mode
        Assert-That ($result.completion -ceq $case.Completion -and $result.implementation_completed -eq $case.Implemented) "잘못된 성공 범위"
        $decoded = $result | ConvertTo-Json | ConvertFrom-Json
        Assert-CompletionScope -Result $decoded -Mode $case.Mode
        Pass "result/$($case.Mode)/$($case.Status)"
    }
    foreach ($name in @("missing-mode","wrong-mode","missing-completion","wrong-completion","flag-string","missing-flag","failure-promoted","impl-no-attempt","verify-with-attempt")) {
        $result = New-FixtureResult
        $mode = "IMPLEMENT"
        switch ($name) {
            "missing-mode" { $result.PSObject.Properties.Remove("mode") }
            "wrong-mode" { $result.mode = "VALIDATE_ONLY" }
            "missing-completion" { $result.PSObject.Properties.Remove("completion") }
            "wrong-completion" { $result.completion = "VERIFIED" }
            "flag-string" { $result.implementation_completed = "true" }
            "missing-flag" { $result.PSObject.Properties.Remove("implementation_completed") }
            "failure-promoted" { $result.status = "FAILED"; $result.exit_code=10 }
            "impl-no-attempt" { $result.attempt = 0 }
            "verify-with-attempt" { $mode="VALIDATE_ONLY"; $result.mode=$mode; $result.completion="VERIFIED"; $result.implementation_completed=$false }
        }
        $rejected = $false
        try { Assert-CompletionScope -Result $result -Mode $mode }
        catch { $rejected = $true }
        Assert-That $rejected "모순된 결과 허용: $name"
        Pass "reject/$name"
    }
    # worker 경계만 대체하고 production supervisor의 판정/최종 result는 실제 실행
    function Invoke-SupervisedProcess {
        param($FilePath,$Arguments,$Directory,$OutputPath,$TimeoutMilliseconds,$Phase)
        "fixture supervisor log" | Set-Content -LiteralPath $OutputPath -Encoding UTF8
        if ($script:scopeTimeout) { throw "TIME_BUDGET: total fixture" }
        $script:scopeResult | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path -Parent $OutputPath) "result.json") -Encoding UTF8
        return $script:scopeCode
    }
    foreach ($name in @("verify-pass","implementation-pass","promoted","missing-mode","failure-promoted","verify-timeout")) {
        $mode = if ($name -eq "implementation-pass" -or $name -eq "failure-promoted") { "IMPLEMENT" } else { "VALIDATE_ONLY" }
        $script:scopeResult = New-FixtureResult -Mode $mode
        if ($mode -eq "VALIDATE_ONLY") { $script:scopeResult.completion="VERIFIED"; $script:scopeResult.implementation_completed=$false }
        $script:scopeCode=0; $script:scopeTimeout=($name -eq "verify-timeout")
        $expected = 0
        switch ($name) {
            "promoted" { $script:scopeResult = New-FixtureResult; $expected=30 }
            "missing-mode" { $script:scopeResult.PSObject.Properties.Remove("mode"); $expected=30 }
            "failure-promoted" { $script:scopeResult.status="FAILED"; $script:scopeResult.exit_code=10; $script:scopeCode=10; $expected=30 }
            "verify-timeout" { $expected=20 }
        }
        $dir = Join-Path $testRoot $name
        $actual = Invoke-HarnessSupervisor -WorkerPath (Join-Path $testRoot "unused.ps1") -WorkerArguments @() -Directory $testRoot -RunDirectory $dir -Limits @{total_seconds=1} -Mode $mode
        Assert-That ($actual -eq $expected) "supervisor 결과 범위 차단 실패: $name"
        $result = Get-Content -LiteralPath (Join-Path $dir "result.json") -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-CompletionScope -Result $result -Mode $mode
        Assert-That ($result.exit_code -eq $expected) "supervisor exit/result 불일치"
        if ($expected -ne 0) { Assert-That ($result.completion -eq "NONE" -and -not $result.implementation_completed) "실패 후 완료 상태 잔존" }
        Pass "supervisor/$name"
    }
    Assert-That ($passed -eq 23) "7단계 결과 검증 수 불일치"
    $powerShellPath = (Get-Process -Id $PID).Path
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "test-harness-writer.ps1") -ScopeTests
    Assert-That ($LASTEXITCODE -eq 0) "검증 전용 흐름 3개 실패"
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "test-harness-budgets.ps1")
    Assert-That ($LASTEXITCODE -eq 0) "기존 182개 회귀 실패"
    $complete=$true
    Write-Host "[test-harness-scopes] PASS: 208 checks (scope 23 + validate-flow 3 + previous 182; 실제 Codex/API/DB 실행 없음)"
    exit 0
}
catch {
    Write-Host "[test-harness-scopes] FAIL: $($_.Exception.Message)"
    Write-Host "[test-harness-scopes] 증거: $testRoot"
    exit 1
}
finally { if ($complete) { Remove-Item -LiteralPath $testRoot -Recurse -Force } }
