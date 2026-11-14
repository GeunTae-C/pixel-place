# 승인 연결·상한·overflow·정적 입력 검증. 부하와 DB 기동 책임 없음
$ErrorActionPreference='Stop'
foreach($file in @('phase15-c-budget.ps1','measure-phase15.ps1','test-phase15.ps1','start-phase15-c-mysql.ps1','stop-phase15-c-mysql.ps1')) {
    $tokens=$null; $errors=$null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors) | Out-Null
    if($errors.Count){throw 'PowerShell syntax failed'}
}
. (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
$budget=Get-Content -Raw 'scripts/phase15-c-budget.json' | ConvertFrom-Json
Assert-Phase15CBudget $budget
if(-not (Test-Phase15CBudget 74GB 0 5GB) -or -not (Test-Phase15CBudget (74GB-1) 1 (5GB+1)) -or
   (Test-Phase15CBudget 74GB 1 (5GB+1)) -or (Test-Phase15CBudget 36960813040L ([decimal][long]::MaxValue) ([decimal][long]::MaxValue)) -or
   (Test-Phase15CBudget 36960813040L 1 5GB) -or (Test-Phase15CBudget 36960813039L 0 10GB) -or
   (Test-Phase15CBudget 36960813040L -1 10GB)) { throw 'Budget arithmetic boundary failed' }
$budget.maximumBytes++
$rejected=$false
try { Assert-Phase15CBudget $budget } catch { $rejected=$true }
if(-not $rejected){throw 'Altered approval accepted'}
$spec=Get-Content -Raw 'scripts/phase15-c4-input.json' | ConvertFrom-Json
if($spec.maxTotalBytes -ne 74GB){throw 'C4 spec approval mismatch'}
$old=Get-Content -Raw 'scripts/phase15-c-input.json' | ConvertFrom-Json
if($old.maxTotalBytes -ne 36GB){throw 'Frozen C3 input changed'}
Write-Output 'PS_SYNTAX_AND_BUDGET_BOUNDARIES_PASS'
