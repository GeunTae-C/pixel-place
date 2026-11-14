# 인자/프롬프트를 command string에 보간하지 않는 제한 실행과 반복 예산 도우미
function Get-PhaseSeconds {
    param([string]$Name, [int]$Default)
    $value = Get-Variable -Name $Name -Scope Script -ErrorAction SilentlyContinue
    if ($null -eq $value) { return $Default }
    return [int]$value.Value
}
function Invoke-SupervisedProcess {
    param([string]$FilePath, [string[]]$Arguments, [string]$Directory, [string]$OutputPath,
        [string]$ErrorPath = "", [string]$InputPath = "", [bool]$Hook = $false,
        [int]$TimeoutMilliseconds = 900000, [string]$Phase = "gate")
    if ($TimeoutMilliseconds -le 0) { throw "TIME_BUDGET: $Phase 실행 시간 소진" }
    if ($null -eq ("PixelHarness.JobRun" -as [type])) {
        Add-Type -Path (Join-Path $PSScriptRoot "harness-job.cs") -ErrorAction Stop
    }
    $base = Split-Path -Parent $OutputPath
    [IO.Directory]::CreateDirectory($base) | Out-Null
    $id = [Guid]::NewGuid().ToString("N")
    $requestPath = Join-Path $base ("process-$id.request.json")
    $recordPath = Join-Path $base ("process-$id.result.json")
    $childErrorPath = Join-Path $base ("process-$id.child-error.json")
    $request = @{ child_error=$childErrorPath; file=$FilePath; arguments=@($Arguments); directory=$Directory; output=$OutputPath; error=$ErrorPath; input=$InputPath; hook=$Hook; separate_error=(-not [string]::IsNullOrWhiteSpace($ErrorPath)) }
    $request | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $requestPath -Encoding UTF8
    $childPath = Join-Path $PSScriptRoot "harness-process-child.ps1"
    $command = "& '" + $childPath.Replace("'", "''") + "' -RequestPath '" + $requestPath.Replace("'", "''") + "'; exit `$LASTEXITCODE"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $job = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $timedOut = $false
    $clean = $false
    $nativeExit = $null
    try {
        $job = [PixelHarness.JobRun]::new((Get-Process -Id $PID).Path, $encoded, $Directory)
        $nextNotice = 15000
        while (-not $job.Wait([Math]::Min(200, [Math]::Max(0, $TimeoutMilliseconds - [int]$watch.ElapsedMilliseconds)))) {
            if ($watch.ElapsedMilliseconds -ge $TimeoutMilliseconds) { $timedOut = $true; break }
            if ($watch.ElapsedMilliseconds -ge $nextNotice) {
                Write-Host "[harness] $Phase 실행 중 ($([int]($watch.Elapsed.TotalSeconds))s), 로그: $OutputPath"
                $nextNotice += 15000
            }
        }
        if ($watch.ElapsedMilliseconds -ge $TimeoutMilliseconds) { $timedOut = $true }
        if (-not $timedOut) { $nativeExit = $job.ExitCode }
        $clean = $job.StopAndConfirm(5000)
        if (-not $clean) { throw "프로세스 정리 확인 실패" }
        $result = [pscustomobject]@{ phase=$Phase; timed_out=$timedOut; native_exit_code=$nativeExit; cleanup_confirmed=$clean; elapsed_ms=$watch.ElapsedMilliseconds; limit_ms=$TimeoutMilliseconds; process_id=$job.ProcessId }
        $result | ConvertTo-Json | Set-Content -LiteralPath $recordPath -Encoding UTF8
        if ($timedOut) { throw "TIME_BUDGET: $Phase 시간 초과 (limit=$TimeoutMilliseconds ms)" }
        if (Test-Path -LiteralPath $childErrorPath) { throw "child 실행기 내부 오류: $childErrorPath" }
        return $nativeExit
    }
    finally {
        if ($null -ne $job) { $job.Dispose() }
        # 요청 인자에는 기존 feedback이 포함될 수 있어 실행 완료 후 중복 원문 제거
        if (Test-Path -LiteralPath $requestPath) { Remove-Item -LiteralPath $requestPath -Force }
    }
}
function Get-ReviewerFailureFingerprint {
    param($Review)
    $issues = @(@($Review.blockers) + @($Review.must_fix) | ForEach-Object { [regex]::Replace([string]$_, '\s+', ' ').Trim() } | Sort-Object -Unique)
    if ($issues.Count -eq 0) { $issues = @([regex]::Replace([string]$Review.summary, '\s+', ' ').Trim()) }
    return Get-EvidenceHash ([Text.Encoding]::UTF8.GetBytes(($issues -join "`n")))
}

