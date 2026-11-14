[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$TaskFile,
    [ValidateRange(1, 20)]
    [int]$MaxAttempts = 6,
    [ValidateRange(1, 10)]
    [int]$SameFailureLimit = 3,
    [switch]$ValidateOnly,
    [string]$WorkerRunDirectory = "",
    [ValidateRange(1, 86400)][int]$WriterTimeoutSeconds = 1800,
    [ValidateRange(1, 86400)][int]$ReviewerTimeoutSeconds = 900,
    [ValidateRange(1, 86400)][int]$GateTimeoutSeconds = 900
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-failures.ps1")
. (Join-Path $PSScriptRoot "harness-evidence.ps1")
. (Join-Path $PSScriptRoot "harness-process.ps1")
. (Join-Path $PSScriptRoot "pixel-place-work.ps1")

function Write-HarnessMessage {
    param([string]$Message)
    Write-Host "[harness] $Message"
}

function Invoke-PowerShellScript {
    param(
        [string]$ScriptPath,
        [string[]]$Arguments,
        [string]$LogPath
    )

    $seconds = Get-PhaseSeconds "GateTimeoutSeconds" 900
    $nativeExit = Invoke-SupervisedProcess -FilePath $script:PowerShellPath -Arguments (@("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $ScriptPath) + $Arguments) -Directory (Get-Location).Path -OutputPath $LogPath -TimeoutMilliseconds ($seconds * 1000) -Phase "gate"
    if (Test-Path -LiteralPath $LogPath) { Get-Content -LiteralPath $LogPath -Tail 100 | ForEach-Object { Write-Host $_ } }
    if ($nativeExit -notin @(0, 10, 20, 30)) { Write-Host "[harness] gate 종료 계약 오류 (native exit=$nativeExit)" }
    return (ConvertTo-GateExit $nativeExit)
}

function Invoke-CodexProcess {
    param(
        [string[]]$Arguments,
        [string]$InputFile,
        [string]$EventsPath,
        [string]$ErrorPath,
        [bool]$EnableHarnessHook
    )

    $phase = if ($EnableHarnessHook) { "writer" } else { "reviewer" }
    $seconds = if ($EnableHarnessHook) { Get-PhaseSeconds "WriterTimeoutSeconds" 1800 } else { Get-PhaseSeconds "ReviewerTimeoutSeconds" 900 }
    return Invoke-SupervisedProcess -FilePath $script:CodexPath -Arguments $Arguments -Directory (Get-Location).Path -OutputPath $EventsPath -ErrorPath $ErrorPath -InputPath $InputFile -Hook $EnableHarnessHook -TimeoutMilliseconds ($seconds * 1000) -Phase $phase
}

function Assert-SafetyRuleDecision {
    param([string]$Json)
    $result = $Json | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $result -or $result -isnot [pscustomobject] -or
        $null -eq $result.PSObject.Properties["decision"] -or
        $result.decision -isnot [string] -or $result.decision -cne "forbidden") {
        throw "safety.rules가 git reset --hard를 forbidden으로 판정하지 않음"
    }
}

function New-HarnessIntegritySnapshot {
    $relativePaths = @(
        "scripts/harness.ps1",
        "scripts/harness-worker.ps1",
        "scripts/harness-process.ps1",
        "scripts/harness-process-child.ps1",
        "scripts/harness-job.cs",
        "scripts/harness-failures.ps1",
        "scripts/harness-evidence.ps1",
        "scripts/harness-local-secrets.ps1",
        "scripts/repo-hygiene.ps1",
        "scripts/verify-fast.ps1",
        "scripts/verify-full.ps1",
        "scripts/reviewer-output.schema.json",
        ".codex/hooks.json",
        ".codex/hooks/stop_gate.ps1",
        ".codex/rules/safety.rules"
    )
    $snapshot = @{}
    foreach ($relativePath in $relativePaths) {
        $fullPath = Join-Path $script:RepoRoot $relativePath
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "하네스 무결성 대상 누락: $relativePath"
        }
        $snapshot[$relativePath] = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
    }
    Write-Output -NoEnumerate $snapshot
}

function Test-HarnessIntegrity {
    $problems = New-Object System.Collections.Generic.List[string]
    foreach ($relativePath in $script:HarnessIntegrity.Keys) {
        $fullPath = Join-Path $script:RepoRoot $relativePath
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            $problems.Add("$relativePath 누락")
            continue
        }
        $actualHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
        if ($actualHash -ne $script:HarnessIntegrity[$relativePath]) {
            $problems.Add("$relativePath 변경됨")
        }
    }
    return @($problems)
}

