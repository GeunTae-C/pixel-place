param([string]$TaskFile = '.\명령문\013-A_기반_실행명령문.md')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness-evidence.ps1')
$count = 0
function Assert-Classification {
    param([string]$Name, [string]$Path, [byte[]]$Bytes, [string]$Expected)
    $actual = Get-EvidenceTextReason $Path $Bytes
    if ($actual -cne $Expected) { throw "$Name expected=$Expected actual=$actual" }
    $script:count++
    Write-Host "[test-evidence-task-entry] PASS $Name"
}
$cases = @(
    @('jdbc-label', 'jdbc:', 'text'),
    @('jdbc-documentation', '설명에 `jdbc:` 또는 인증 문자열이 있습니다.', 'text'),
    @('jdbc-quoted-label', '"jdbc:" 표기', 'text'),
    @('jdbc-url', 'jdbc:mysql://localhost:3306/fixture', 'text'),
    @('jdbc-credentials', 'jdbc:mysql://localhost/fixture?password=fixture-only', 'sensitive-content'),
    @('jdbc-unknown', 'jdbc:opaque-fixture', 'sensitive-content'),
    @('jdbc-uppercase', 'JDBC:MYSQL://localhost/fixture', 'text'),
    @('password', 'password: fixture-only', 'sensitive-content'),
    @('bearer', 'Authorization: Bearer fixture-only', 'sensitive-content'),
    @('private-key', '-----BEGIN PRIVATE KEY-----', 'sensitive-content')
)
foreach ($case in $cases) {
    Assert-Classification $case[0] 'task.md' ([Text.Encoding]::UTF8.GetBytes($case[1])) $case[2]
}
Assert-Classification 'secret-name' '.env' ([Text.Encoding]::UTF8.GetBytes('fixture')) 'sensitive-name'
Assert-Classification 'binary' 'task.md' ([byte[]]@(65, 0, 66)) 'binary'
Assert-Classification 'invalid-utf8' 'task.md' ([byte[]]@(255, 254)) 'non-utf8'
Assert-Classification 'size-limit' 'task.md' (New-Object byte[] 2097153) 'over-2MiB'
if (-not (Test-Path -LiteralPath $TaskFile -PathType Leaf)) { throw 'TaskFile does not exist; pass -TaskFile with the real instruction path.' }
$resolved = (Resolve-Path -LiteralPath $TaskFile).Path
Assert-Classification 'real-task' $resolved ([IO.File]::ReadAllBytes($resolved)) 'text'
Write-Host "[test-evidence-task-entry] PASS: $count checks (classification only; no Writer/API/DB execution)"
