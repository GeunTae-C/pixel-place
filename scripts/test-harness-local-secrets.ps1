[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness-local-secrets.ps1')
$count = 0
function Check([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:count++
    Write-Host "[test-local-secrets] PASS $Name"
}
$root = Join-Path ([IO.Path]::GetTempPath()) ('local secrets 한글 ' + [Guid]::NewGuid().ToString('N'))
$folder = Join-Path $root 'local-secrets'
[IO.Directory]::CreateDirectory($folder) | Out-Null
$path = Join-Path $folder 'secrets.yml'
function Put([string]$Value) { [IO.File]::WriteAllText($path, $Value, (New-Object Text.UTF8Encoding($true))) }
$names = @('DB_PASSWORD', 'KAKAO_CLIENT_SECRET', 'SERVICE_API_KEY')
$saved = @{}
$userSaved = @{}
foreach ($name in $names) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    $userSaved[$name] = [Environment]::GetEnvironmentVariable($name, 'User')
}
try {
    $env:DB_PASSWORD = 'prior-fixture'
    Import-HarnessLocalSecrets $root
    Check ($env:DB_PASSWORD -ceq 'prior-fixture') 'absent-file-keeps-environment'
    Put "# fixture`r`nDB_PASSWORD: 'file-fixture'`r`nSERVICE_API_KEY: 'key-fixture'`r`n"
    $output = @(Import-HarnessLocalSecrets $root)
    Check ($output.Count -eq 0) 'no-value-output'
    Check ($env:DB_PASSWORD -ceq 'file-fixture') 'file-wins'
    Check ($env:SERVICE_API_KEY -ceq 'key-fixture') 'api-key-imported'
    Put @'
DB_PASSWORD: '한글 # $env:X ${X} '' quote ; & ! \' # comment
'@
    Import-HarnessLocalSecrets $root
    Check ($env:DB_PASSWORD -ceq '한글 # $env:X ${X} '' quote ; & ! \') 'literal-special-characters'
    Check ($env:SERVICE_API_KEY -ceq 'key-fixture') 'missing-key-preserved'
    $cases = @(
        "DB_PASSWORD: ''",
        "DB_PASSWORD: '   '",
        'DB_PASSWORD: "double-quoted"',
        'DB_PASSWORD: unquoted',
        "DB_PASSWORD: 'one'`nDB_PASSWORD: 'two'",
        "DB_PASSWORD: 'first'`ninvalid: 'SENSITIVE-FIXTURE-MARKER'",
        "PATH: 'SENSITIVE-FIXTURE-MARKER'",
        "CODEX_HOME: 'SENSITIVE-FIXTURE-MARKER'",
        " DB_PASSWORD: 'nested'",
        'DB_PASSWORD: *alias',
        'DB_PASSWORD: |',
        "DB_PASSWORD: 'unclosed",
        "DB_PASSWORD: 'x' trailing",
        "DB_PASSWORD: 'x'`n---",
        "DB_PASSWORD: 'x$([char]0)y'"
    )
    $i = 0
    foreach ($case in $cases) {
        $i++
        $env:DB_PASSWORD = 'unchanged-fixture'
        Put $case
        $rejected = $false
        try { Import-HarnessLocalSecrets $root }
        catch {
            $rejected = $true
            if ($_.Exception.Message.Contains('SENSITIVE-FIXTURE-MARKER')) { throw 'Error exposed fixture value.' }
        }
        Check ($rejected -and $env:DB_PASSWORD -ceq 'unchanged-fixture') "reject-atomically-$i"
    }
    [IO.File]::WriteAllBytes($path, [byte[]]@(255))
    $rejected = $false
    try { Import-HarnessLocalSecrets $root } catch { $rejected = $true }
    Check $rejected 'invalid-utf8'
    [IO.File]::WriteAllBytes($path, (New-Object byte[] 65537))
    $rejected = $false
    try { Import-HarnessLocalSecrets $root } catch { $rejected = $true }
    Check $rejected 'size-limit'
    Put "DB_PASSWORD: 'child-fixture'"
    Import-HarnessLocalSecrets $root
    $child = (Get-Process -Id $PID).Path
    # Windows PowerShell 5.1 native argument passing can strip embedded quotes.
    # Encode only this fixed test command; no actual credential is in the argument.
    $childCommand = 'if ($env:DB_PASSWORD -ceq "child-fixture") { exit 0 } else { exit 1 }'
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))
    & $child -NoProfile -NonInteractive -EncodedCommand $encodedCommand
    Check ($LASTEXITCODE -eq 0) 'child-inherits'
    foreach ($name in $names) {
        Check ([Environment]::GetEnvironmentVariable($name, 'User') -ceq $userSaved[$name]) "no-user-persistence-$name"
    }
    . (Join-Path $PSScriptRoot 'harness-evidence.ps1')
    Check ((Get-EvidenceTextReason 'local-secrets/secrets.yml' ([IO.File]::ReadAllBytes($path))) -eq 'sensitive-name') 'secret-file-hash-only'
    $project = Split-Path -Parent $PSScriptRoot
    Check ([IO.File]::ReadAllText((Join-Path $project 'scripts/harness.ps1')).Contains('Import-HarnessLocalSecrets -Root $root')) 'harness-connected'
    Check ([IO.File]::ReadAllText((Join-Path $project 'scripts/verify-full.ps1')).Contains('Import-HarnessLocalSecrets -Root $repoRoot')) 'full-connected'
    Check ([IO.File]::ReadAllText((Join-Path $project 'scripts/harness-worker.ps1')).Contains('"scripts/harness-local-secrets.ps1"')) 'integrity-list-connected'
    Write-Host "[test-local-secrets] PASS: $count checks (fixture only; no Codex/DB/API)"
}
finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    Write-Host "[test-local-secrets] Fixture: $root"
}