# 실행 완료 증거만 판정, 구현의 정확성·완료 판정은 기존 gate/Reviewer 책임
function Get-WriterOutcome {
    param(
        [int]$ProcessExitCode,
        [string]$EventsPath,
        [string]$FinalPath,
        [string]$ExpectedThreadId = ""
    )

    $outcome = [pscustomobject]@{
        status = "HARNESS_ERROR"
        exit_code = 30
        codex_exit_code = $ProcessExitCode
        thread_id = $ExpectedThreadId
        completed_turns = 0
        detail = "Writer 완료 증거 미확인"
    }
    if ($ProcessExitCode -ne 0) {
        $outcome.status = "FAILED"
        $outcome.exit_code = 10
        $outcome.detail = "Writer 프로세스 실패 (codex exit=$ProcessExitCode)"
        return $outcome
    }

    try {
        if (-not (Test-Path -LiteralPath $EventsPath -PathType Leaf)) {
            throw "Writer JSONL 파일 누락"
        }
        $threadSeen = $false
        $turnOpen = $false
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $EventsPath) {
            $lineNumber++
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            if (-not $line.TrimStart().StartsWith("{")) {
                throw "Writer JSONL은 행마다 객체여야 함 (line=$lineNumber)"
            }
            try { $eventObject = $line | ConvertFrom-Json -ErrorAction Stop }
            catch { throw "Writer JSONL 문법 오류 (line=$lineNumber)" }
            if ($null -eq $eventObject -or $eventObject -isnot [pscustomobject]) {
                throw "Writer JSONL 객체 형식 오류 (line=$lineNumber)"
            }
            $typeProperty = $eventObject.PSObject.Properties["type"]
            if ($null -eq $typeProperty -or $typeProperty.Value -isnot [string] -or
                [string]::IsNullOrWhiteSpace($typeProperty.Value)) {
                throw "Writer JSONL type 누락/오류 (line=$lineNumber)"
            }
            switch -CaseSensitive ($typeProperty.Value) {
                "thread.started" {
                    $idProperty = $eventObject.PSObject.Properties["thread_id"]
                    if ($null -eq $idProperty -or $idProperty.Value -isnot [string] -or
                        [string]::IsNullOrWhiteSpace($idProperty.Value)) {
                        throw "Writer thread_id 누락/오류"
                    }
                    if ($threadSeen -or $turnOpen -or $outcome.completed_turns -gt 0) {
                        throw "Writer thread.started 순서/중복 오류"
                    }
                    if (-not [string]::IsNullOrWhiteSpace($ExpectedThreadId) -and
                        $idProperty.Value -cne $ExpectedThreadId) {
                        throw "resume thread_id가 요청한 세션과 다름"
                    }
                    $outcome.thread_id = $idProperty.Value
                    $threadSeen = $true
                }
                "turn.started" {
                    if ($turnOpen -or [string]::IsNullOrWhiteSpace($outcome.thread_id)) {
                        throw "Writer turn.started 순서/세션 오류"
                    }
                    $turnOpen = $true
                }
                "turn.completed" {
                    if (-not $turnOpen) { throw "Writer turn.completed 순서/중복 오류" }
                    $turnOpen = $false
                    $outcome.completed_turns++
                }
                { $_ -ceq "turn.failed" -or $_ -ceq "error" } {
                    # top-level 실패를 이후 completed나 외부 gate가 덮어쓰지 못하도록 차단
                    $outcome.status = "FAILED"
                    $outcome.exit_code = 10
                    $outcome.detail = "Writer 실패 이벤트: $($typeProperty.Value) (line=$lineNumber)"
                    return $outcome
                }
                default {
                    # item.* 내부 도구 실패는 Writer가 같은 turn에서 복구 가능
                }
            }
        }
        if ($turnOpen -or $outcome.completed_turns -eq 0 -or
            [string]::IsNullOrWhiteSpace($outcome.thread_id)) {
            throw "Writer turn 완료 증거 누락 또는 미완료 turn 존재"
        }
        if (-not (Test-Path -LiteralPath $FinalPath -PathType Leaf) -or
            [string]::IsNullOrWhiteSpace((Get-Content -LiteralPath $FinalPath -Raw -Encoding UTF8))) {
            throw "Writer 최종 응답 파일 누락/빈 내용"
        }
        $outcome.status = "COMPLETED"
        $outcome.exit_code = 0
        $outcome.detail = "Writer 정상 종료 및 turn 완료 확인"
    }
    catch {
        $outcome.detail = $_.Exception.Message
    }
    return $outcome
}

