param(
    [Parameter(Mandatory=$true)][ValidateSet('Plan','VerifyTools','VerifyTransport','VerifyObservation','Pilot','Run','ProduceK6','Analyze','Close')][string]$Action,
    [Parameter(Mandatory=$true)][string]$PlanPath,
    [Parameter(Mandatory=$true)][string]$Java,
    [Parameter(Mandatory=$true)][string]$ClasspathFile,
    [string]$EnvironmentFile,
    [string]$ObservationPolicy,
    [string]$K6
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'phase18-cap-process.ps1')
$null=Get-CapOperationMillis
. (Join-Path $PSScriptRoot 'pixel-place-work.ps1')
# 미구현 동작은 경로 읽기·파일 생성·JVM 시작 전에 거부
if($Action -notin @('Plan','VerifyTools','VerifyTransport','VerifyObservation','Pilot','Run','ProduceK6')){throw 'Action unavailable'}
Assert-PixelPlaceExecutionEnvironment
foreach($path in @($PlanPath,$Java,$ClasspathFile)) {
    Assert-PixelPlaceWorkPath $path
    if($path -notmatch '\A[A-Za-z]:[\\/]' -or -not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Existing absolute file required'}
}
$phase18Classpath=[IO.File]::ReadAllText($ClasspathFile).Trim()
if($phase18Classpath.Contains("`r") -or $phase18Classpath.Contains("`n")){throw 'Invalid classpath file'}
foreach($path in $phase18Classpath.Split(';')){Assert-PixelPlaceWorkPath $path}
$phase18Plan=Get-Content -LiteralPath $PlanPath -Raw | ConvertFrom-Json
$expectedEvidence=Get-PixelPlaceEvidencePath $phase18Plan.sessionId
if([IO.Path]::GetFullPath($phase18Plan.ownership.evidenceRoot) -ine [IO.Path]::GetFullPath($expectedEvidence)){throw 'New run evidence must own its F session'}
Assert-PixelPlaceEvidencePath $PlanPath
Assert-PixelPlaceEvidencePath $ClasspathFile
foreach($path in @($phase18Plan.ownership.temp,$phase18Plan.ownership.jna)){Assert-PixelPlaceWorkPath $path}
$env:TEMP=$phase18Plan.ownership.temp;$env:TMP=$env:TEMP
# 직접 JVM 인자 배열 사용. 명령 문자열이나 Invoke-Expression으로 조립하지 않음
$phase18Extra=@()
if($env:PIXEL_PLACE_CAP_EXPIRES){$phase18Extra+="-Dphase18.deadlineEpochMillis=$env:PIXEL_PLACE_CAP_EXPIRES"}
if($Action -in @('VerifyTransport','VerifyObservation','Pilot','Run','ProduceK6')) {
    if($EnvironmentFile -notmatch '\A[A-Za-z]:[\\/]' -or -not(Test-Path -LiteralPath $EnvironmentFile -PathType Leaf)){throw 'Explicit A-2 environment required'}
    $phase18Extra+="-Dphase18.environment=$EnvironmentFile"
}
if($Action -in @('VerifyObservation','Pilot','Run','ProduceK6')) {
    if($ObservationPolicy -notmatch '\A[A-Za-z]:[\\/]' -or -not(Test-Path -LiteralPath $ObservationPolicy -PathType Leaf)){throw 'Explicit A-3 observation policy required'}
    $phase18Extra+="-Dphase18.observationPolicy=$ObservationPolicy"
}
if($Action -eq 'ProduceK6'){
    Assert-PixelPlaceWorkPath $K6
    if(-not(Test-Path -LiteralPath $K6 -PathType Leaf)){throw 'Pinned k6 executable required'}
    $phase18Extra+="-Dphase18.k6=$K6"
}
$phase18NativePreference=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    $null=Get-CapOperationMillis
    & $Java '-Xmx128m' "-Djava.io.tmpdir=$($phase18Plan.ownership.temp)" "-Djna.tmpdir=$($phase18Plan.ownership.jna)" "-Dphase18.runtimeReceipt=$ClasspathFile.json" @phase18Extra '-cp' $phase18Classpath 'dev.cgt.benchmark.Phase18Main' '--action' $Action '--plan' $PlanPath
    $phase18JavaExit=$LASTEXITCODE
} finally {$ErrorActionPreference=$phase18NativePreference}
if($phase18JavaExit -ne 0){throw "Phase18 JVM exit $phase18JavaExit"}
