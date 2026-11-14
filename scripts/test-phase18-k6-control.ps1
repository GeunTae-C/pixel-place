# 실제 schema4 control 경로의 한 번 정한 clock·timeout·STOP 경계. DB/운영 서비스 사용 없음.
param([string]$Output,[string]$Bridge)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/pixel-place-work.ps1"
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/phase18-k6-process.ps1"
Assert-PixelPlaceExecutionEnvironment
$root=$Output;$clock=New-CapClock ([Math]::Min([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+20000,[long]$env:PIXEL_PLACE_CAP_EXPIRES))
$captures=[Collections.Generic.List[object]]::new();$env:PIXEL_PLACE_BENCH_JWT_KEY='isolated-control-fixture'
$inputData=[pscustomobject]@{bridge=$Bridge;limits=@{};plan=@{schemaVersion=4;cases=@(@{workload=@{drainMillis=100}})}}
$checks=@()
try {
 $ok=Invoke-Bridge '/ok';if(-not $ok.done){throw 'Actual control response missing'}
 $caught=$null;try{$null=Invoke-Bridge '/bad'}catch{$caught=$_.Exception.Message}
 if($caught -cne 'required bridge operation failed'){throw "Non200 accepted: $caught"}
 $checks+=@{Case='non200';Failure=$caught}
 $began=[Diagnostics.Stopwatch]::StartNew();$caught=$null
 try{$null=Invoke-Bridge '/finish'}catch{$caught=$_.Exception.Message};$began.Stop()
 if(-not $caught -or $began.ElapsedMilliseconds -gt 2000){throw 'Bounded HttpClient timeout not enforced'}
 $checks+=@{Case='timeout';Failure=$caught;ElapsedMillis=$began.ElapsedMilliseconds;FrozenOperationMillis=100}
 $inputData.limits.metricsBytes=1L;$inputData.plan.phase='CAP';$inputData.plan.bounds=@{outputBytes=1L}
 $caught=$null;try{$null=Invoke-Bridge '/state'}catch{$caught=$_.Exception.Message}
 if($caught -cne 'k6_total_output_limit'){throw 'Expected output failure before control'}
 $stop=Invoke-Bridge '/stop';if(-not $stop.done){throw 'Output failure blocked actual STOP'}
 $checks+=@{Case='output-failure-stop';FirstFailure=$caught;StopReceived=$true}
 Save-CapJson "$root/results.json" @{Complete=$true;Checks=$checks;OriginalExpires=$clock.Expires;Services=0;NativeChildren=0}
}finally{if($script:k6BridgeClient){$script:k6BridgeClient.Dispose();$script:k6BridgeClient=$null}}
