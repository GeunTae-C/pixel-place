# 실제 helper의 parameter/기존 증거 guard와 회귀 입력 함수만 격리 검증. DB 기동 없음
param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'phase15-inputs.ps1')
if (Test-Path -LiteralPath $Evidence) { throw 'Tool evidence already exists' }
New-Item -ItemType Directory -Path $Evidence | Out-Null
$checks=[Collections.Generic.List[string]]::new()
foreach ($name in @('start-phase15-c-mysql.ps1','stop-phase15-c-mysql.ps1','test-phase15.ps1','phase15-inputs.ps1','test-phase15-c5-tools.ps1')) {
    $parseErrors=$null; $tokens=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw "Parse failed: $name" }
    $checks.Add("parse:$name")
    if ($name -notmatch '^(start|stop)-') { continue }
    $parameter=[scriptblock]::Create($ast.ParamBlock.Extent.Text + "`n return `$RunPrefix")
    foreach ($prefix in @('phase15-C-1-20260922','phase15-C-2-20260922','phase15-C-3-20260922',
                          'phase15-C-4-20260923','phase15-C-5-20260924-110905405','phase15-C-5-20260924-110905406')) {
        if ((& $parameter -RunPrefix $prefix) -cne $prefix) { throw 'Valid prefix rejected' }
        $checks.Add("prefix-valid:$name/$prefix")
    }
    foreach ($prefix in @('phase15-C-6-20260924','phase15-C-5-20260924-123','../phase15-C-5-20260924',
                          'phase15-C-5-20260924/child','arbitrary','phase15-C-5-20260924-1234567890')) {
        $rejected=$false
        try { & $parameter -RunPrefix $prefix | Out-Null } catch { $rejected=$true }
        if (-not $rejected) { throw 'Invalid prefix accepted' }
        $checks.Add("prefix-invalid:$name/$prefix")
    }
    $guards=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and
        $node.Extent.Text -match '^if \(Test-Path -LiteralPath \$evidence\)'},$true))
    if ($guards.Count -ne 1) { throw 'Exact evidence reuse guard missing' }
    $evidence=$Evidence
    $rejected=$false
    try { & ([scriptblock]::Create($guards[0].Extent.Text)) } catch { $rejected=$true }
    if (-not $rejected) { throw 'Existing evidence accepted' }
    $checks.Add("existing-evidence:$name")
}
$fixture=Join-Path $Evidence 'input-fixture'
New-Item -ItemType Directory -Path "$fixture/src","$fixture/scripts" | Out-Null
$label='phase15-C-5-20260924-110905405'
foreach ($path in Get-Phase15InputPaths $fixture $label) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    [IO.File]::WriteAllText($path,'fixture')
}
$source=Join-Path $fixture 'src/sample.java'
[IO.File]::WriteAllText($source,'before')
$before=@(Get-Phase15ExecutionInputs $fixture $label)
if (-not (Compare-Phase15ExecutionInputs $before @(Get-Phase15ExecutionInputs $fixture $label)).Exact) { throw 'Unchanged input rejected' }
$checks.Add('inputs:unchanged')
$added=Join-Path $fixture 'src/application-stub.yml'
[IO.File]::WriteAllText($added,'new')
$difference=Compare-Phase15ExecutionInputs $before @(Get-Phase15ExecutionInputs $fixture $label)
if ($difference.Exact -or $difference.Added.Count -ne 1) { throw 'Input addition missed' }
$checks.Add('inputs:added-config')
# 새로 만든 fixture 파일만 삭제. 원본·과거 증거에는 접근하지 않음
Remove-Item -LiteralPath $added
Remove-Item -LiteralPath $source
$difference=Compare-Phase15ExecutionInputs $before @(Get-Phase15ExecutionInputs $fixture $label)
if ($difference.Exact -or $difference.Removed.Count -ne 1) { throw 'Input deletion missed' }
$checks.Add('inputs:removed')
[IO.File]::WriteAllText($source,'after')
$difference=Compare-Phase15ExecutionInputs $before @(Get-Phase15ExecutionInputs $fixture $label)
if ($difference.Exact -or $difference.Changed.Count -ne 1) { throw 'Input modification missed' }
$checks.Add('inputs:modified')
[IO.File]::WriteAllText($source,'before')
[IO.File]::WriteAllText((Join-Path $fixture 'scripts/report-phase15-c5.py'),'new analyzer')
$difference=Compare-Phase15ExecutionInputs $before @(Get-Phase15ExecutionInputs $fixture $label)
if ($difference.Exact -or $difference.Added.Count -ne 1) { throw 'New C5 analyzer missed' }
$checks.Add('inputs:added-tool')
[pscustomobject]@{Checks=$checks;Count=$checks.Count;Exit=0;DatabaseStarted=$false} |
    ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath "$Evidence/result.json"
Write-Output "C5_TOOL_CHECKS=$($checks.Count) exit=0"
