# 완료된 고정 비교의 분석 증거 저장. 부하·회귀 기동이나 기존 증거 변경 책임 없음
param([ValidatePattern('^phase15-C-4-[0-9]{8}-analysis-r[1-9][0-9]*$')][string]$Label='phase15-C-4-20260923-analysis-r2',
    [ValidatePattern('^[a-z0-9_]+$')][string]$Session='c4r220260923',
    [ValidatePattern('^phase15-C-4-[0-9]{8}-candidate-r[1-9][0-9]*$')][string]$Candidate='phase15-C-4-20260923-candidate-r2')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
$before=Save-Phase15CCharge
if (-not (Test-Phase15CBudget $before.chargedBytes 256MB $before.freeBytes)) { throw 'Analysis budget insufficient' }
$owned='E:/pixel-place-phase15/phase15-a-20260918'
$output=Join-Path $owned ('agent-runs/'+$Label)
if (Test-Path -LiteralPath $output) { throw 'Analysis evidence already exists' }
$frozen=Get-Content -Raw -Encoding UTF8 -LiteralPath "$owned/agent-runs/$Candidate/candidate.json" | ConvertFrom-Json
$items=@($frozen.executionInputs.PSObject.Properties)
if ($items.Count -ne $frozen.inputCount -or $frozen.measurementSession -cne $Session) { throw 'Frozen comparison input identity changed' }
foreach ($item in $items) {
    if (-not (Test-Path -LiteralPath $item.Name) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $item.Name).Hash -ine $item.Value) {
        throw 'Current comparison input differs from frozen candidate before final selection'
    }
}
New-Item -ItemType Directory -Path $output | Out-Null
New-Item -ItemType Directory -Path "$output/tool-source" | Out-Null
$toolHashes=@(foreach ($tool in @('report-phase15-c4.py','render-phase15-c4.py','save-phase15-c4-results.ps1',
    'summarize-phase15.py','test-phase15-analysis.py','inspect-phase15-c4.py')) {
    $source=Join-Path $PSScriptRoot $tool
    $saved=Join-Path "$output/tool-source" $tool
    Copy-Item -LiteralPath $source -Destination $saved
    $hash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $saved -Algorithm SHA256).Hash -cne $hash) { throw 'Analysis tool copy differs' }
    [pscustomobject]@{Name=$tool;Sha256=$hash}
})
$toolHashes | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/tool-source/hashes.json"
New-Item -ItemType Directory -Path "$output/before-final/docs" | Out-Null
foreach ($document in @('pixel-place-details.md','pixel-place-specs.md')) {
    Copy-Item -LiteralPath (Join-Path 'docs' $document) -Destination (Join-Path "$output/before-final/docs" $document)
}
# 최종 bootJar가 기존 build 파일을 대체하기 전에 C3의 검증된 production artifact 보존
$priorJar=Join-Path $owned 'build/libs/pixel-place-0.0.1-SNAPSHOT.jar'
$priorJarHash=(Get-FileHash -LiteralPath $priorJar -Algorithm SHA256).Hash
if ($priorJarHash -ine 'df240c1896eba0150c9837c1653f0289c84b78b35fc7bd0a266cebb64fab479a') { throw 'C3 artifact identity changed before archival' }
$savedJar=Join-Path "$output/before-final" 'preserved-c3-boot.jar'
Copy-Item -LiteralPath $priorJar -Destination $savedJar
if ((Get-FileHash -LiteralPath $savedJar -Algorithm SHA256).Hash -cne $priorJarHash) { throw 'Preserved C3 artifact copy differs' }
[pscustomobject]@{Source=$priorJar;Sha256=$priorJarHash;Evidence='phase15-C-3-20260922-regression-r3';Scope='C3 production artifact; benchmark code excluded'} |
    ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/before-final/preserved-c3-boot.json"
$fullDiff=@(& git -c core.quotepath=false -c core.safecrlf=false diff --)
if ($LASTEXITCODE -ne 0) { throw 'Pre-selection Git diff capture failed' }
[IO.File]::WriteAllText("$output/before-final/full.diff",($fullDiff -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$gitStatus=@(& git -c core.quotepath=false status --short --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw 'Pre-selection Git status capture failed' }
[IO.File]::WriteAllText("$output/before-final/status.txt",($gitStatus -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$before | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/budget-before.json"
$pythonExe='C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
$ErrorActionPreference='Continue'
& $pythonExe -B scripts/report-phase15-c4.py --session $Session --candidate $Candidate --output "$output/report.json" 2>&1 | Tee-Object -FilePath "$output/analysis.log"
$analysisExit=$LASTEXITCODE
$ErrorActionPreference='Stop'
$analysisExit | Set-Content -Encoding UTF8 -LiteralPath "$output/analysis-exit.txt"
if ($analysisExit -ne 0) {
    Save-Phase15CCharge | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/budget-after-failure.json"
    throw 'Independent comparison analysis failed; preserve evidence'
}
$ErrorActionPreference='Continue'
& $pythonExe -B scripts/render-phase15-c4.py "$output/report.json" "$output/comparison.md" 2>&1 | Tee-Object -FilePath "$output/render.log"
$renderExit=$LASTEXITCODE
$ErrorActionPreference='Stop'
$renderExit | Set-Content -Encoding UTF8 -LiteralPath "$output/render-exit.txt"
if ($renderExit -ne 0) {
    Save-Phase15CCharge | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/budget-after-failure.json"
    throw 'Comparison table rendering failed; preserve evidence'
}
Save-Phase15CCharge | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$output/budget-after.json"
Write-Output "C4_ANALYSIS_COMPLETE evidence=$output"
