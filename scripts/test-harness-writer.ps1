[CmdletBinding()]
param(
    [switch]$FixtureChild,
    [switch]$EvidenceTests,
    [switch]$BudgetTests,
    [switch]$ScopeTests,
    [switch]$RulesTests,
    [switch]$PathTests,
    [string]$FixtureRoot,
    [string]$Scenario
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-failures.ps1")
. (Join-Path $PSScriptRoot "harness-evidence.ps1")
. (Join-Path $PSScriptRoot "harness-process.ps1")

# 실제 Codex/API/DB 호출 없이 production parser와 supervisor 실패 차단 검증
$target = Join-Path $PSScriptRoot "harness-worker.ps1"
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($target, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -ne 0) { throw "harness-worker.ps1 구문 오류" }
foreach ($statement in $ast.EndBlock.Statements) {
    if ($statement -is [System.Management.Automation.Language.FunctionDefinitionAst]) {
        . ([scriptblock]::Create($statement.Extent.Text))
    }
}

$fixtureThread = "0199a213-81c0-7800-8aa1-bbab2a035a53"
$threadLine = '{"type":"thread.started","thread_id":"' + $fixtureThread + '"}'
$startedLine = '{"type":"turn.started"}'
$completedLine = '{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1}}'
$normalLines = @($threadLine, $startedLine, $completedLine)

if ($FixtureChild) {
    # 모든 외부 실행 경계를 임시 fixture로 대체, actual main 및 Stop-Harness는 그대로 사용
    $script:fixtureRoot = $FixtureRoot
    $script:fixtureScenario = $Scenario
    $script:writerCalls = 0
    $script:gateCalls = 0
    $script:reviewerCalls = 0
    $script:tracePath = Join-Path $FixtureRoot "build/trace.log"

    # Git native stderr 및 실제 공백 검사는 test-harness-git.ps1의 임시 실제 저장소에서 검증
    function Get-EvidenceWhitespaceLines { param([string]$Root) return @() }
    function Get-EvidenceGitState {
        param([string]$Root)
        $index = if ($script:fixtureScenario -eq "evidence-index" -and $script:writerCalls -gt 0) { "changed" } else { "fixture-index" }
        return [pscustomobject]@{ head = "fixture-head"; branch = "fixture"; index_sha256 = $index; tracked = @{}; untracked = @{} }
    }
    function git {
        $global:LASTEXITCODE = 0
        # Git for Windows는 / 경로를 반환하므로 실제 경계 형식으로 fixture 구성
        if ($args[0] -eq "rev-parse") { return $script:fixtureRoot.Replace('\', '/') }
        if ($args[0] -eq "diff") { return }
        throw "fixture가 허용하지 않은 git 호출"
    }
    function Get-Command {
        param([string]$Name, [string]$ErrorAction)
        if ($Name -eq "codex" -and $script:fixtureScenario -like "scope-validate-*") { throw "검증 전용에서 Codex 조회" }
        if ($Name -notin @("codex", "java")) { throw "fixture가 허용하지 않은 도구 조회" }
        return [pscustomobject]@{ Source = (Join-Path $script:fixtureRoot "fake-codex.cmd"); Path = "unused" }
    }
    function Invoke-SupervisedProcess {
        param($FilePath, $Arguments, $Directory, $OutputPath, $ErrorPath, $TimeoutMilliseconds, $Phase)
        if ($Phase -ne "rules") { throw "fixture native boundary bypass" }
        $rulesJson = switch ($script:fixtureScenario) {
            "rules-allow" { '{"decision":"allow"}' }
            "rules-malformed" { '{bad' }
            "rules-missing" { '{}' }
            default { '{"decision":"forbidden"}' }
        }
        $rulesJson | Set-Content -LiteralPath $OutputPath -Encoding UTF8
        return 0
    }
    function Invoke-PowerShellScript {
        param([string]$ScriptPath, [string[]]$Arguments, [string]$LogPath)
        "fixture verification log" | Set-Content -LiteralPath $LogPath -Encoding UTF8
        if ([System.IO.Path]::GetFileName($ScriptPath) -ne "verify-full.ps1" -or
            $Arguments -contains "-EnvironmentOnly") { return 0 }
        $script:gateCalls++
        if ($script:fixtureScenario -eq "evidence-gate-task") {
            "changed task" | Set-Content -LiteralPath (Join-Path $script:fixtureRoot "task.md") -Encoding UTF8
        }
        "gate" | Add-Content -LiteralPath $script:tracePath
        if ($script:fixtureScenario -eq "scope-validate-fail") { return 10 }
        if ($script:fixtureScenario -eq "scope-validate-blocked") { return 20 }
        if ($script:fixtureScenario -eq "budget-gate-timeout") { throw "TIME_BUDGET: gate fixture" }
        if ($script:fixtureScenario -eq "budget-gate-repeat") {
            "FAILED same test" | Set-Content -LiteralPath $LogPath -Encoding UTF8
            return 10
        }
        if ($script:fixtureScenario -like "resume-*" -and $script:gateCalls -eq 1) {
            "FAILED fixture test" | Set-Content -LiteralPath $LogPath -Encoding UTF8
            return 10
        }
        return 0
    }
    function Invoke-CodexProcess {
        param([string[]]$Arguments, [string]$InputFile, [string]$EventsPath, [string]$ErrorPath, [bool]$EnableHarnessHook)
        # 실제 CLI에서 거부된 옵션의 재도입 및 역할별 sandbox 변경을 차단한다.
        if ($Arguments -contains "--ask-for-approval") { throw "unsupported exec approval flag" }
        $configIndex = [Array]::IndexOf($Arguments, "-c")
        if ($configIndex -lt 0 -or $Arguments[$configIndex + 1] -cne "approval_policy=never") {
            throw "exec approval configuration missing"
        }
        $sandboxIndex = [Array]::IndexOf($Arguments, "--sandbox")
        $expectedSandbox = if ($EnableHarnessHook) { "workspace-write" } else { "read-only" }
        if ($sandboxIndex -lt 0 -or $Arguments[$sandboxIndex + 1] -cne $expectedSandbox) {
            throw "exec sandbox contract changed"
        }
        $outputIndex = [Array]::IndexOf($Arguments, "-o")
        $outputPath = $Arguments[$outputIndex + 1]
        if (-not $EnableHarnessHook) {
            $script:reviewerCalls++
            "reviewer" | Add-Content -LiteralPath $script:tracePath
            $verdict = if ($script:fixtureScenario -eq "reviewer-resume-nonzero" -and $script:reviewerCalls -eq 1) { "CHANGES_REQUESTED" } else { "PASS" }
            if ($script:fixtureScenario -eq "budget-reviewer-timeout") { throw "TIME_BUDGET: reviewer fixture" }
            $issues = @()
            if ($script:fixtureScenario -in @("budget-reviewer-repeat", "budget-max-attempts", "budget-reviewer-change")) {
                $verdict = "CHANGES_REQUESTED"
                $issues = @("same issue")
                if ($script:fixtureScenario -eq "budget-max-attempts") { $issues = @("issue-$script:reviewerCalls") }
                if ($script:fixtureScenario -eq "budget-reviewer-change") {
                    $issues = @("issue-" + [Math]::Floor(($script:reviewerCalls - 1) / 2))
                    if ($script:reviewerCalls -eq 5) { $verdict = "PASS"; $issues = @() }
                }
            }
            $receipt = @{ task_sha256 = $script:ReviewEvidence.task_sha256; baseline_sha256 = $script:ReviewEvidence.baseline_sha256; change_manifest_sha256 = $script:ReviewEvidence.change_manifest_sha256 }
            if ($script:fixtureScenario -eq "evidence-wrong-receipt") { $receipt.task_sha256 = "wrong" }
            if ($script:fixtureScenario -eq "evidence-missing-receipt") { $receipt = $null }
            if ($script:fixtureScenario -eq "evidence-review-edit") { "changed during review" | Set-Content -LiteralPath (Join-Path $script:fixtureRoot "subject.md") -Encoding UTF8 }
            if ($script:fixtureScenario -eq "evidence-review-log") { "changed" | Set-Content -LiteralPath (Join-Path (Split-Path -Parent (Split-Path -Parent $script:ReviewEvidence.manifest)) "verification.log") -Encoding UTF8 }
            $prompt = Get-Content -LiteralPath $InputFile -Raw -Encoding UTF8
            foreach ($path in @($script:RunEvidence.task, $script:RunEvidence.manifest, $script:ReviewEvidence.manifest)) {
                if (-not $prompt.Contains($path)) { throw "Reviewer prompt의 증거 경로 누락" }
            }
            @{ verdict = $verdict; blockers = @(); must_fix = $issues; summary = "fixture"; evidence = $receipt } |
                ConvertTo-Json | Set-Content -LiteralPath $outputPath -Encoding UTF8
            return 0
        }
        $script:writerCalls++
        "writer" | Add-Content -LiteralPath $script:tracePath
        if ($script:fixtureScenario -eq "budget-writer-timeout") { throw "TIME_BUDGET: writer fixture" }
        if ($script:fixtureScenario -eq "initial-throw") { throw "fixture native launch error" }
        if ($script:writerCalls -eq 1) {
            switch ($script:fixtureScenario) {
                "evidence-task" { "changed" | Set-Content -LiteralPath (Join-Path $script:fixtureRoot "task.md") -Encoding UTF8 }
                "evidence-frozen" { "changed" | Set-Content -LiteralPath $script:RunEvidence.task -Encoding UTF8 }
                "evidence-baseline" { "changed" | Set-Content -LiteralPath $script:RunEvidence.manifest -Encoding UTF8 }
                "evidence-copy" { $copy = @($script:RunEvidence.snapshot.files.Values | Where-Object { $_.content -ne "" })[0].content; "changed" | Set-Content -LiteralPath $copy -Encoding UTF8 }
                "evidence-unreviewable" { [IO.File]::WriteAllBytes((Join-Path $script:fixtureRoot "binary.dat"), [byte[]]@(0, 1, 2)) }
                "evidence-change" { "new source" | Set-Content -LiteralPath (Join-Path $script:fixtureRoot "subject.md") -Encoding UTF8 }
            }
        }
        $lines = $normalLines
        $code = 0
        if ($script:writerCalls -eq 1) {
            switch ($script:fixtureScenario) {
                "initial-nonzero" { $code = 7 }
                "initial-failed-event" { $lines = @($threadLine, $startedLine, '{"type":"turn.failed"}') }
                "initial-malformed" { $lines = @("not-json") }
                "initial-incomplete" { $lines = @($threadLine, $startedLine) }
            }
        }
        else {
            # 실제 resume 호출이 최초 세션 ID를 계속 사용하는지 확인
            $resumeIndex = [Array]::IndexOf($Arguments, "resume")
            if ($resumeIndex -lt 0 -or $Arguments[$resumeIndex + 1] -cne $fixtureThread) {
                throw "fixture resume 인자의 세션 ID 불일치"
            }
            switch ($script:fixtureScenario) {
                { $_ -in @("resume-nonzero", "reviewer-resume-nonzero") } { $code = 7 }
                "resume-failed-event" { $lines = @($threadLine, $startedLine, '{"type":"turn.failed"}') }
                "resume-mismatch" { $lines = @('{"type":"thread.started","thread_id":"other"}', $startedLine, $completedLine) }
                "resume-no-thread" { $lines = @($startedLine, $completedLine) }
                "resume-incomplete" { $lines = @($threadLine, $startedLine) }
            }
        }
        $lines | Set-Content -LiteralPath $EventsPath -Encoding UTF8
        "fixture final response" | Set-Content -LiteralPath $outputPath -Encoding UTF8
        "fixture stderr" | Set-Content -LiteralPath $ErrorPath -Encoding UTF8
        return $code
    }

    $TaskFile = Join-Path $FixtureRoot "task.md"
    if ($Scenario -eq "path-sibling") {
        $sibling = $FixtureRoot + "-other"
        [IO.Directory]::CreateDirectory($sibling) | Out-Null
        $TaskFile = Join-Path $sibling "task.md"
        [IO.File]::WriteAllText($TaskFile, "outside fixture")
    }
    if ($Scenario -eq "path-parent") {
        $TaskFile = Join-Path $FixtureRoot "../outside-task.md"
        [IO.File]::WriteAllText([IO.Path]::GetFullPath($TaskFile), "outside fixture")
    }
    $MaxAttempts = if ($Scenario -like "budget-*") { 5 } else { 2 }
    $WorkerRunDirectory = ""
    $SameFailureLimit = 3
    $ValidateOnly = ($Scenario -eq "validate-only" -or $Scenario -like "scope-validate-*")
    $main = @($ast.EndBlock.Statements | Where-Object { $_ -is [System.Management.Automation.Language.TryStatementAst] })
    if ($main.Count -ne 1) { throw "harness main 식별 실패" }
    . ([scriptblock]::Create($main[0].Extent.Text))
    throw "Stop-Harness로 종료되지 않은 fixture"
}

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("writer harness 검증 " + [Guid]::NewGuid().ToString("N"))
$passed = 0
try {
    [System.IO.Directory]::CreateDirectory($testRoot) | Out-Null
    $cases = @(
        @{ Name = "normal"; Lines = $normalLines; Exit = 0; Expected = 0 },
        @{ Name = "nonzero-with-completed"; Lines = $normalLines; Exit = 7; Expected = 10 },
        @{ Name = "nonzero-without-json"; Lines = @(); Exit = 7; Expected = 10 },
        @{ Name = "failed-event"; Lines = @($threadLine, $startedLine, '{"type":"turn.failed"}'); Exit = 0; Expected = 10 },
        @{ Name = "error-then-completed"; Lines = @($threadLine, $startedLine, '{"type":"error","message":"fixture"}', $completedLine); Exit = 0; Expected = 10 },
        @{ Name = "error-after-completed"; Lines = @($normalLines) + @('{"type":"error"}'); Exit = 0; Expected = 10 },
        @{ Name = "missing-json"; Lines = @(); Exit = 0; Expected = 30 },
        @{ Name = "empty-json"; Lines = @(" "); Exit = 0; Expected = 30 },
        @{ Name = "malformed"; Lines = @($normalLines) + @("not-json"); Exit = 0; Expected = 30 },
        @{ Name = "array-line"; Lines = @(('[' + $threadLine + ']'), $startedLine, $completedLine); Exit = 0; Expected = 30 },
        @{ Name = "null-line"; Lines = @("null"); Exit = 0; Expected = 30 },
        @{ Name = "missing-type"; Lines = @('{}'); Exit = 0; Expected = 30 },
        @{ Name = "invalid-type"; Lines = @('{"type":123}'); Exit = 0; Expected = 30 },
        @{ Name = "missing-id"; Lines = @('{"type":"thread.started"}', $startedLine, $completedLine); Exit = 0; Expected = 30 },
        @{ Name = "invalid-id"; Lines = @('{"type":"thread.started","thread_id":123}', $startedLine, $completedLine); Exit = 0; Expected = 30 },
        @{ Name = "missing-start"; Lines = @($threadLine, $completedLine); Exit = 0; Expected = 30 },
        @{ Name = "missing-completed"; Lines = @($threadLine, $startedLine); Exit = 0; Expected = 30 },
        @{ Name = "duplicate-completed"; Lines = @($normalLines) + @($completedLine); Exit = 0; Expected = 30 },
        @{ Name = "duplicate-start"; Lines = @($threadLine, $startedLine, $startedLine, $completedLine); Exit = 0; Expected = 30 },
        @{ Name = "duplicate-thread"; Lines = @($threadLine, $threadLine, $startedLine, $completedLine); Exit = 0; Expected = 30 },
        @{ Name = "trailing-open-turn"; Lines = @($normalLines) + @($startedLine); Exit = 0; Expected = 30 },
        @{ Name = "two-complete-turns"; Lines = @($normalLines) + @($startedLine, $completedLine); Exit = 0; Expected = 0 },
        @{ Name = "item-failure-recovered"; Lines = @($threadLine, $startedLine, '{"type":"item.completed","item":{"type":"command_execution","status":"failed","exit_code":1}}', $completedLine); Exit = 0; Expected = 0 },
        @{ Name = "unknown-event"; Lines = @($threadLine, $startedLine, '{"type":"future.notice"}', $completedLine); Exit = 0; Expected = 0 },
        @{ Name = "resume-same-id"; Lines = $normalLines; Exit = 0; Expected = 0; Thread = $fixtureThread },
        @{ Name = "resume-no-thread"; Lines = @($startedLine, $completedLine); Exit = 0; Expected = 0; Thread = $fixtureThread },
        @{ Name = "resume-mismatch"; Lines = $normalLines; Exit = 0; Expected = 30; Thread = "other" },
        @{ Name = "initial-no-thread"; Lines = @($startedLine, $completedLine); Exit = 0; Expected = 30 },
        @{ Name = "missing-final"; Lines = $normalLines; Exit = 0; Expected = 30 },
        @{ Name = "empty-final"; Lines = $normalLines; Exit = 0; Expected = 30 }
    )
    if ($EvidenceTests -or $BudgetTests -or $ScopeTests -or $RulesTests -or $PathTests) { $cases = @() }
    foreach ($case in $cases) {
        $directory = Join-Path $testRoot $case.Name
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
        $events = Join-Path $directory "events.jsonl"
        $final = Join-Path $directory "final.md"
        if ($case.Name -ne "missing-json") { $case.Lines | Set-Content -LiteralPath $events -Encoding UTF8 }
        if ($case.Name -ne "missing-final") {
            $finalText = if ($case.Name -eq "empty-final") { " " } else { "fixture final" }
            $finalText | Set-Content -LiteralPath $final -Encoding UTF8
        }
        $expectedThread = if ($case.ContainsKey("Thread")) { $case.Thread } else { "" }
        $result = Get-WriterOutcome -ProcessExitCode $case.Exit -EventsPath $events -FinalPath $final -ExpectedThreadId $expectedThread
        Assert-That ($result.exit_code -eq $case.Expected) "$($case.Name): expected=$($case.Expected) actual=$($result.exit_code) detail=$($result.detail)"
        if ($case.Expected -eq 0) {
            Assert-That ($result.status -eq "COMPLETED" -and $result.thread_id -ceq $fixtureThread) "$($case.Name): 완료 상태/세션 불일치"
        }
        $passed++
        Write-Host "[test-harness-writer] PASS parser/$($case.Name)"
    }

    # 실제 production main의 gate/Reviewer 호출 횟수와 최종 result.json/exit 확인
    $flows = @(
        @{ Name = "success"; Exit = 0; Writers = 1; Gates = 1; Reviewers = 1 },
        @{ Name = "initial-nonzero"; Exit = 10; Writers = 1; Gates = 0; Reviewers = 0 },
        @{ Name = "initial-failed-event"; Exit = 10; Writers = 1; Gates = 0; Reviewers = 0 },
        @{ Name = "initial-malformed"; Exit = 30; Writers = 1; Gates = 0; Reviewers = 0 },
        @{ Name = "initial-incomplete"; Exit = 30; Writers = 1; Gates = 0; Reviewers = 0 },
        @{ Name = "initial-throw"; Exit = 30; Writers = 1; Gates = 0; Reviewers = 0 },
        @{ Name = "resume-nonzero"; Exit = 10; Writers = 2; Gates = 1; Reviewers = 0 },
        @{ Name = "resume-failed-event"; Exit = 10; Writers = 2; Gates = 1; Reviewers = 0 },
        @{ Name = "resume-mismatch"; Exit = 30; Writers = 2; Gates = 1; Reviewers = 0 },
        @{ Name = "resume-incomplete"; Exit = 30; Writers = 2; Gates = 1; Reviewers = 0 },
        @{ Name = "resume-no-thread"; Exit = 0; Writers = 2; Gates = 2; Reviewers = 1 },
        @{ Name = "resume-success"; Exit = 0; Writers = 2; Gates = 2; Reviewers = 1 },
        @{ Name = "reviewer-resume-nonzero"; Exit = 10; Writers = 2; Gates = 1; Reviewers = 1 },
        @{ Name = "validate-only"; Exit = 0; Writers = 0; Gates = 1; Reviewers = 0 }
    )
    if ($EvidenceTests) {
        $flows = @(
            @{ Name = "evidence-change"; Exit = 0; Writers = 1; Gates = 1; Reviewers = 1 },
            @{ Name = "evidence-task"; Exit = 20; Writers = 1; Gates = 0; Reviewers = 0 },
            @{ Name = "evidence-frozen"; Exit = 20; Writers = 1; Gates = 0; Reviewers = 0 },
            @{ Name = "evidence-baseline"; Exit = 20; Writers = 1; Gates = 0; Reviewers = 0 },
            @{ Name = "evidence-copy"; Exit = 20; Writers = 1; Gates = 0; Reviewers = 0 },
            @{ Name = "evidence-index"; Exit = 20; Writers = 1; Gates = 0; Reviewers = 0 },
            @{ Name = "evidence-gate-task"; Exit = 20; Writers = 1; Gates = 1; Reviewers = 0 },
            @{ Name = "evidence-unreviewable"; Exit = 20; Writers = 1; Gates = 1; Reviewers = 0 },
            @{ Name = "evidence-wrong-receipt"; Exit = 30; Writers = 1; Gates = 1; Reviewers = 1 },
            @{ Name = "evidence-missing-receipt"; Exit = 30; Writers = 1; Gates = 1; Reviewers = 1 },
            @{ Name = "evidence-review-edit"; Exit = 20; Writers = 1; Gates = 1; Reviewers = 1 },
            @{ Name = "evidence-review-log"; Exit = 20; Writers = 1; Gates = 1; Reviewers = 1 }
        )
    }
    if ($BudgetTests) {
        $flows = @(
            @{ Name="budget-writer-timeout"; Exit=20; Writers=1; Gates=0; Reviewers=0 },
            @{ Name="budget-gate-timeout"; Exit=20; Writers=1; Gates=1; Reviewers=0 },
            @{ Name="budget-reviewer-timeout"; Exit=20; Writers=1; Gates=1; Reviewers=1 },
            @{ Name="budget-reviewer-repeat"; Exit=10; Writers=3; Gates=3; Reviewers=3 },
            @{ Name="budget-reviewer-change"; Exit=0; Writers=5; Gates=5; Reviewers=5 },
            @{ Name="budget-max-attempts"; Exit=10; Writers=5; Gates=5; Reviewers=5 },
            @{ Name="budget-gate-repeat"; Exit=10; Writers=3; Gates=3; Reviewers=0 }
        )
    }
    if ($ScopeTests) {
        $flows = @(
            @{ Name="scope-validate-pass"; Exit=0; Writers=0; Gates=1; Reviewers=0 },
            @{ Name="scope-validate-fail"; Exit=10; Writers=0; Gates=1; Reviewers=0 },
            @{ Name="scope-validate-blocked"; Exit=20; Writers=0; Gates=1; Reviewers=0 }
        )
    }
    if ($RulesTests) {
        $flows = @(
            @{ Name="rules-allow"; Exit=30; Writers=0; Gates=0; Reviewers=0 },
            @{ Name="rules-malformed"; Exit=30; Writers=0; Gates=0; Reviewers=0 },
            @{ Name="rules-missing"; Exit=30; Writers=0; Gates=0; Reviewers=0 }
        )
    }
    if ($PathTests) {
        $flows = @(
            @{ Name="path-forward-root"; Exit=0; Writers=1; Gates=1; Reviewers=1 },
            @{ Name="path-sibling"; Exit=20; Writers=0; Gates=0; Reviewers=0 },
            @{ Name="path-parent"; Exit=20; Writers=0; Gates=0; Reviewers=0 }
        )
    }
    $expectedChecks = $cases.Count + $flows.Count
    $powerShellPath = (Get-Process -Id $PID).Path
    foreach ($flow in $flows) {
        $directory = Join-Path $testRoot ("flow-" + $flow.Name)
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
        foreach ($relative in @("scripts/harness.ps1", "scripts/harness-worker.ps1", "scripts/harness-process.ps1", "scripts/harness-process-child.ps1", "scripts/harness-job.cs", "scripts/harness-failures.ps1", "scripts/harness-evidence.ps1",
 "scripts/harness-local-secrets.ps1", "scripts/repo-hygiene.ps1", "scripts/verify-fast.ps1", "scripts/verify-full.ps1", "scripts/reviewer-output.schema.json", ".codex/hooks.json", ".codex/hooks/stop_gate.ps1", ".codex/rules/safety.rules", "task.md", "gradlew.bat")) {
            $path = Join-Path $directory $relative
            [System.IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
            "fixture" | Set-Content -LiteralPath $path -Encoding UTF8
        }
        [System.IO.File]::WriteAllText((Join-Path $directory "fake-codex.cmd"), "@echo off`r`nexit /b 0`r`n", [System.Text.Encoding]::ASCII)
        [System.IO.Directory]::CreateDirectory((Join-Path $directory "build")) | Out-Null
        [IO.File]::WriteAllText((Join-Path $directory "build/trace.log"), "")
        $log = Join-Path $directory "build/child.log"
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -FixtureChild -FixtureRoot $directory -Scenario $flow.Name 2>&1 |
                Set-Content -LiteralPath $log -Encoding UTF8 -ErrorAction Stop
            $code = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $previousPreference }
        Assert-That ($code -eq $flow.Exit) "$($flow.Name): child exit expected=$($flow.Exit) actual=$code; log=$log"
        $runs = @(Get-ChildItem -LiteralPath (Join-Path $directory "build/agent-runs") -Directory)
        Assert-That ($runs.Count -eq 1) "$($flow.Name): run 생성 수 불일치"
        $result = Get-Content -LiteralPath (Join-Path $runs[0].FullName "result.json") -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-That ($result.exit_code -eq $flow.Exit) "$($flow.Name): result.json exit 불일치"
        $expectedStatus = if ($flow.Exit -eq 0) { "PASS" } elseif ($flow.Exit -eq 10) { "FAILED" } elseif ($flow.Exit -eq 20) { "BLOCKED" } else { "HARNESS_ERROR" }
        Assert-That ($result.status -eq $expectedStatus) "$($flow.Name): 최종 상태 불일치"
        $expectedMode = if ($flow.Name -eq "validate-only" -or $flow.Name -like "scope-validate-*") { "VALIDATE_ONLY" } else { "IMPLEMENT" }
        Assert-CompletionScope -Result $result -Mode $expectedMode
        $expectedCompletion = if ($flow.Exit -ne 0) { "NONE" } elseif ($expectedMode -eq "VALIDATE_ONLY") { "VERIFIED" } else { "IMPLEMENTED" }
        Assert-That ($result.completion -ceq $expectedCompletion -and $result.implementation_completed -eq ($expectedCompletion -eq "IMPLEMENTED")) "완료 범위 오류"
        $trace = @(Get-Content -LiteralPath (Join-Path $directory "build/trace.log"))
        Assert-That (@($trace | Where-Object { $_ -eq "writer" }).Count -eq $flow.Writers) "$($flow.Name): Writer 호출 수 불일치"
        Assert-That (@($trace | Where-Object { $_ -eq "gate" }).Count -eq $flow.Gates) "$($flow.Name): 실패 뒤 gate 호출 또는 정상 gate 누락"
        Assert-That (@($trace | Where-Object { $_ -eq "reviewer" }).Count -eq $flow.Reviewers) "$($flow.Name): 실패 뒤 Reviewer 호출 또는 정상 Reviewer 누락"
        $statuses = @(Get-ChildItem -LiteralPath $runs[0].FullName -Recurse -File -Filter "writer-status.json" | Sort-Object FullName)
        $expectedStatuses = if ($flow.Name -in @("initial-throw", "budget-writer-timeout")) { 0 } else { $flow.Writers }
        Assert-That ($statuses.Count -eq $expectedStatuses) "$($flow.Name): attempt별 Writer 상태 증거 누락"
        if ($statuses.Count -gt 0) {
            $lastStatus = Get-Content -LiteralPath $statuses[-1].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
            Assert-That ($lastStatus.exit_code -eq $(if ($EvidenceTests -or $BudgetTests) { 0 } else { $flow.Exit })) "$($flow.Name): 마지막 Writer 상태가 최종 exit와 다름"
        }
        $passed++
        Write-Host "[test-harness-writer] PASS flow/$($flow.Name)"
    }
    if ($PathTests) {
        Assert-That ($passed -eq 3) "경로 검증 수 불일치"
        & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath
        Assert-That ($LASTEXITCODE -eq 0) "Git / 경로 조건의 기존 Writer 44개 회귀 실패"
        Write-Host "[test-harness-writer] PASS: 47 checks (path 3 + writer 44; 실제 Codex/API/DB 실행 없음)"
    }
    else { Write-Host "[test-harness-writer] PASS: $passed checks (실제 Codex/API/DB 실행 없음)" }
    exit 0
}
catch {
    Write-Host "[test-harness-writer] FAIL: $($_.Exception.Message)"
    # 실패 시 fixture 로그 보존, 실제 프로젝트 파일이나 자격 증명 없음
    Write-Host "[test-harness-writer] 증거: $testRoot"
    exit 1
}
finally {
    if ($passed -eq $(if ($ScopeTests -or $RulesTests -or $PathTests) { 3 } elseif ($BudgetTests) { 7 } elseif ($EvidenceTests) { 12 } else { 44 }) -and (Test-Path -LiteralPath $testRoot)) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