# 최초 실행과 resume의 공통 진입 gate, 실패 시 후속 검증·Reviewer 진입 금지
function Confirm-WriterCompletion {
    param(
        [int]$ProcessExitCode,
        [string]$EventsPath,
        [string]$FinalPath,
        [int]$Attempt,
        [string]$ExpectedThreadId = ""
    )

    $outcome = Get-WriterOutcome -ProcessExitCode $ProcessExitCode -EventsPath $EventsPath -FinalPath $FinalPath -ExpectedThreadId $ExpectedThreadId
    $statusPath = Join-Path (Split-Path -Parent $EventsPath) "writer-status.json"
    $outcome | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statusPath -Encoding UTF8
    if ($outcome.exit_code -ne 0) {
        Stop-Harness -Status $outcome.status -ExitCode $outcome.exit_code -Attempt $Attempt -Detail $outcome.detail
    }
    return $outcome.thread_id
}

function Get-FailureText {
    param([string]$AttemptDirectory)

    $sections = New-Object System.Collections.Generic.List[string]
    $logFiles = @(Get-ChildItem -LiteralPath $AttemptDirectory -File -Filter "*.log" | Where-Object { $_.Name -notmatch '^writer-' } | Sort-Object Name)
    foreach ($logFile in $logFiles) {
        $tail = @(Get-Content -LiteralPath $logFile.FullName -Tail 100)
        if ($tail.Count -gt 0) {
            $sections.Add("FILE: $($logFile.Name)")
            $sections.Add(($tail -join [Environment]::NewLine))
        }
    }

    $text = $sections -join ([Environment]::NewLine + [Environment]::NewLine)
    if ($text.Length -gt 12000) {
        $text = $text.Substring($text.Length - 12000)
    }
    return $text
}

function Get-FailureFingerprint {
    param([string]$FailureText)

    $signalLines = @(
        $FailureText -split '\r?\n' |
            Where-Object {
                $_ -match 'FAILED|FAILURE:|Exception|error:|AssertionFailed|Caused by:|What went wrong|Task .* failed|repo-hygiene|git diff|MySQL'
            }
    )
    $fingerprintSource = if ($signalLines.Count -gt 0) {
        $signalLines -join [Environment]::NewLine
    }
    else {
        $FailureText
    }
    $normalized = $fingerprintSource.Replace($script:RepoRoot, "<repo>")
    $normalized = [regex]::Replace($normalized, '\d{4}-\d{2}-\d{2}[T ][0-9:.+\-Z]+', '<timestamp>')
    $normalized = [regex]::Replace($normalized, ':\d+:', ':<line>:')
    $normalized = [regex]::Replace($normalized, '\s+', ' ').Trim()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "")
    }
    finally {
        $sha.Dispose()
    }
}

function Write-RunResult {
    param(
        [string]$Status,
        [int]$ExitCode,
        [int]$Attempt,
        [string]$Detail
    )

    $result = [pscustomobject][ordered]@{
        status = $Status
        exit_code = $ExitCode
        attempt = $Attempt
        detail = $Detail
        run_directory = $script:RunDirectory
        finished_at = [DateTimeOffset]::Now.ToString("o")
    }
    $mode = if ($ValidateOnly) { "VALIDATE_ONLY" } else { "IMPLEMENT" }
    Add-CompletionScope -Result $result -Mode $mode
    Assert-CompletionScope -Result $result -Mode $mode
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $script:RunDirectory "result.json") -Encoding UTF8
}

