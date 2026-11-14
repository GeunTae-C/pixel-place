[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness-evidence.ps1')
$script:passed = 0
function Check([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
    Write-Host "[test-public-docs] PASS $Name"
}
$sample = [Text.Encoding]::UTF8.GetBytes('Bearer Access JWT; password: fixture-only')
foreach ($path in @('명령문/task.md', '명령문/013단계/nested.txt', '명령문\nested\task.md', 'docs/pixel-place-details.md')) {
    Check ((Get-EvidenceTextReason $path $sample) -eq 'text') "public: $path"
}
foreach ($path in @('명령문-old/task.md', 'other/명령문/task.md', 'docs/other.md', 'docs/pixel-place-details.md.bak', '명령문/../outside.md', 'src/main/resources/application.yml')) {
    Check ((Get-EvidenceTextReason $path $sample) -eq 'sensitive-content') "protected: $path"
}
foreach ($path in @('local-secrets/secrets.yml', '명령문/secrets.yml', '명령문/.env', '명령문/key.pem', '명령문\secrets.yml', 'local-secrets\secrets.yml')) {
    Check ((Get-EvidenceTextReason $path $sample) -eq 'sensitive-name') "protected-name: $path"
}
foreach ($path in @('명령문/task.md', 'docs/pixel-place-details.md')) {
    Check ((Get-EvidenceTextReason $path ([byte[]]@(65,0))) -eq 'binary') "binary: $path"
    Check ((Get-EvidenceTextReason $path ([byte[]]@(255))) -eq 'non-utf8') "utf8: $path"
    Check ((Get-EvidenceTextReason $path (New-Object byte[] 2097153)) -eq 'over-2MiB') "size: $path"
}
$project = Split-Path -Parent $PSScriptRoot
foreach ($relative in @('docs/pixel-place-details.md', '명령문/_5~20단계_정리260703.md', '명령문/013-A_기반_실행명령문.md')) {
    $p = Join-Path $project $relative
    if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { throw "Required document missing: $relative" }
    Check ((Get-EvidenceTextReason $relative ([IO.File]::ReadAllBytes($p))) -eq 'text') "real-document: $relative"
}

# Check real evidence storage/diff in an isolated temporary repository.
$temp = Join-Path ([IO.Path]::GetTempPath()) ('public docs 한글 ' + [Guid]::NewGuid().ToString('N'))
$repo = Join-Path $temp 'repo'
[IO.Directory]::CreateDirectory((Join-Path $repo '명령문')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $repo 'docs')) | Out-Null
function Put([string]$Path, [string]$Value) { [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding($false))) }
try {
    & git init -q $repo
    if ($LASTEXITCODE -ne 0) { throw 'fixture init failed' }
    Put (Join-Path $repo '.gitignore') "build/`n명령문/`n"
    $details = Join-Path $repo 'docs/pixel-place-details.md'
    Put $details 'Bearer Access JWT'
    & git -C $repo add -- .gitignore docs/pixel-place-details.md
    if ($LASTEXITCODE -ne 0) { throw 'fixture add failed' }
    & git -C $repo -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -qm fixture
    if ($LASTEXITCODE -ne 0) { throw 'fixture commit failed' }
    $task = Join-Path $repo '명령문/task.md'
    Put $task 'Bearer Access JWT task'
    $run = Join-Path $repo 'build/run'
    $e = New-RunEvidence $repo $run $task
    Check ($e.snapshot.files['명령문/task.md'].kind -eq 'ignored') 'ignored-task-included'
    Check ([IO.File]::ReadAllText($e.task) -ceq 'Bearer Access JWT task') 'task-frozen-exactly'
    Check ($e.snapshot.files['docs/pixel-place-details.md'].coverage -eq 'text') 'details-baseline-copy'
    Put $details "Bearer Access JWT`nnew documentation"
    $c = New-ChangeEvidence $e (Join-Path $run 'review')
    $j = Get-Content -LiteralPath $c.manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    Check ($c.unreviewable.Count -eq 0 -and $j.changes.Count -eq 1) 'document-change-reviewable'
    Check ([IO.File]::ReadAllText($j.changes[0].diff).Contains('+new documentation')) 'document-diff-preserved'
    Assert-RunEvidence $e
    Check $true 'index-and-task-preserved'
    Put $task 'changed input'
    $rejected = $false
    try { Assert-RunEvidence $e } catch { $rejected = $true }
    Check $rejected 'task-change-still-blocked'
    Write-Host "[test-public-docs] PASS: $script:passed checks (no Codex/API/DB)"
}
finally { Write-Host "[test-public-docs] Fixture: $temp" }
