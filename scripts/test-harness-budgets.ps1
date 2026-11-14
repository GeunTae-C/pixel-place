[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-process.ps1")
. (Join-Path $PSScriptRoot "harness-evidence.ps1")
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("budget harness 한글 ' 검증 " + [guid]::NewGuid().ToString("N"))
$passed = 0
$complete = $false
$outsider = $null
function Assert-That { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message } }
function Pass { param([string]$Name) $script:passed++; Write-Host "[test-harness-budgets] PASS $Name" }
function Put { param([string]$Path, [string]$Text) [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($true))) }
function Run-Fixture {
    param([string]$Name, [string]$Target, [string[]]$Arguments=@(), [int]$Milliseconds=15000, [string]$InputPath="", [switch]$Separate)
    $dir = Join-Path $testRoot $Name
    [IO.Directory]::CreateDirectory($dir) | Out-Null
    $errorLog = if ($Separate) { Join-Path $dir "error.log" } else { "" }
    return Invoke-SupervisedProcess -FilePath $Target -Arguments $Arguments -Directory $testRoot -OutputPath (Join-Path $dir "output.log") -ErrorPath $errorLog -InputPath $InputPath -Hook $true -TimeoutMilliseconds $Milliseconds -Phase $Name
}
function Read-Record {
    param([string]$Name)
    $records = @(Get-ChildItem -LiteralPath (Join-Path $testRoot $Name) -Filter "process-*.result.json")
    Assert-That ($records.Count -eq 1) "$Name 실행 기록 수 불일치"
    return Get-Content -LiteralPath $records[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
}
function Expect-Timeout {
    param([scriptblock]$Action)
    $caught = $false
    try { & $Action | Out-Null }
    catch { if (-not $_.Exception.Message.StartsWith("TIME_BUDGET:")) { throw }; $caught = $true }
    Assert-That $caught "timeout 차단 누락"
}
try {
    [IO.Directory]::CreateDirectory($testRoot) | Out-Null
    foreach ($name in @("harness.ps1", "harness-worker.ps1", "harness-process.ps1", "harness-process-child.ps1", "verify-fast.ps1", "verify-full.ps1", "test-harness-writer.ps1", "test-harness-failures.ps1", "test-harness-budgets.ps1")) {
        $tokens=$null; $errors=$null
        [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors) | Out-Null
        Assert-That (@($errors).Count -eq 0) "PowerShell 구문 오류: $name / $errors"
    }
    Pass "production-parser"
    $native = Join-Path $testRoot "fixture native.cmd"
    foreach ($code in @(0,7)) {
        [IO.File]::WriteAllText($native, "@echo off`r`necho native-output`r`necho native-warning 1>&2`r`nexit /b $code`r`n", [Text.Encoding]::ASCII)
        $actual = Run-Fixture -Name "native-$code" -Target $native
        Assert-That ($actual -eq $code) "native exit 보존 실패"
        $rec = Read-Record "native-$code"
        Assert-That ($rec.cleanup_confirmed -and -not $rec.timed_out) "정상 종료 정리 미확인"
        Assert-That ((Get-Content -LiteralPath (Join-Path $testRoot "native-$code/output.log") -Raw).Contains("native-warning")) "stderr 수집 누락"
        Pass "native-$code"
    }
    # cmd fixture JSON의 큰따옴표는 PowerShell 문자열이 아닌 단일 인용 텍스트로 생성
    [IO.File]::WriteAllText($native, ('@echo off' + "`r`n" + 'echo {"type":"turn.completed"}' + "`r`n" + 'echo fixture-warning 1>&2' + "`r`nexit /b 0`r`n"), [Text.Encoding]::ASCII)
    Assert-That ((Run-Fixture "separate" $native -Separate) -eq 0) "분리 출력 실행 실패"
    $event = Get-Content -LiteralPath (Join-Path $testRoot "separate/output.log") -Raw | ConvertFrom-Json
    Assert-That ($event.type -eq "turn.completed") "stderr가 JSONL에 혼입됨"
    Assert-That ((Get-Content -LiteralPath (Join-Path $testRoot "separate/error.log") -Raw).Contains("fixture-warning")) "별도 stderr 누락"
    Pass "separate-jsonl-stderr"

    $caught = $false
    try { Run-Fixture "missing-target" (Join-Path $testRoot "absent.exe") | Out-Null }
    catch { Assert-That ($_.Exception.Message.Contains("child 실행기 내부 오류")) "launch 오류를 native 실패로 오인"; $caught=$true }
    Assert-That $caught "누락된 실행 대상 허용"
    Pass "launch-error"

    # 직접 호출하는 PowerShell fixture에는 배열 splatting의 위치 순서대로 값만 전달
    $argScript = Join-Path $testRoot "인자 fixture.ps1"
    Put $argScript @'
param([string]$Tag)
@{ tag=$Tag; stdin=(@($input) -join "`n"); hook=$env:PIXEL_PLACE_HARNESS; supervised=$env:PIXEL_PLACE_SUPERVISED } | ConvertTo-Json -Compress
exit 0
'@
    $prompt = Join-Path $testRoot "prompt.md"; Put $prompt "한글 입력`n두 번째 줄"
    $tag = '공백 ''따옴표'' "문자" & $(literal)'
    Assert-That ((Run-Fixture "arguments" $argScript -Arguments @($tag) -InputPath $prompt) -eq 0) "인자 실행 실패"
    $argResult = Get-Content -LiteralPath (Join-Path $testRoot "arguments/output.log") -Raw -Encoding UTF8 | ConvertFrom-Json
    $expectedStdin = "한글 입력`n두 번째 줄"
    $tagMatches = $argResult.tag -ceq $tag
    $stdinMatches = $argResult.stdin.Trim() -ceq $expectedStdin
    $diagnostic = Join-Path $testRoot "arguments/assertion.json"
    if (-not ($tagMatches -and $stdinMatches)) {
        @{ expected_tag=$tag; actual_tag=$argResult.tag; expected_stdin=$expectedStdin; actual_stdin=$argResult.stdin; tag_matches=$tagMatches; stdin_matches=$stdinMatches } |
            ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $diagnostic -Encoding UTF8
    }
    Assert-That $tagMatches "인자 변형: $diagnostic"
    Assert-That $stdinMatches "stdin 변형: $diagnostic"
    Assert-That ($argResult.hook -eq "1" -and $argResult.supervised -eq "1") "child 환경 계약 누락"
    Pass "arguments-stdin-environment"

    $sleep = Join-Path $testRoot "sleep.ps1"; Put $sleep 'Start-Sleep -Seconds 60'
    Expect-Timeout { Run-Fixture "timeout" $sleep -Milliseconds 2000 }
    $rec = Read-Record "timeout"
    Assert-That ($rec.timed_out -and $rec.cleanup_confirmed -and $rec.elapsed_ms -lt 12000) "시간/정리 계약 오류"
    Pass "timeout-root"

    $spawn = Join-Path $testRoot "spawn.ps1"
    Put $spawn @'
param([string]$Marker, [int]$Seconds)
$escaped = $Marker.Replace("'", "''")
$code = "[IO.File]::WriteAllText('$escaped', [string]`$PID); Start-Sleep -Seconds 60"
$info = New-Object Diagnostics.ProcessStartInfo
$info.FileName = (Get-Process -Id $PID).Path
$info.Arguments = "-NoProfile -EncodedCommand " + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
$info.UseShellExecute = $false
$child = [Diagnostics.Process]::Start($info)
$wait = [Diagnostics.Stopwatch]::StartNew()
while (-not (Test-Path -LiteralPath $Marker)) {
    if ($wait.ElapsedMilliseconds -gt 4000) { throw "grandchild start timeout" }
    Start-Sleep -Milliseconds 25
}
Start-Sleep -Seconds $Seconds
exit 0
'@
    foreach ($seconds in @(0,60)) {
        $name = "descendant-$seconds"; $marker = Join-Path $testRoot "$name.pid"
        if ($seconds -eq 0) { Assert-That ((Run-Fixture $name $spawn -Arguments @($marker, [string]$seconds)) -eq 0) "정상 자손 fixture 실패" }
        else { Expect-Timeout { Run-Fixture $name $spawn -Arguments @($marker, [string]$seconds) -Milliseconds 8000 } }
        Assert-That (Test-Path -LiteralPath $marker) "자손이 실행되지 않은 fixture"
        $descendantId = [int](Get-Content -LiteralPath $marker -Raw)
        Assert-That ($null -eq (Get-Process -Id $descendantId -ErrorAction SilentlyContinue)) "자손 프로세스 생존"
        Assert-That ((Read-Record $name).cleanup_confirmed) "자손 정리 확인 누락"
        Pass $name
    }
    $outsideInfo = New-Object Diagnostics.ProcessStartInfo
    $outsideInfo.FileName = (Get-Process -Id $PID).Path
    $outsideInfo.Arguments = '-NoProfile -Command "Start-Sleep -Seconds 60"'
    $outsideInfo.UseShellExecute = $false
    $outsider = [Diagnostics.Process]::Start($outsideInfo)
    Expect-Timeout { Run-Fixture "unrelated" $sleep -Milliseconds 1500 }
    Assert-That (-not $outsider.HasExited) "무관한 프로세스 종료"
    $outsider.Kill(); $outsider.WaitForExit(); $outsider.Dispose(); $outsider=$null
    Pass "unrelated-process-survives"

    $nested = Join-Path $testRoot "nested.ps1"
    Put $nested @'
param([string]$Helper, [string]$Target, [string]$Log)
. $Helper
Invoke-SupervisedProcess -FilePath $Target -Arguments @() -Directory (Get-Location).Path -OutputPath $Log -TimeoutMilliseconds 60000 -Phase "inner" | Out-Null
'@
    Expect-Timeout { Run-Fixture "nested" $nested -Arguments @((Join-Path $PSScriptRoot "harness-process.ps1"), $sleep, (Join-Path $testRoot "inner/output.log")) -Milliseconds 8000 }
    Assert-That (Test-Path -LiteralPath (Join-Path $testRoot "inner/output.log")) "중첩 job이 실제 시작되지 않음"
    Assert-That ((Read-Record "nested").cleanup_confirmed) "중첩 job 정리 실패"
    Pass "nested-total-timeout"

    $fakeWorker = Join-Path $testRoot "worker.ps1"
    Put $fakeWorker @'
param([string]$WorkerRunDirectory, [string]$Mode)
if ($Mode -ne "missing") {
    $code = if ($Mode -eq "mismatch") { 10 } else { 0 }
    @{status="PASS";exit_code=$code;attempt=1;detail="fixture";mode="IMPLEMENT";completion="IMPLEMENTED";implementation_completed=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $WorkerRunDirectory "result.json") -Encoding UTF8
}
if ($Mode -eq "timeout") { Start-Sleep -Seconds 60 }
if ($Mode -eq "unknown") { exit 7 }
exit 0
'@
    foreach ($mode in @("pass", "timeout", "missing", "unknown", "mismatch")) {
        $dir = Join-Path $testRoot "supervisor-$mode"
        $limits = @{total_seconds=$(if ($mode -eq "timeout") { 4 } else { 15 });writer_seconds=9;reviewer_seconds=8;gate_seconds=7;max_attempts=6;same_failure_limit=3}
        $actual = Invoke-HarnessSupervisor -WorkerPath $fakeWorker -WorkerArguments @("-Mode", $mode) -Directory $testRoot -RunDirectory $dir -Limits $limits
        $expected = if ($mode -eq "pass") { 0 } elseif ($mode -eq "timeout") { 20 } else { 30 }
        Assert-That ($actual -eq $expected) "supervisor $mode 종료 오류"
        $result = Get-Content -LiteralPath (Join-Path $dir "result.json") -Raw | ConvertFrom-Json
        Assert-That ($result.exit_code -eq $expected -and $result.limits.gate_seconds -eq 7) "supervisor 결과/예산 기록 오류"
        if ($mode -eq "timeout") { Assert-That ($result.status -eq "BLOCKED") "기존 PASS가 timeout을 덮음" }
        Assert-That (@(Get-ChildItem -LiteralPath $dir -Recurse -Filter "process-*.request.json").Count -eq 0) "launch 요청 원문 미정리"
        Pass "supervisor-$mode"
    }
    $a = [pscustomobject]@{blockers=@("first  issue");must_fix=@("second");summary="one"}
    $b = [pscustomobject]@{blockers=@("second");must_fix=@("first issue");summary="other"}
    Assert-That ((Get-ReviewerFailureFingerprint $a) -eq (Get-ReviewerFailureFingerprint $b)) "동일 지적 순서/공백 정규화 실패"
    Pass "reviewer-normalized-fingerprint"
    $b.must_fix = @("changed")
    Assert-That ((Get-ReviewerFailureFingerprint $a) -ne (Get-ReviewerFailureFingerprint $b)) "다른 지적을 같은 반복으로 오인"
    Pass "reviewer-different-fingerprint"
    Assert-That ($passed -eq 18) "6단계 process 검증 수 불일치: $passed"
    $powerShellPath = (Get-Process -Id $PID).Path
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "test-harness-writer.ps1") -BudgetTests
    Assert-That ($LASTEXITCODE -eq 0) "시간/반복 흐름 7개 검증 실패"
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "test-harness-failures.ps1")
    Assert-That ($LASTEXITCODE -eq 0) "기존 157개 회귀 실패"
    $complete = $true
    Write-Host "[test-harness-budgets] PASS: 182 checks (process 18 + budget-flow 7 + previous 157; 실제 Codex/API/DB 실행 없음)"
    exit 0
}
catch {
    Write-Host "[test-harness-budgets] FAIL: $($_.Exception.Message)"
    Write-Host "[test-harness-budgets] 증거: $testRoot"
    exit 1
}
finally {
    if ($null -ne $outsider) { if (-not $outsider.HasExited) { $outsider.Kill(); $outsider.WaitForExit() }; $outsider.Dispose() }
    if ($complete) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
