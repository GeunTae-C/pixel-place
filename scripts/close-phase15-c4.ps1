# 완료된 수동 검토의 종료 증거만 저장. 부하/회귀/DB 기동 및 Git 변경 없음
$ErrorActionPreference = 'Stop'
$ownedRoot = 'E:/pixel-place-phase15/phase15-a-20260918'
$closing = "$ownedRoot/agent-runs/phase15-C-4-20260923-closing-r1"
. (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
$before = Get-Phase15CCharge
if (-not (Test-Phase15CBudget $before.chargedBytes 64MB $before.freeBytes)) { throw 'Closing evidence budget failed' }
if (Test-Path -LiteralPath $closing) { throw 'Closing evidence exists; inspect instead of overwriting' }
New-Item -ItemType Directory -Path $closing | Out-Null
$before | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$closing/budget-before.json"
$entry = Get-Content -Raw -Encoding UTF8 -LiteralPath "$ownedRoot/agent-runs/phase15-C-4-entry-20260922T165438189125Z/processes-and-ownership.json" | ConvertFrom-Json
$original = @($entry.Processes | Where-Object { $_.Pid -in 4804,5216,6860 })
$verified = @(foreach ($prior in $original) {
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($prior.Pid)"
    if (-not $current -or $current.ParentProcessId -ne $prior.ParentPid -or $current.ExecutablePath -ne $prior.Executable -or $current.CreationDate.ToUniversalTime().ToString('o') -ne $prior.Started) { throw 'Original service identity changed' }
    [pscustomobject]@{Pid=$current.ProcessId;ParentPid=$current.ParentProcessId;StartedUtc=$current.CreationDate.ToUniversalTime().ToString('o');Executable=$current.ExecutablePath}
})
$java = @(Get-Process java -ErrorAction SilentlyContinue)
$port = @(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue)
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 3306,6379 | Select-Object LocalPort,OwningProcess)
if ($java.Count -ne 0 -or $port.Count -ne 0) { throw 'Work still running; completion prohibited' }
[pscustomobject]@{SavedUtc=[DateTime]::UtcNow.ToString('o');JavaCount=$java.Count;Port3307Count=$port.Count;OriginalProcessIdentityPreserved=$true;OriginalProcesses=$verified;OriginalListeners=$listeners} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath "$closing/processes.json"
$env:PYTHONDONTWRITEBYTECODE='1'
$env:TEMP="$ownedRoot/tmp"; $env:TMP=$env:TEMP
$ErrorActionPreference='Continue'
& 'C:/Users/cgt/.local/bin/graphify.exe' update . 2>&1 | Out-File -Encoding UTF8 -LiteralPath "$closing/graphify.log"
$graphExit=$LASTEXITCODE
$ErrorActionPreference='Stop'
[pscustomobject]@{ExitCode=$graphExit;SavedUtc=[DateTime]::UtcNow.ToString('o');Mode='incremental AST';Evidence='graphify.log';NotExecutionEvidence=$true} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$closing/graphify-verification.json"
if ($graphExit -ne 0) { throw 'Graphify update failed' }
Copy-Item -LiteralPath $PSCommandPath -Destination "$closing/close-phase15-c4.ps1"
& 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe' -B scripts/close-phase15-c4.py
$result=$LASTEXITCODE
$result | Set-Content -Encoding UTF8 -LiteralPath "$closing/exit-code.txt"
if ($result -ne 0) { throw 'Closing facts verification failed; preserve evidence' }
Write-Output 'PHASE15_C4_COMPLETE_SINGLE_TARGET_MISSED'
