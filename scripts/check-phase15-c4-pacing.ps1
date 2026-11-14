# 동결 측정 종료 뒤의 무DB 대기 진단. OS timer 설정이나 실제 후보 코드를 변경하지 않음
param([ValidatePattern('^phase15-C-4-[0-9]{8}-pacing-r[1-9][0-9]*$')][string]$Label='phase15-C-4-20260923-pacing-r1')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
$before=Save-Phase15CCharge
if (-not (Test-Phase15CBudget $before.chargedBytes 64MB $before.freeBytes)) { throw 'Pacing probe budget insufficient' }
$root='E:/pixel-place-phase15/phase15-a-20260918'
$output=Join-Path $root ('agent-runs/'+$Label)
if (Test-Path -LiteralPath $output) { throw 'Pacing evidence already exists' }
New-Item -ItemType Directory -Path $output | Out-Null
$before | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/budget-before.json"
$env:TEMP="$root/tmp"; $env:TMP=$env:TEMP
$ErrorActionPreference='Continue'
& 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' '-Xmx128m' "-Djava.io.tmpdir=$root/tmp" scripts/Phase15C4PacingAudit.java 2>&1 | Tee-Object -FilePath "$output/pacing.jsonl"
$probeExit=$LASTEXITCODE
$ErrorActionPreference='Stop'
$probeExit | Set-Content -Encoding UTF8 -LiteralPath "$output/exit-code.txt"
Copy-Item -LiteralPath scripts/Phase15C4PacingAudit.java -Destination "$output/Phase15C4PacingAudit.java"
Save-Phase15CCharge | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/budget-after.json"
if ($probeExit -ne 0) { throw 'Pacing probe failed; preserve evidence' }
Write-Output "C4_PACING_PROBE_COMPLETE evidence=$output"
