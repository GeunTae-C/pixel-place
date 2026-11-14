[CmdletBinding()]
param([string]$TaskFile = '.\명령문\013-A_기반_실행명령문.md')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness-evidence.ps1')
$script:passed = 0
function Check([bool]$Ok, [string]$Name) {
    if (-not $Ok) { throw "FAIL: $Name" }
    $script:passed++
    Write-Host "[test-evidence-config] PASS $Name"
}
function Put([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding($false)))
}
function Rejected([scriptblock]$Action, [string]$Name) {
    $blocked = $false
    try { & $Action } catch { $blocked = $true }
    Check $blocked $Name
}
$allow = @(
    'jdbc:',
    '설명에 `jdbc:` 표기가 있다.',
    'url: jdbc:mysql://localhost:3306/pixel_place?serverTimezone=Asia/Seoul&characterEncoding=UTF-8',
    'url: "jdbc:mysql://localhost/pixel_place_test"',
    'jdbc:mysql://localhost/test?useSSL=false&allowPublicKeyRetrieval=true',
    'password: ${DB_PASSWORD}',
    'password: "${DB_PASSWORD}"',
    "password: '`${DB_PASSWORD}'",
    'password: $env:DB_PASSWORD',
    'password: "$env:DB_PASSWORD"',
    'client-secret: ${KAKAO_CLIENT_SECRET}',
    'JWT_SECRET=${JWT_SECRET}',
    'api-key: ${SERVICE_API_KEY}',
    '{"password":"${DB_PASSWORD}","client-secret":"${KAKAO_CLIENT_SECRET}"}',
    'password: ""'
)
$deny = @(
    'jdbc:mysql://root:fixture@localhost/test',
    'jdbc:mysql://localhost/test?password=fixture',
    'jdbc:mysql://localhost/test?password=${DB_PASSWORD}',
    'jdbc:mysql://localhost/test?pass%77ord=fixture',
    'jdbc:mysql://localhost/test?unknown=fixture',
    'jdbc:mysql://localhost/test?serverTimezone=fixture-secret',
    'jdbc:mysql://localhost/test?useSSL=true&useSSL=false',
    'jdbc:mysql://address=(host=localhost,user=root,password=fixture)/test',
    'jdbc:postgresql://localhost/test',
    'jdbc:opaque-fixture',
    'password: fixture-only',
    'password: "fixture-only"',
    'password: ${DB_PASSWORD:fixture-default}',
    'password: "${DB_PASSWORD}" + "fixture"',
    'password: ${DB_PASSWORD}suffix',
    'password: ${DB_PASSWORD} + fixture',
    'password: "${DB_PASSWORD}suffix"',
    'password: $env:DB_PASSWORD+fixture',
    'password: "${DB_PASSWORD}',
    'password: |',
    'password: *alias',
    '{"password":"${DB_PASSWORD}","client-secret":"fixture-only"}',
    'DB_PASSWORD=fixture-only',
    'dbPassword=fixture-only',
    'JWT_SECRET=fixture-only',
    'refresh-token: fixture-only',
    'Authorization: Bearer fixture-only',
    '-----BEGIN PRIVATE KEY-----'
)
$n = 0
foreach ($v in $allow) {
    $n++
    Check ((Get-EvidenceTextReason 'application.yml' ([Text.Encoding]::UTF8.GetBytes($v))) -eq 'text') "allow-$n"
}
$n = 0
foreach ($v in $deny) {
    $n++
    Check ((Get-EvidenceTextReason 'application.yml' ([Text.Encoding]::UTF8.GetBytes($v))) -eq 'sensitive-content') "protect-$n"
}
Check ((Get-EvidenceTextReason '.env' ([Text.Encoding]::UTF8.GetBytes('x'))) -eq 'sensitive-name') 'secret-name'
Check ((Get-EvidenceTextReason 'x.md' ([byte[]]@(65,0))) -eq 'binary') 'binary'
Check ((Get-EvidenceTextReason 'x.md' ([byte[]]@(255))) -eq 'non-utf8') 'non-utf8'
Check ((Get-EvidenceTextReason 'x.md' (New-Object byte[] 2097153)) -eq 'over-2MiB') 'size-limit'
foreach ($path in @($TaskFile, '.\src\main\resources\application.yml', '.\src\test\resources\application-mysql-integration.yml')) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required local file missing: $path" }
    $bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $path).Path)
    $reason = Get-EvidenceTextReason $path $bytes
    Check ($reason -eq 'text') "local-file: $path (coverage=$reason)"
}