function Stop-Harness {
    param(
        [string]$Status,
        [int]$ExitCode,
        [int]$Attempt,
        [string]$Detail
    )

    Write-RunResult -Status $Status -ExitCode $ExitCode -Attempt $Attempt -Detail $Detail
    $completion = if ($Status -ne "PASS") { "NONE" } elseif ($ValidateOnly) { "VERIFIED" } else { "IMPLEMENTED" }
    Write-HarnessMessage "FINAL STATUS: $Status (completion=$completion)"
    Write-HarnessMessage $Detail
    Write-HarnessMessage "증거: $script:RunDirectory"
    exit $ExitCode
}

try {
    $repoRootOutput = & git rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Git worktree 안에서 실행해야 함"
    }
    # Git / 경로와 Resolve-Path의 Windows 경로를 같은 형식으로 비교하기 위한 정규화
    $script:RepoRoot = [IO.Path]::GetFullPath($repoRootOutput.Trim().Replace([IO.Path]::AltDirectorySeparatorChar, [IO.Path]::DirectorySeparatorChar))
    Set-Location $script:RepoRoot

    $script:PowerShellPath = (Get-Process -Id $PID).Path
    $runId = "{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), ([guid]::NewGuid().ToString("N").Substring(0, 8))
    $script:RunDirectory = if ([string]::IsNullOrWhiteSpace($WorkerRunDirectory)) { Get-PixelPlaceEvidencePath $runId } else { $WorkerRunDirectory }
    Assert-PixelPlaceEvidencePath $script:RunDirectory
    [System.IO.Directory]::CreateDirectory($script:RunDirectory) | Out-Null

    if (-not $ValidateOnly) {
        $codexCommand = Get-Command codex -ErrorAction SilentlyContinue
        if ($null -eq $codexCommand) {
            Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt 0 -Detail "Codex CLI를 PATH에서 찾을 수 없음"
        }
        $script:CodexPath = $codexCommand.Source
        if ([string]::IsNullOrWhiteSpace($script:CodexPath)) {
            $script:CodexPath = $codexCommand.Path
        }
        $rulesFile = Join-Path $script:RepoRoot ".codex/rules/safety.rules"
        $rulesCheckLog = Join-Path $script:RunDirectory "preflight-rules.log"
        $rulesCheckExit = Invoke-SupervisedProcess -FilePath $script:CodexPath -Arguments @("execpolicy", "check", "--pretty", "--rules", $rulesFile, "--", "git", "reset", "--hard") -Directory $script:RepoRoot -OutputPath $rulesCheckLog -ErrorPath (Join-Path $script:RunDirectory "preflight-rules.stderr.log") -TimeoutMilliseconds 30000 -Phase "rules"
        if ($rulesCheckExit -ne 0) {
            Stop-Harness -Status "HARNESS_ERROR" -ExitCode 30 -Attempt 0 -Detail "Codex execpolicy rules 검사 실패 (exit=$rulesCheckExit)"
        }
        Assert-SafetyRuleDecision -Json (Get-Content -LiteralPath $rulesCheckLog -Raw -Encoding UTF8)
    }

    if ($null -eq (Get-Command java -ErrorAction SilentlyContinue)) {
        Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt 0 -Detail "Java 21 실행 환경을 PATH에서 찾을 수 없음"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $script:RepoRoot "gradlew.bat") -PathType Leaf)) {
        Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt 0 -Detail "gradlew.bat가 없음"
    }

    $hygieneScript = Join-Path $script:RepoRoot "scripts/repo-hygiene.ps1"
    $hygieneExit = Invoke-PowerShellScript -ScriptPath $hygieneScript -Arguments @() -LogPath (Join-Path $script:RunDirectory "preflight-hygiene.log")
    if ($hygieneExit -ne 0) {
        $status = Get-GateStatus $hygieneExit
        Stop-Harness -Status $status -ExitCode $hygieneExit -Attempt 0 -Detail "repository hygiene preflight 실패"
    }

    $fullScript = Join-Path $script:RepoRoot "scripts/verify-full.ps1"
    $environmentExit = Invoke-PowerShellScript -ScriptPath $fullScript -Arguments @("-RunDirectory", $script:RunDirectory, "-EnvironmentOnly") -LogPath (Join-Path $script:RunDirectory "preflight-environment.log")
    if ($environmentExit -ne 0) {
        $status = Get-GateStatus $environmentExit
        Stop-Harness -Status $status -ExitCode $environmentExit -Attempt 0 -Detail "MySQL/JDK 환경 preflight 실패"
    }

    if ($ValidateOnly) {
        $validationDirectory = Join-Path $script:RunDirectory "validate-only"
        [System.IO.Directory]::CreateDirectory($validationDirectory) | Out-Null
        $validationExit = Invoke-PowerShellScript -ScriptPath $fullScript -Arguments @("-RunDirectory", $validationDirectory) -LogPath (Join-Path $validationDirectory "verification.log")
        if ($validationExit -eq 0) {
            Stop-Harness -Status "PASS" -ExitCode 0 -Attempt 0 -Detail "현재 코드 검증 통과 (VERIFIED). 명령문 구현 완료 판정은 수행하지 않음"
        }
        $status = if ($validationExit -eq 10) { "FAILED" } elseif ($validationExit -eq 20) { "BLOCKED" } else { "HARNESS_ERROR" }
        Stop-Harness -Status $status -ExitCode $validationExit -Attempt 0 -Detail "현재 코드의 전체 gate 실패"
    }

    if ([string]::IsNullOrWhiteSpace($TaskFile)) {
        Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt 0 -Detail "-TaskFile 경로가 필요함"
    }
    $resolvedTaskFile = (Resolve-Path -LiteralPath $TaskFile -ErrorAction Stop).Path
    $repoPrefix = $script:RepoRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTaskFile.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt 0 -Detail "TaskFile은 현재 repository 내부 파일이어야 함"
    }

    $diffBaselinePath = Join-Path $script:RunDirectory "preexisting-diff-check.log"
    $diffBaseline = @(Get-EvidenceWhitespaceLines -Root $script:RepoRoot)
    [IO.File]::WriteAllLines($diffBaselinePath, [string[]]$diffBaseline, (New-Object Text.UTF8Encoding($false)))
    $env:PIXEL_PLACE_DIFF_BASELINE = $diffBaselinePath

    $script:HarnessIntegrity = New-HarnessIntegritySnapshot
    $script:RunEvidence = New-RunEvidence -Root $script:RepoRoot -RunDirectory $script:RunDirectory -TaskPath $resolvedTaskFile
    Assert-RunEvidence $script:RunEvidence
    $taskText = Get-Content -LiteralPath $script:RunEvidence.task -Raw -Encoding UTF8
    $initialPromptPath = Join-Path $script:RunDirectory "writer-initial-prompt.md"
    $initialPrompt = @"
