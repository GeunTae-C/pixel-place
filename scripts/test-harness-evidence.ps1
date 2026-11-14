[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-evidence.ps1")

# 임시 Git 저장소에서 실제 파일/분류/변경 증거를 검사하고 실제 main 회귀 검증 연결
function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}
function Assert-Rejected {
    param([scriptblock]$Action, [string]$Expected)
    $rejected = $false
    try { & $Action }
    catch {
        if (-not $_.Exception.Message.Contains($Expected)) { throw }
        $rejected = $true
    }
    Assert-That $rejected "예상 차단 누락: $Expected"
}
function Put-Text {
    param([string]$Path, [string]$Text)
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}
function Pass-Check {
    param([string]$Name)
    $script:passed++
    Write-Host "[test-harness-evidence] PASS $Name"
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("evidence harness 검증 " + [Guid]::NewGuid().ToString("N"))
$script:passed = 0
$success = $false
try {
    $repo = Join-Path $testRoot "repo"
    [IO.Directory]::CreateDirectory($repo) | Out-Null
    # 사용자 저장소가 아닌 disposable fixture에서만 init/add/commit 사용
    & git init -q $repo
    if ($LASTEXITCODE -ne 0) { throw "fixture Git init 실패" }
    Put-Text (Join-Path $repo ".gitignore") "ignored/`nbuild/`n.env`n"
    Put-Text (Join-Path $repo "tracked.txt") "committed`n"
    Put-Text (Join-Path $repo "deleted.txt") "keep until writer`n"
    $taskPath = Join-Path $repo "task.md"
    Put-Text $taskPath "# 작업`n파일 동작 보완`n"
    & git -C $repo add -- .gitignore tracked.txt deleted.txt task.md
    if ($LASTEXITCODE -ne 0) { throw "fixture Git add 실패" }
    & git -C $repo -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm fixture
    if ($LASTEXITCODE -ne 0) { throw "fixture Git commit 실패" }
    Put-Text (Join-Path $repo "tracked.txt") "staged user change`n"
    & git -C $repo add -- tracked.txt
    if ($LASTEXITCODE -ne 0) { throw "fixture staged 변경 준비 실패" }
    Put-Text (Join-Path $repo "tracked.txt") "staged user change`nunstaged user change`n"
    Put-Text (Join-Path $repo "untracked 한글.txt") "untracked before`n"
    Put-Text (Join-Path $repo "ignored/local.md") "ignored before`n"
    Put-Text (Join-Path $repo "build/generated.txt") "exclude build`n"
    Put-Text (Join-Path $repo ".env") "DB_PASSWORD=fixture-value-never-real`n"
    Put-Text (Join-Path $repo "application.yml") "url: jdbc:mysql://localhost/fixture?password=fixture-only-never-real`n"
    [IO.File]::WriteAllBytes((Join-Path $repo "binary.dat"), [byte[]]@(0, 255, 1))
    $run = Join-Path $repo "build/run"
    $e = New-RunEvidence $repo $run $taskPath
    Assert-RunEvidence $e
    Assert-That ($e.snapshot.files["tracked.txt"].kind -eq "tracked") "tracked 분류 오류"
    Assert-That ($e.snapshot.files["untracked 한글.txt"].kind -eq "untracked") "untracked 분류 오류"
    Assert-That ($e.snapshot.files["ignored/local.md"].kind -eq "ignored") "ignored 분류 오류"
    Pass-Check "tracked-untracked-ignored"
    Assert-That (-not $e.snapshot.files.ContainsKey("build/generated.txt")) "build 제외 실패"
    Assert-That (@($e.snapshot.files.Keys | Where-Object { $_ -like ".git/*" }).Count -eq 0) "Git 내부 제외 실패"
    Pass-Check "generated-exclusion"
    $before = [IO.File]::ReadAllText($e.snapshot.files["tracked.txt"].content)
    Assert-That ($before -eq "staged user change`nunstaged user change`n") "dirty 기준선 원문 손실"
    Pass-Check "dirty-baseline"
    Assert-That ((Get-FileHash -LiteralPath $e.task -Algorithm SHA256).Hash -ceq (Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash) "명령문 bytes 불일치"
    Pass-Check "frozen-task-bytes"
    foreach ($name in @(".env", "application.yml", "binary.dat")) {
        Assert-That ($e.snapshot.files[$name].content -eq "" -and $e.snapshot.files[$name].sha256.Length -eq 64) "민감/바이너리 원문 복제"
    }
    Pass-Check "hash-only-no-content-copy"
    $initial = New-ChangeEvidence $e (Join-Path $run "unchanged")
    $initialJson = Get-Content -LiteralPath $initial.manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-That ($initialJson.changes.Count -eq 0) "기존 dirty 상태를 새 변경으로 판정"
    Pass-Check "preexisting-is-not-new"

    Put-Text (Join-Path $repo "tracked.txt") ($before + "writer change`n")
    Put-Text (Join-Path $repo "ignored/local.md") "ignored after`n"
    Remove-Item -LiteralPath (Join-Path $repo "deleted.txt")
    Put-Text (Join-Path $repo "new.md") "new file`n"
    $attempt = Join-Path $run "attempt"
    $c = New-ChangeEvidence $e $attempt
    $json = Get-Content -LiteralPath $c.manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-That ($json.changes.Count -eq 4 -and $json.unreviewable.Count -eq 0) "변경 집계 오류"
    foreach ($expect in @(@("tracked.txt","M"),@("ignored/local.md","M"),@("deleted.txt","D"),@("new.md","A"))) {
        $entry = @($json.changes | Where-Object { $_.path -eq $expect[0] })
        Assert-That ($entry.Count -eq 1 -and $entry[0].status -eq $expect[1]) "A/M/D 분류 오류"
        Assert-That (Test-Path -LiteralPath $entry[0].diff -PathType Leaf) "변경 diff 누락"
    }
    Pass-Check "add-modify-delete-and-ignored-diff"
    $dirtyChange = @($json.changes | Where-Object { $_.path -eq "tracked.txt" })[0]
    $patch = Get-Content -LiteralPath $dirtyChange.diff -Raw -Encoding UTF8
    Assert-That ($patch.Contains("+writer change") -and -not $patch.Contains("+unstaged user change")) "시작 dirty 상태 대비 diff 아님"
    Pass-Check "diff-against-working-baseline"
    Assert-RunEvidence $e
    Pass-Check "index-preserved"
    $receipt = [pscustomobject]@{ evidence = [pscustomobject]@{ task_sha256 = $c.task_sha256; baseline_sha256 = $c.baseline_sha256; change_manifest_sha256 = $c.change_manifest_sha256 } }
    Assert-ReviewReceipt $receipt $c
    Pass-Check "review-receipt-match"
    Assert-Rejected { Assert-ReviewReceipt ([pscustomobject]@{}) $c } "Reviewer evidence"
    Pass-Check "missing-receipt-rejected"
    $receipt.evidence.change_manifest_sha256 = "wrong"
    Assert-Rejected { Assert-ReviewReceipt $receipt $c } "hash 불일치"
    Pass-Check "wrong-receipt-rejected"
    Assert-ReviewState $e $c $attempt
    Pass-Check "review-state-stable"
    Put-Text (Join-Path $repo "new.md") "changed during review`n"
    Assert-Rejected { Assert-ReviewState $e $c $attempt } "EVIDENCE_CHANGED:"
    Pass-Check "stale-review-rejected"
    Put-Text (Join-Path $repo "new.md") "new file`n"

    $taskBytes = [IO.File]::ReadAllBytes($taskPath)
    Put-Text $taskPath "modified requirements"
    Assert-Rejected { Assert-RunEvidence $e } "EVIDENCE_CHANGED:"
    [IO.File]::WriteAllBytes($taskPath, $taskBytes)
    Pass-Check "source-task-change-rejected"
    $frozenBytes = [IO.File]::ReadAllBytes($e.task)
    Remove-Item -LiteralPath $e.task
    Assert-Rejected { Assert-RunEvidence $e } "EVIDENCE_CHANGED:"
    [IO.File]::WriteAllBytes($e.task, $frozenBytes)
    Pass-Check "frozen-task-delete-rejected"
    $manifestBytes = [IO.File]::ReadAllBytes($e.manifest)
    Put-Text $e.manifest "modified manifest"
    Assert-Rejected { Assert-RunEvidence $e } "EVIDENCE_CHANGED:"
    [IO.File]::WriteAllBytes($e.manifest, $manifestBytes)
    Pass-Check "baseline-tamper-rejected"
    $changeBytes = [IO.File]::ReadAllBytes($c.manifest)
    Put-Text $c.manifest "modified change manifest"
    Assert-Rejected { Assert-ReviewState $e $c $attempt } "EVIDENCE_CHANGED:"
    [IO.File]::WriteAllBytes($c.manifest, $changeBytes)
    Pass-Check "review-evidence-tamper-rejected"
    Put-Text (Join-Path $repo ".env") "DB_PASSWORD=fixture-changed`n"
    $sensitive = New-ChangeEvidence $e (Join-Path $run "sensitive")
    Assert-That ($sensitive.unreviewable -contains ".env") "비밀 변경의 검토 불가 표시 누락"
    Pass-Check "sensitive-change-no-auto-pass"
    & git -C $repo add -- new.md
    if ($LASTEXITCODE -ne 0) { throw "fixture index 변경 실패" }
    Assert-Rejected { Assert-RunEvidence $e } "EVIDENCE_CHANGED:"
    Pass-Check "index-change-rejected"

    $powerShellPath = (Get-Process -Id $PID).Path
    $writerTest = Join-Path $PSScriptRoot "test-harness-writer.ps1"
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File $writerTest
    if ($LASTEXITCODE -ne 0) { throw "기존 Writer 44개 회귀 검증 실패" }
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File $writerTest -EvidenceTests
    if ($LASTEXITCODE -ne 0) { throw "증거 연결 main 흐름 12개 검증 실패" }
    Assert-That ($script:passed -eq 20) "evidence 검증 수 불일치"
    $success = $true
    Write-Host "[test-harness-evidence] PASS: 76 checks (evidence 20 + writer 44 + flow 12; 실제 Codex/API/DB 실행 없음)"
    exit 0
}
catch {
    Write-Host "[test-harness-evidence] FAIL: $($_.Exception.Message)"
    Write-Host "[test-harness-evidence] 증거: $testRoot"
    exit 1
}
finally {
    if ($success -and (Test-Path -LiteralPath $testRoot)) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