# Only disposable temp repository is written. No real Codex/API/DB execution.
$temp = Join-Path ([IO.Path]::GetTempPath()) ('evidence config 한글 ' + [Guid]::NewGuid().ToString('N'))
$repo = Join-Path $temp 'repo'
[IO.Directory]::CreateDirectory($repo) | Out-Null
try {
    & git init -q $repo
    if ($LASTEXITCODE -ne 0) { throw 'fixture init failed' }
    Put (Join-Path $repo 'task.md') '# fixture task'
    Put (Join-Path $repo '.gitignore') "build/`nignored/`n"
    $config = Join-Path $repo 'application.yml'
    $safe = 'url: jdbc:mysql://localhost/test' + "`n" + 'password: "${DB_PASSWORD}"' + "`n"
    Put $config $safe
    & git -C $repo add -- task.md .gitignore application.yml
    if ($LASTEXITCODE -ne 0) { throw 'fixture add failed' }
    & git -C $repo -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -qm fixture
    if ($LASTEXITCODE -ne 0) { throw 'fixture commit failed' }
    Put $config ($safe + "# existing unstaged change`n")
    [IO.Directory]::CreateDirectory((Join-Path $repo 'ignored')) | Out-Null
    Put (Join-Path $repo 'ignored/config.yml') $safe
    $run = Join-Path $repo 'build/run'
    $e = New-RunEvidence $repo $run (Join-Path $repo 'task.md')
    Check ($e.snapshot.files['application.yml'].coverage -eq 'text') 'config-copy-available'
    Check ($e.snapshot.files['ignored/config.yml'].kind -eq 'ignored' -and $e.snapshot.files['ignored/config.yml'].coverage -eq 'text') 'ignored-config-evidence'
    Check ([IO.File]::ReadAllText($e.snapshot.files['application.yml'].content) -ceq ($safe + "# existing unstaged change`n")) 'exact-dirty-baseline'
    Check ((Get-EvidenceHash ([IO.File]::ReadAllBytes($e.task))) -ceq $e.task_sha256) 'frozen-task-hash'
    Put $config ($safe + "# new change`n")
    $c = New-ChangeEvidence $e (Join-Path $run 'safe')
    $j = Get-Content -LiteralPath $c.manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    Check ($c.unreviewable.Count -eq 0 -and $j.changes.Count -eq 1) 'safe-config-reviewable'
    Check ((Test-Path -LiteralPath $j.changes[0].diff) -and ([IO.File]::ReadAllText($j.changes[0].diff).Contains('existing unstaged change'))) 'exact-config-diff'
    Assert-RunEvidence $e
    Check $true 'index-preserved'
    $receipt = [pscustomobject]@{ evidence = [pscustomobject]@{ task_sha256=$c.task_sha256; baseline_sha256=$c.baseline_sha256; change_manifest_sha256=$c.change_manifest_sha256 } }
    Assert-ReviewReceipt $receipt $c
    Check $true 'receipt-match'
    Rejected { Assert-ReviewReceipt ([pscustomobject]@{}) $c } 'missing-receipt'
    $receipt.evidence.task_sha256 = 'wrong'
    Rejected { Assert-ReviewReceipt $receipt $c } 'wrong-receipt'
    Put $config ('password: fixture-only-secret')
    $secret = New-ChangeEvidence $e (Join-Path $run 'secret')
    $sj = Get-Content -LiteralPath $secret.manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    Check ($secret.unreviewable -contains 'application.yml') 'secret-change-blocked'
    Check ($sj.changes[0].after.content -eq '' -and $sj.changes[0].diff -eq '') 'secret-no-copy-no-diff'
    $se = New-RunEvidence $repo (Join-Path $repo 'build/secret-baseline') (Join-Path $repo 'task.md')
    Put $config $safe
    $cleaned = New-ChangeEvidence $se (Join-Path $repo 'build/cleaned')
    Check ($cleaned.unreviewable -contains 'application.yml') 'secret-to-reference-still-blocked'
    Remove-Item -LiteralPath $config
    $deleted = New-ChangeEvidence $se (Join-Path $repo 'build/deleted')
    Check ($deleted.unreviewable -contains 'application.yml') 'secret-delete-still-blocked'
    Put (Join-Path $repo 'new-secret.yml') 'api-key: fixture-only-secret'
    $added = New-ChangeEvidence $e (Join-Path $run 'added')
    Check ($added.unreviewable -contains 'new-secret.yml') 'new-secret-blocked'
    $leaked = @(Get-ChildItem -LiteralPath (Join-Path $repo 'build') -Recurse -File | Where-Object { [IO.File]::ReadAllText($_.FullName).Contains('fixture-only-secret') })
    Check ($leaked.Count -eq 0) 'secret-not-in-evidence-artifacts'
    Rejected { Assert-ReviewState $e $c (Join-Path $run 'stale') } 'stale-review-blocked'
    Put $e.task 'tampered'
    Rejected { Assert-RunEvidence $e } 'frozen-tamper-blocked'
    Write-Host "[test-evidence-config] PASS: $script:passed checks (real temp Git/evidence; no Codex/API/DB)"
}
finally { Write-Host "[test-evidence-config] fixture: $temp" }