역할: pixel-place 구현 Writer.

AGENTS.md와 아래 상세 명령문을 먼저 읽고 현재 활성 정책을 판정한 뒤 작업하라.
작업 시작 전 현재 dirty worktree를 확인하고 기존 사용자 변경을 보존하라.
요청 범위만 구현하고 테스트를 약화하지 말라.
자동 commit, merge, branch/worktree 생성, 다음 단계 진입을 하지 말라.
하네스와 Stop gate를 비활성화하거나 우회하지 말라.
작업 기준 명령문은 고정본 '$($script:RunEvidence.task)'이다.
원본 명령문과 F:/pixel-place-work/evidence 및 F:/pixel-place-archive 아래 보존 증거를 수정하지 말라.
기존 상태 근거 '$($script:RunEvidence.manifest)'와 보존 원문을 확인하여 사용자 변경을 보존하라.
검증 실패는 root cause를 수정한 뒤 같은 검증을 다시 실행하라.

상세 명령문 시작
================
$taskText
================
상세 명령문 끝
"@
    [System.IO.File]::WriteAllText($initialPromptPath, $initialPrompt, (New-Object System.Text.UTF8Encoding($false)))

    $attempt = 1
    $attemptDirectory = Join-Path $script:RunDirectory ("attempt-{0:D2}" -f $attempt)
    [System.IO.Directory]::CreateDirectory($attemptDirectory) | Out-Null
    $writerEvents = Join-Path $attemptDirectory "writer-events.jsonl"
    $writerErrors = Join-Path $attemptDirectory "writer-stderr.log"
    $writerFinal = Join-Path $attemptDirectory "writer-final.md"

    Write-HarnessMessage "Writer attempt $attempt 시작"
    $writerArguments = @(
        "exec",
        "--json",
        "--sandbox",
        "workspace-write",
        "-c",
        "approval_policy=never",
        "-o",
        $writerFinal,
        "-"
    )
    $writerExit = Invoke-CodexProcess -Arguments $writerArguments -InputFile $initialPromptPath -EventsPath $writerEvents -ErrorPath $writerErrors -EnableHarnessHook $true

    $threadId = Confirm-WriterCompletion -ProcessExitCode $writerExit -EventsPath $writerEvents -FinalPath $writerFinal -Attempt $attempt

    $previousFingerprint = $null
    $sameFingerprintCount = 0
    $previousReviewFingerprint = $null
    $sameReviewCount = 0
    $reviewSchema = Join-Path $script:RepoRoot "scripts/reviewer-output.schema.json"

    while ($attempt -le $MaxAttempts) {
        Assert-RunEvidence $script:RunEvidence
        $integrityProblems = @(Test-HarnessIntegrity)
        if ($integrityProblems.Count -gt 0) {
            Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt $attempt -Detail ("Writer가 하네스 파일을 변경함: " + ($integrityProblems -join ", "))
        }

        $verificationLog = Join-Path $attemptDirectory "verification.log"
        Write-HarnessMessage "Attempt $attempt 전체 gate 실행"
        $verificationExit = Invoke-PowerShellScript -ScriptPath $fullScript -Arguments @("-RunDirectory", $attemptDirectory) -LogPath $verificationLog
        Assert-RunEvidence $script:RunEvidence

        if ($verificationExit -eq 20) {
            Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt $attempt -Detail "외부 환경 또는 사람 판단이 필요한 상태"
        }
        if ($verificationExit -ne 0 -and $verificationExit -ne 10) {
            Stop-Harness -Status "HARNESS_ERROR" -ExitCode 30 -Attempt $attempt -Detail "검증기 내부 오류 (exit=$verificationExit)"
        }

        $resumeFeedback = $null
        if ($verificationExit -eq 10) {
            $previousReviewFingerprint = $null
            $sameReviewCount = 0
            $failureText = Get-FailureText -AttemptDirectory $attemptDirectory
            $fingerprint = Get-FailureFingerprint -FailureText $failureText
            if ($fingerprint -eq $previousFingerprint) {
                $sameFingerprintCount++
            }
            else {
                $sameFingerprintCount = 1
                $previousFingerprint = $fingerprint
            }

            if ($sameFingerprintCount -ge $SameFailureLimit) {
                Stop-Harness -Status "FAILED" -ExitCode 10 -Attempt $attempt -Detail "동일 실패가 $sameFingerprintCount 회 반복되어 중단"
            }

            $resumeFeedback = @"
결정론적 검증이 실패했다.
아래 로그의 root cause를 수정하고 같은 검증을 다시 실행하라.
테스트, assertion, 품질 gate를 삭제하거나 완화하지 말라.
기존 사용자 변경과 범위 밖 파일을 되돌리거나 삭제하지 말라.

$failureText
"@
        }
        else {
            $script:ReviewEvidence = New-ChangeEvidence -Evidence $script:RunEvidence -AttemptDirectory $attemptDirectory -VerificationLog $verificationLog
            if ($script:ReviewEvidence.unreviewable.Count -gt 0) {
                Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt $attempt -Detail "원문 비교가 불가능한 변경 존재: review-evidence/changes.json의 unreviewable 확인"
            }
            Write-HarnessMessage "결정론적 gate PASS, 독립 read-only reviewer 시작"
            $reviewPromptPath = Join-Path $attemptDirectory "reviewer-prompt.md"
            $reviewResultPath = Join-Path $attemptDirectory "reviewer-result.json"
            $reviewEventsPath = Join-Path $attemptDirectory "reviewer-events.jsonl"
            $reviewErrorsPath = Join-Path $attemptDirectory "reviewer-stderr.log"
            $reviewPrompt = @"
역할: pixel-place 독립 최종 Reviewer.

read-only로만 검토하고 어떤 파일도 수정하지 말라.
AGENTS.md, docs/review-protocol.md와 아래 고정 명령문·기준선·변경 증거를 읽어라.
고정 명령문: '$($script:RunEvidence.task)'
시작 기준선: '$($script:RunEvidence.manifest)'
이번 검토의 변경 목록: '$($script:ReviewEvidence.manifest)'
이번 Full 검증 로그: '$verificationLog'
changes.json의 before/after 원문과 diff를 실제로 대조하라. ignored/untracked도 같은 기준으로 검토하라.
시작 working tree 대비 누적 변화만 이번 실행 중 변경이며, 시작부터 있던 dirty 변경은 기준선이다.
같은 파일의 기존 사용자 수정 보존 여부까지 확인하고 변경 행위자를 근거 없이 단정하지 말라.
명령문 원본을 다시 기준으로 삼지 말고 고정본의 완료 여부를 다중 패스로 검토하라.
증거가 없거나 읽을 수 없어 판정이 불가능하면 PASS 금지, BLOCKED로 보고하라.
응답 evidence에는 읽은 증거의 다음 SHA-256을 정확히 반환하라:
task_sha256=$($script:ReviewEvidence.task_sha256)
baseline_sha256=$($script:ReviewEvidence.baseline_sha256)
change_manifest_sha256=$($script:ReviewEvidence.change_manifest_sha256)
correctness, 활성 정책, durability/recovery/concurrency 불변식, regression, missing tests, 범위 밖 변경을 확인하라.
BLOCKER와 MUST FIX가 하나라도 있으면 PASS를 반환하지 말라.
출력은 제공된 JSON Schema만 따르라.
"@
            [System.IO.File]::WriteAllText($reviewPromptPath, $reviewPrompt, (New-Object System.Text.UTF8Encoding($false)))

            $reviewArguments = @(
                "exec",
                "--json",
                "--sandbox",
                "read-only",
                "-c",
                "approval_policy=never",
                "--output-schema",
                $reviewSchema,
                "-o",
                $reviewResultPath,
                "-"
            )
            $reviewExit = Invoke-CodexProcess -Arguments $reviewArguments -InputFile $reviewPromptPath -EventsPath $reviewEventsPath -ErrorPath $reviewErrorsPath -EnableHarnessHook $false
            if ($reviewExit -ne 0 -or -not (Test-Path -LiteralPath $reviewResultPath -PathType Leaf)) {
                Stop-Harness -Status "HARNESS_ERROR" -ExitCode 30 -Attempt $attempt -Detail "독립 reviewer 실행 실패 (exit=$reviewExit)"
            }

            try {
                $review = Get-Content -LiteralPath $reviewResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
                Assert-ReviewReceipt -Review $review -Evidence $script:ReviewEvidence
            }
            catch {
                Stop-Harness -Status "HARNESS_ERROR" -ExitCode 30 -Attempt $attempt -Detail "reviewer JSON 또는 evidence 계약 오류"
            }

            Assert-ReviewState -RunEvidence $script:RunEvidence -ReviewEvidence $script:ReviewEvidence -AttemptDirectory $attemptDirectory

            if ($review.verdict -eq "PASS" -and $review.blockers.Count -eq 0 -and $review.must_fix.Count -eq 0) {
                Stop-Harness -Status "PASS" -ExitCode 0 -Attempt $attempt -Detail "전체 gate와 독립 reviewer 통과"
            }
            if ($review.verdict -eq "BLOCKED") {
                Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt $attempt -Detail ([string]$review.summary)
            }

            $reviewFingerprint = Get-ReviewerFailureFingerprint $review
            if ($reviewFingerprint -eq $previousReviewFingerprint) { $sameReviewCount++ }
            else { $previousReviewFingerprint = $reviewFingerprint; $sameReviewCount = 1 }
            @{ fingerprint=$reviewFingerprint; consecutive=$sameReviewCount; limit=$SameFailureLimit } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $attemptDirectory "reviewer-repetition.json") -Encoding UTF8
            if ($sameReviewCount -ge $SameFailureLimit) {
                Stop-Harness -Status "FAILED" -ExitCode 10 -Attempt $attempt -Detail "동일 Reviewer 지적이 $sameReviewCount 회 반복되어 중단"
            }

            $reviewText = Get-Content -LiteralPath $reviewResultPath -Raw -Encoding UTF8
            if ($reviewText.Length -gt 12000) {
                $reviewText = $reviewText.Substring(0, 12000)
            }
            $resumeFeedback = @"
독립 read-only reviewer가 변경을 요청했다.
BLOCKER와 MUST FIX를 우선 해결하고 regression 검증을 다시 실행하라.
RECOMMENDED는 현재 범위에 필요한 경우에만 반영하라.
테스트나 gate를 약화하지 말라.

$reviewText
"@
            $previousFingerprint = $null
            $sameFingerprintCount = 0
        }

        if ($attempt -ge $MaxAttempts) {
            Stop-Harness -Status "FAILED" -ExitCode 10 -Attempt $attempt -Detail "최대 수정 attempt $MaxAttempts 회 소진"
        }

        $attempt++
        $attemptDirectory = Join-Path $script:RunDirectory ("attempt-{0:D2}" -f $attempt)
        [System.IO.Directory]::CreateDirectory($attemptDirectory) | Out-Null
        $writerEvents = Join-Path $attemptDirectory "writer-events.jsonl"
        $writerErrors = Join-Path $attemptDirectory "writer-stderr.log"
        $writerFinal = Join-Path $attemptDirectory "writer-final.md"

        Assert-RunEvidence $script:RunEvidence
        $resumeFeedback = "고정 명령문: '$($script:RunEvidence.task)'. 원본 명령문과 보존 증거 수정 금지.`n" + $resumeFeedback
        Write-HarnessMessage "Writer attempt $attempt resume"
        $writerArguments = @(
            "exec",
            "--json",
            "--sandbox",
            "workspace-write",
            "-c",
            "approval_policy=never",
            "-o",
            $writerFinal,
            "resume",
            $threadId,
            $resumeFeedback
        )
        $writerExit = Invoke-CodexProcess -Arguments $writerArguments -InputFile "" -EventsPath $writerEvents -ErrorPath $writerErrors -EnableHarnessHook $true

        $threadId = Confirm-WriterCompletion -ProcessExitCode $writerExit -EventsPath $writerEvents -FinalPath $writerFinal -Attempt $attempt -ExpectedThreadId $threadId
    }

    Stop-Harness -Status "FAILED" -ExitCode 10 -Attempt $attempt -Detail "retry loop 종료"
}
catch {
    if (-not (Get-Variable RunDirectory -Scope Script -ErrorAction SilentlyContinue)) {
        $script:RunDirectory = Get-PixelPlaceEvidencePath ("harness-error-"+[Guid]::NewGuid().ToString("N"))
        Assert-PixelPlaceEvidencePath $script:RunDirectory
    [System.IO.Directory]::CreateDirectory($script:RunDirectory) | Out-Null
    }
    if ($_.Exception.Message.StartsWith("EVIDENCE_CHANGED:") -or $_.Exception.Message.StartsWith("TIME_BUDGET:")) {
        Stop-Harness -Status "BLOCKED" -ExitCode 20 -Attempt 0 -Detail $_.Exception.Message
    }
    Stop-Harness -Status "HARNESS_ERROR" -ExitCode 30 -Attempt 0 -Detail $_.Exception.Message
}
