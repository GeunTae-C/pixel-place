param([Parameter(Mandatory=$true)][ValidateSet('summary','shutdown','graph','audit')][string]$Stage)
$ErrorActionPreference = 'Stop'
$phase15Root = 'E:/pixel-place-phase15/phase15-a-20260918'
$env:TEMP = "$phase15Root/tmp"
$env:TMP = $env:TEMP
$env:PYTHONDONTWRITEBYTECODE = '1'
$phase15Python = 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
$completedPath = "$phase15Root/agent-runs/phase15-A-invoke_20260917_161451_711/completed-trials.json"
$completed = Get-Content -LiteralPath $completedPath -Raw | ConvertFrom-Json
if ($completed.Count -ne 21 -or @($completed | Where-Object { $_.TrialExit -ne 0 -or $_.RecoveryExit -ne 0 }).Count -ne 0) {
    throw 'All fixed 21 trials and recoveries must finish before finalization'
}

# 고정 21회 종료 후에만 실행하는 증거 집계·소유 인스턴스 종료·AST 갱신 경계
switch ($Stage) {
    'summary' {
        & $phase15Python scripts/summarize-phase15.py --output "$phase15Root/agent-runs/phase15-A-measurement-summary.json"
    }
    'shutdown' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/stop-phase15-mysql.ps1 }
    'graph' { & graphify update . }
    'audit' { & $phase15Python scripts/audit-phase15.py }
}
if ($LASTEXITCODE -ne 0) { throw "Phase15 finalization stage failed: $Stage, exit=$LASTEXITCODE" }