# 실행 성공의 범위 명시: 검증 전용 성공을 구현 완료로 승격하지 않는 결과 계약
function Add-CompletionScope {
    param($Result, [ValidateSet("VALIDATE_ONLY", "IMPLEMENT")][string]$Mode)
    $completion = "NONE"
    if ($Result.status -ceq "PASS") {
        $completion = if ($Mode -ceq "VALIDATE_ONLY") { "VERIFIED" } else { "IMPLEMENTED" }
    }
    $Result | Add-Member -NotePropertyName mode -NotePropertyValue $Mode -Force
    $Result | Add-Member -NotePropertyName completion -NotePropertyValue $completion -Force
    $Result | Add-Member -NotePropertyName implementation_completed -NotePropertyValue ([bool]($completion -ceq "IMPLEMENTED")) -Force
}
function Assert-CompletionScope {
    param($Result, [ValidateSet("VALIDATE_ONLY", "IMPLEMENT")][string]$Mode)
    foreach ($name in @("mode", "completion", "implementation_completed", "attempt")) {
        if ($null -eq $Result.PSObject.Properties[$name]) { throw "결과 범위 필드 누락: $name" }
    }
    if ($Result.mode -isnot [string] -or $Result.mode -cne $Mode) { throw "결과 실행 모드 불일치" }
    if ($Result.implementation_completed -isnot [bool]) { throw "구현 완료 필드 형식 오류" }
    if (($Result.attempt -isnot [int] -and $Result.attempt -isnot [long]) -or $Result.attempt -lt 0) { throw "attempt 형식 오류" }
    if ($Mode -ceq "VALIDATE_ONLY" -and $Result.attempt -ne 0) { throw "검증 전용의 Writer attempt 오류" }
    if ($Mode -ceq "IMPLEMENT" -and $Result.status -ceq "PASS" -and $Result.attempt -lt 1) { throw "Writer 실행 없는 구현 완료" }
    $expected = "NONE"
    if ($Result.status -ceq "PASS") { $expected = if ($Mode -ceq "VALIDATE_ONLY") { "VERIFIED" } else { "IMPLEMENTED" } }
    if ($Result.completion -isnot [string] -or $Result.completion -cne $expected -or $Result.implementation_completed -ne ($expected -ceq "IMPLEMENTED")) {
        throw "결과 완료 범위 불일치"
    }
}

function Invoke-HarnessSupervisor {
    param([string]$WorkerPath, [string[]]$WorkerArguments, [string]$Directory, [string]$RunDirectory, [hashtable]$Limits, [ValidateSet("VALIDATE_ONLY", "IMPLEMENT")][string]$Mode = "IMPLEMENT")
    [IO.Directory]::CreateDirectory($RunDirectory) | Out-Null
    $Limits | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunDirectory "budget.json") -Encoding UTF8
    $log = Join-Path $RunDirectory "supervisor.log"
    $resultPath = Join-Path $RunDirectory "result.json"
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $result = $null
    $code = 30
    try {
        $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $WorkerPath, "-WorkerRunDirectory", $RunDirectory) + $WorkerArguments
        $code = Invoke-SupervisedProcess -FilePath (Get-Process -Id $PID).Path -Arguments $arguments -Directory $Directory -OutputPath $log -TimeoutMilliseconds ($Limits.total_seconds * 1000) -Phase "total"
        if ($code -notin @(0, 10, 20, 30)) { throw "worker 종료 계약 오류 (native exit=$code)" }
        $result = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $expected = @{ 0="PASS"; 10="FAILED"; 20="BLOCKED"; 30="HARNESS_ERROR" }
        if ($result.exit_code -ne $code -or $result.status -cne $expected[$code]) { throw "worker result/exit 불일치" }
        Assert-CompletionScope -Result $result -Mode $Mode
    }
    catch {
        $code = if ($_.Exception.Message.StartsWith("TIME_BUDGET:")) { 20 } else { 30 }
        $status = if ($code -eq 20) { "BLOCKED" } else { "HARNESS_ERROR" }
        # timeout 때 worker의 앞선 PASS 기록이 남아 있어도 최종 판정으로 재사용 금지
        $result = [pscustomobject]@{ status=$status; exit_code=$code; attempt=0; detail=$_.Exception.Message; run_directory=$RunDirectory; finished_at=[DateTimeOffset]::Now.ToString("o") }
        Add-CompletionScope -Result $result -Mode $Mode
    }
    $result | Add-Member -NotePropertyName limits -NotePropertyValue $Limits -Force
    $result | Add-Member -NotePropertyName supervisor_elapsed_ms -NotePropertyValue $watch.ElapsedMilliseconds -Force
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resultPath -Encoding UTF8
    # 강제 종료로 worker finally가 실행되지 않은 경우의 일회성 launch 요청 원문 정리
    Get-ChildItem -LiteralPath $RunDirectory -Recurse -File -Filter "process-*.request.json" | Remove-Item -Force
    if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 30 | ForEach-Object { Write-Host $_ } }
    Write-Host "[harness] FINAL STATUS: $($result.status) (exit=$code, mode=$($result.mode), completion=$($result.completion))"
    Write-Host "[harness] $($result.detail)"
    Write-Host "[harness] 증거: $RunDirectory"
    return $code
}
