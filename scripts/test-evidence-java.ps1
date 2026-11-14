[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness-evidence.ps1')
$script:count = 0
function Check([bool]$Ok, [string]$Name) {
    if (-not $Ok) { throw "FAIL: $Name" }
    $script:count++
    Write-Host "[test-evidence-java] PASS $Name"
}
$cases = @(
    @('src/test/java/dev/cgt/pixelplace/auth/config/AuthPropertiesTest.java', 'contextRunner.withPropertyValues("pixel-place.auth.jwt-secret=" + testSigningKey, "pixel-place.auth.oauth-cookie-secret=" + testCookieKey);'),
    @('src/test/java/dev/cgt/pixelplace/auth/config/AuthPropertiesTest.java', 'contextRunner.withPropertyValues("pixel-place.auth.jwt-secret=invalid-base64-test-sentinel");'),
    @('src/main/java/dev/cgt/pixelplace/auth/config/AuthProperties.java', 'this.jwtSecret = decodeAndValidate(encodedValue);')
)
$i = 0
foreach ($case in $cases) {
    $i++
    Check ((Get-EvidenceTextReason $case[0] ([Text.Encoding]::UTF8.GetBytes($case[1]))) -eq 'text') "reported-reproduction-$i"
}
$bytes = [Text.Encoding]::UTF8.GetBytes('password: fixture-only')
foreach ($path in @('src/main/java/A.java', 'src/test/java/A.java', 'src\test\java\A.java', '명령문/13/A.md', 'docs/pixel-place-details.md')) {
    Check ((Get-EvidenceTextReason $path $bytes) -eq 'text') "reviewable: $path"
}
foreach ($path in @('src/main/resources/application.yml', 'src/test/resources/application.yml', 'src/main/java/config.yml', 'src/main/java/A.java.bak', 'other/src/main/java/A.java', 'src/main/java/../A.java', 'src/integrationTest/java/A.java')) {
    Check ((Get-EvidenceTextReason $path $bytes) -eq 'sensitive-content') "protected: $path"
}
Check ((Get-EvidenceTextReason 'local-secrets/secrets.yml' $bytes) -eq 'sensitive-name') 'local-secrets-protected'
Check ((Get-EvidenceTextReason 'src/main/java/secrets.java' $bytes) -eq 'sensitive-name') 'sensitive-filename-protected'
Check ((Get-EvidenceTextReason 'src/main/java/A.java' ([byte[]]@(65,0))) -eq 'binary') 'binary-protected'
Check ((Get-EvidenceTextReason 'src/main/java/A.java' ([byte[]]@(255))) -eq 'non-utf8') 'invalid-utf8-protected'
Check ((Get-EvidenceTextReason 'src/main/java/A.java' (New-Object byte[] 2097153)) -eq 'over-2MiB') 'size-protected'

# Real file snapshot and cumulative diff; only a disposable Git fixture is changed.
$repo = Join-Path ([IO.Path]::GetTempPath()) ('java evidence 한글 ' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory((Join-Path $repo 'src/main/java')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $repo 'src/test/java')) | Out-Null
function Put([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false))) }
try {
    & git init -q $repo
    if ($LASTEXITCODE -ne 0) { throw 'fixture init failed' }
    Put (Join-Path $repo '.gitignore') "build/`n"
    Put (Join-Path $repo 'task.md') '# fixture task'
    $source = Join-Path $repo 'src/main/java/AuthProperties.java'
    Put $source $cases[2][1]
    & git -C $repo add -- .gitignore task.md src/main/java/AuthProperties.java
    if ($LASTEXITCODE -ne 0) { throw 'fixture add failed' }
    & git -C $repo -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -qm fixture
    if ($LASTEXITCODE -ne 0) { throw 'fixture commit failed' }
    $run = Join-Path $repo 'build/run'
    $e = New-RunEvidence $repo $run (Join-Path $repo 'task.md')
    Check ([IO.File]::ReadAllText($e.snapshot.files['src/main/java/AuthProperties.java'].content) -ceq $cases[2][1]) 'source-baseline-exact'
    Put $source ($cases[2][1] + "`n// reviewed change")
    Put (Join-Path $repo 'src/test/java/AuthPropertiesTest.java') ($cases[0][1] + "`n" + $cases[1][1])
    $c = New-ChangeEvidence $e (Join-Path $run 'review')
    $j = Get-Content -LiteralPath $c.manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    Check ($c.unreviewable.Count -eq 0 -and $j.changes.Count -eq 2) 'java-changes-reviewable'
    foreach ($change in $j.changes) {
        Check ((Test-Path -LiteralPath $change.diff -PathType Leaf) -and $change.after.coverage -eq 'text') "diff: $($change.path)"
    }
    Assert-RunEvidence $e
    Check $true 'baseline-index-task-preserved'
    [IO.Directory]::CreateDirectory((Join-Path $repo 'local-secrets')) | Out-Null
    Put (Join-Path $repo 'local-secrets/secrets.yml') "DB_PASSWORD: 'fixture-only'"
    $secret = New-ChangeEvidence $e (Join-Path $run 'secret')
    Check ($secret.unreviewable -contains 'local-secrets/secrets.yml') 'secret-change-still-blocked'
    Write-Host "[test-evidence-java] PASS: $script:count checks (no Codex/API/DB)"
}
finally { Write-Host "[test-evidence-java] Fixture: $repo" }
