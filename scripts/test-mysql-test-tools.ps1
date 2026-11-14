# lifecycle의 실행부 없이 guard만 검증. 실제 DB 정상 경로는 별도 비상승 smoke가 담당
param([Parameter(Mandatory=$true)][string]$EvidenceRoot)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'phase18-cap-process.ps1')
$repo=Split-Path $PSScriptRoot -Parent
$descriptor=Join-Path $PSScriptRoot 'mysql-test-instance.json'
$instance=Get-Content -Raw -LiteralPath $descriptor | ConvertFrom-Json
$root=$instance.root;$config=$instance.config;$binary=$instance.binary
$run=$EvidenceRoot;$large='F:/pixel-place-work/sessions/tool-guards';$minimumFree=10GB;$MySqlGrowthBytes=2GB;$EvidenceBytes=64MB
. (Join-Path $PSScriptRoot 'pixel-place-work.ps1')
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/mysql-test-instance.ps1",[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Parser failure'}
foreach($function in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)){
    Invoke-Expression $function.Extent.Text
}
$results=[Collections.Generic.List[string]]::new()
function Rejected([string]$Name,[scriptblock]$Work,[string]$Message) {
    $caught=$false
    try{& $Work}catch{if($_.Exception.Message -notlike "*$Message*"){throw};$caught=$true}
    if(-not $caught){throw "Guard accepted invalid input: $Name"};$results.Add($Name)
}
Assert-Instance;$results.Add('current-F-instance-hash-uuid')
Rejected 'relative-path' {Assert-LocalPath 'mysql/data'} 'absolute'
Rejected 'path-traversal' {Assert-LocalPath 'F:/MySQL/test/../main'} 'Normalized'
Rejected 'ads-path' {Assert-LocalPath 'F:/MySQL/test/data:other'} 'absolute'
# .NET이 NUL을 장치 경로로 정규화하므로 원문과 정규화 결과의 불일치에서 거부됨
Rejected 'windows-alias' {Assert-LocalPath 'F:/MySQL/test/NUL'} 'Normalized'
$old=$instance.uuid;$instance.uuid='00000000-0000-0000-0000-000000000000'
try{Rejected 'wrong-uuid' {Assert-Instance} 'UUID'}finally{$instance.uuid=$old}
$old=$instance.configSha256;$instance.configSha256='0'*64
try{Rejected 'wrong-config-hash' {Assert-Instance} 'hash'}finally{$instance.configSha256=$old}
$old=$root;$root='E:/pixel-place-phase15/phase15-a-20260918/mysql'
try{Rejected 'old-E-root' {Assert-Instance} 'descriptor'}finally{$root=$old}
$baseline=Get-Bytes $root;$budget=Get-Budget $baseline
if(@($budget.Volumes|Where-Object Drive -eq 'F:').Count -ne 1 -or $budget.DatabaseBytes -le 0){throw 'F accounting absent'}
if(($budget.Volumes.Drive -join ',') -ne 'C:,F:'){throw 'Only actual C/F volumes required'}
$results.Add('real-F-usage-and-free-space')
Rejected 'database-growth-limit' {Get-Budget (-$MySqlGrowthBytes-1)} 'capacity'
$old=$minimumFree;$minimumFree=[long]::MaxValue
try{Rejected 'low-free-space' {Get-Budget $baseline} 'free-space'}finally{$minimumFree=$old}
$old=$EvidenceBytes;$EvidenceBytes=1
try{Rejected 'evidence-capacity-limit' {Get-Budget $baseline} 'capacity'}finally{$EvidenceBytes=$old}
Rejected 'stale-session-descriptor' {Get-Live @{DescriptorHash='0'*64;RunId='old'}} 'descriptor'
$startPath=Join-Path $run 'mysql-start.json'
if(Test-Path $startPath){
    $RunId=Split-Path $run -Leaf;$start=Get-Content -Raw -LiteralPath $startPath | ConvertFrom-Json
    $start.StartUtc='2000-01-01T00:00:00.0000000Z'
    Rejected 'recycled-pid-old-start-time' {Get-Live $start} 'PID/start'
}
$path=Join-Path $run 'create-new-guard.json';Save-NewJson $path @{preserve='first'}
$before=(Get-FileHash $path).Hash
Rejected 'evidence-overwrite' {Save-NewJson $path @{preserve='second'}} 'already exists'
if((Get-FileHash $path).Hash -ne $before){throw 'Evidence changed'}
Save-NewJson (Join-Path $run 'tool-guards.json') @{Passed=$results.Count;Cases=@($results);Budget=$budget;DatabaseMutation=$false}
Write-Output "MYSQL_TOOL_GUARDS_PASS count=$($results.Count)"
