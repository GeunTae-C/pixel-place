# 실제 Windows Job 후손을 쓰는 유한 무서비스 대역. 준비 성공과 정상 정리 자격 분리 검증
param([Parameter(Mandatory=$true)][string]$Output,[switch]$BeforeFix,[switch]$OnlyUnstarted)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/phase18-cap-process.ps1"
$repo=Split-Path $PSScriptRoot -Parent
$python='C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
$servicePython=& $python -B -c 'import sys;print(sys._base_executable)'
New-Item -ItemType Directory -Path $Output -ErrorAction Stop|Out-Null
$fixtureRoot=Join-Path $env:TEMP ("cleanup-"+[IO.Path]::GetFileName($Output));New-Item -ItemType Directory $fixtureRoot -ErrorAction Stop|Out-Null
$finite="$fixtureRoot/finite.py"
[IO.File]::WriteAllText($finite,@'
import os,pathlib,sys,time
stop,ready,done=map(pathlib.Path,sys.argv[1:4])
ready.write_text(str(os.getpid()))
until=time.monotonic()+20
while not stop.exists() and time.monotonic()<until:time.sleep(.02)
done.write_text('natural exit')
'@)
$starter="$fixtureRoot/start-fixture.ps1"
$body=@'
param($Python,$Finite,$Stop,$Ready,$Done,$Case,$Helper)
. $Helper
$argv='"'+$Finite+'" "'+$Stop+'" "'+$Ready+'" "'+$Done+'"'
$p=[Phase18Cap.JobCapture]::StartService($Python,$argv,(Get-Location).Path,"$Ready.out","$Ready.err")
while(-not(Test-Path $Ready)){$null=Get-CapOperationMillis;Start-Sleep -Milliseconds 20}
if($Case -eq 'unknown-descendant'){
 $argv='"'+$Finite+'" "'+$Stop+'" "'+$Ready+'.extra" "'+$Done+'.extra"'
 $extra=[Phase18Cap.JobCapture]::StartService($Python,$argv,(Get-Location).Path,"$Ready.extra.out","$Ready.extra.err")
}
$id=@{Pid=$p.Id;StartUtc=$p.StartTime.ToUniversalTime().ToString('o');Binary=$p.Path;BinarySha256=(Get-FileHash $p.Path).Hash}
if($Case -eq 'changed-start'){$id.StartUtc='2000-01-01T00:00:00.0000000Z'}
$hash=(Get-FileHash $Ready).Hash
if($Case -eq 'changed-receipt'){$hash='0'*64}
$suffix=if($Case -eq 'handoff-save-failure'){'service'}else{'cleanup-service'}
Save-CapJson "$env:PIXEL_PLACE_CAP_REQUEST.$suffix.json" @{Role='mysql3307';Ready=($suffix -eq 'service');Purpose='normal-stop-only';Request=$env:PIXEL_PLACE_CAP_REQUEST;ReadinessReceipt=$Ready;ReadinessHash=$hash;Processes=@($id)}
if($Case -ne 'handoff-save-failure'){throw 'synthetic probe failure'}
'@
[IO.File]::WriteAllText($starter,$body,[Text.UTF8Encoding]::new($true))
# 실패 존재만으로 통과하지 않고 실제 정상 인계 저장 호출·예외·최초 실패 연결 확인
function Assert-NormalHandoffSaveFailure($Observation,[string]$ExpectedPath,[string]$FirstFailure) {
 if($Observation.Calls -ne 1 -or $Observation.Path -cne $ExpectedPath -or -not $Observation.RealSaveCalled -or -not $Observation.ExceptionObserved){
  throw 'Normal handoff save was not reached at the expected path'
 }
 if(-not $Observation.FileSystemFailure){throw 'Normal handoff save did not report a filesystem exception'}
 if($FirstFailure -cne ('CAP service handoff failed: '+$Observation.ExceptionMessage)){
  throw 'First failure does not match the observed normal handoff save exception'
 }
}
$normalHandoffSave=${function:Save-CapJson}
$results=@()
if(-not $BeforeFix -and -not $OnlyUnstarted){
foreach($case in @('probe-failure','handoff-save-failure','unknown-descendant','changed-start','changed-receipt')){
 $registry=[Collections.Generic.List[object]]::new();$first=$null;$released=$false
 $stop="$Output/$case.stop";$ready="$Output/$case.ready";$done="$Output/$case.done"
 $clock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+30000)
 $req="$Output/$case.request.json"
 if($case -eq 'handoff-save-failure'){New-Item -ItemType Directory "$req.handoff.json"|Out-Null}
 try{
  $o=Start-CapWorker @{Kind='script';Repo=$repo;File=$starter;ServiceRole='mysql3307';ExpiresEpochMillis=$clock.Expires;OriginalExpiresEpochMillis=$clock.Expires;Output="$Output/$case.exit.json";Parameters=@{Python=$servicePython;Finite=$finite;Stop=$stop;Ready=$ready;Done=$done;Case=$case;Helper="$PSScriptRoot/phase18-cap-process.ps1"}} $req $clock $registry
  if($case -eq 'handoff-save-failure'){
   # 종료 직후 남은 콘솔도 기다림. 고정 지연/후손 무시 없이 같은 기존 clock 안에서 준비
   $prepared=$null
   while((Get-CapRemaining $clock) -gt 0){
    if(Test-CapWorkerExit $o){
     Test-CapStreams $o
     if($o.Failure -or $o.Capture.Process.ExitCode -ne 0){throw 'Normal handoff fixture preparation failed: worker or stream'}
     $offer=Get-Content -LiteralPath "$req.service.json" -Raw -Encoding UTF8|ConvertFrom-Json
     if($offer.Role -cne 'mysql3307' -or $offer.Ready -ne $true -or $offer.Request -cne $req -or @($offer.Processes).Count -ne 1 -or $offer.ReadinessReceipt -cne $ready -or (Get-FileHash -LiteralPath $ready).Hash -ine $offer.ReadinessHash){
      throw 'Normal handoff fixture preparation failed: offer or receipt'
     }
     $identity=$offer.Processes[0]
     $service=Get-Process -Id $identity.Pid -ErrorAction Stop
     if([int][IO.File]::ReadAllText($ready) -ne $identity.Pid -or $service.StartTime.ToUniversalTime().ToString('o') -cne $identity.StartUtc -or $service.Path -ine $identity.Binary -or $service.Path -ine $servicePython -or (Get-FileHash -LiteralPath $service.Path).Hash -ine $identity.BinarySha256){
      throw 'Normal handoff fixture preparation failed: service identity'
     }
     $members=@($o.Capture.Members())
     if($members.Count -eq 1 -and $members[0] -eq $identity.Pid){
      $prepared=@{WorkerExited=$true;WorkerExit=$o.Capture.Process.ExitCode;OutCompleted=$o.Capture.OutTask.IsCompleted;ErrCompleted=$o.Capture.ErrTask.IsCompleted;Members=$members;Service=$identity;OfferPath="$req.service.json";Expires=$clock.Expires;Utc=[DateTime]::UtcNow.ToString('o')}
      break
     }
    }
    Start-Sleep -Milliseconds ([int][Math]::Min(20,(Get-CapRemaining $clock)))
   }
   if(-not $prepared -or (Get-CapRemaining $clock) -le 0){throw 'Normal handoff fixture preparation deadline exhausted'}
   $normalHandoffPath="$req.handoff.json"
   $normalSaveObservation=@{Calls=0;Path=$null;RealSaveCalled=$false;ExceptionObserved=$false;FileSystemFailure=$false}
   try{
    # 이 사례의 정확한 정상 인계 경로만 관측. 원래 저장 실행과 실제 예외 재전달 유지
    function Save-CapJson([string]$Path,$Value){
     if($Path -cne $normalHandoffPath){& $normalHandoffSave $Path $Value;return}
     $normalSaveObservation.Calls++
     $normalSaveObservation.Path=$Path
     $normalSaveObservation.RealSaveCalled=$true
     try{& $normalHandoffSave $Path $Value}
     catch{
      $base=$_.Exception.GetBaseException()
      $normalSaveObservation.ExceptionObserved=$true
      $normalSaveObservation.ExceptionMessage=$_.Exception.Message
      $normalSaveObservation.ExceptionType=$_.Exception.GetType().FullName
      $normalSaveObservation.BaseExceptionType=$base.GetType().FullName
      $normalSaveObservation.HResult=$base.HResult
      $normalSaveObservation.FileSystemFailure=($base -is [IO.IOException] -or $base -is [UnauthorizedAccessException])
      throw
     }
    }
    try{Wait-CapWorker $o $clock|Out-Null}catch{$first=$o.Failure}
   }finally{Set-Item Function:Save-CapJson $normalHandoffSave}
   Assert-NormalHandoffSaveFailure $normalSaveObservation $normalHandoffPath $first
   if(-not(Test-Path -LiteralPath $normalHandoffPath -PathType Container) -or $o.CleanupFailure){throw 'Normal handoff obstruction or failure kind changed'}

   # 보존된 오원인과 같은 작은 입력: 저장 미도달 및 다른 최초 실패 모두 새 assertion이 거부
   $wrongFirst='CAP service handoff failed: Unconfirmed MySQL descendant'
   $notReached=@{Calls=0;Path=$null;RealSaveCalled=$false;ExceptionObserved=$false;FileSystemFailure=$false}
   $rejections=@()
   foreach($negative in @(@{Name='ownership-before-save';Observation=$notReached;Expected='Normal handoff save was not reached at the expected path'},@{Name='different-first-failure';Observation=$normalSaveObservation;Expected='First failure does not match the observed normal handoff save exception'})){
    $rejection=$null
    try{Assert-NormalHandoffSaveFailure $negative.Observation $normalHandoffPath $wrongFirst}catch{$rejection=$_.Exception.Message}
    if($rejection -cne $negative.Expected){throw 'Wrong-cause negative control was not rejected by the required assertion'}
    $rejections+=@{Case=$negative.Name;Observation=$negative.Observation;FirstFailure=$wrongFirst;RejectedBy=$rejection}
   }
   if(${function:Save-CapJson}.ToString() -cne $normalHandoffSave.ToString()){throw 'Normal save observer leaked'}
   Save-CapJson "$req.save-observation.json" @{Preparation=$prepared;Observation=$normalSaveObservation;FirstFailure=$first;NegativeControls=$rejections;ObserverRestored=$true;OriginalExpires=$clock.Expires}
  }else{try{Wait-CapWorker $o $clock|Out-Null}catch{$first=$o.Failure}}
  if(-not $first -or $o.Result.NextTaskAllowed -or (Test-CapJoined $o) -or $o.Services.Count){throw 'Failed startup became ready'}
  if((Get-CapRemaining $clock) -lt 20000){throw 'Failure waited for live service or deadline'}
  Receive-CapService $o -CleanupOnly
  $canStop=Test-CapDbUsersClosed $o
  $expected=$case -in @('probe-failure','handoff-save-failure')
  if($canStop -ne $expected -or (Test-CapJoined $o) -or $o.Failure -cne $first){throw 'Cleanup eligibility or first failure mismatch'}
  if($case -eq 'handoff-save-failure'){
   $cleanup=Get-Content -LiteralPath "$req.cleanup-handoff.json" -Raw -Encoding UTF8|ConvertFrom-Json
   if($o.Services.Count -or $o.Result.NextTaskAllowed -or $o.Result.FirstFailure -cne $first -or $cleanup.Ready -ne $false -or $cleanup.Purpose -cne 'normal-stop-only' -or @($cleanup.Processes).Count -ne 1 -or $cleanup.Processes[0].Pid -ne $prepared.Service.Pid){throw 'Normal save failure was not preserved through cleanup handoff'}
  }
  $results+=@{Case=$case;Passed=$true;Ready=$false;DbUsersClosed=$canStop;FirstFailure=$first;ActualExit=$o.Result.Exit;Members=@($o.Capture.Members());CleanupServices=$o.CleanupServices}
 }finally{
  # 준비/검증 예외에도 뒤쪽 cleanup 실패 주입과 관측 상태 분리
  if($case -eq 'handoff-save-failure'){Set-Item Function:Save-CapJson $normalHandoffSave}
  # 이 시험이 만든 유한 대역에만 정상 종료 marker 전달. DB/외부 프로세스 종료 없음
  [IO.File]::WriteAllText($stop,'owned finite fixture stop')
  $final=@(Wait-CapCleanup $registry $clock -Final)
  if(@($final|Where-Object {-not $_.Joined}).Count){throw 'Fixture descendants remain'}
  foreach($owner in $registry){$owner.Capture.Release();$released=$true}
  $close=@{Workers=$final;Released=$released}
  if($case -eq 'handoff-save-failure'){
   $close.TeardownOnly=$true;$close.ControllerStopInvoked=$false;$close.SqlShutdownInvoked=$false
   $close.OutCompleted=$o.Capture.OutTask.IsCompleted;$close.ErrCompleted=$o.Capture.ErrTask.IsCompleted
   $close.ObserverRestored=(${function:Save-CapJson}.ToString() -ceq $normalHandoffSave.ToString())
  }
  Save-CapJson "$Output/$case.close.json" $close
 }
}
Save-CapJson "$Output/results.json" @{Passed=$results.Count;Cases=$results;DatabaseServiceStarts=0;ForceKills=0}
Write-Output "CAP_CLEANUP_BOUNDARIES_PASS=$($results.Count)"

}

# 실제 controller Stop 분기와 동일한 함수 사용. 수정 전에는 당시 controller의 해당 문장 자체를 실행
$controller=[IO.File]::ReadAllText("$PSScriptRoot/run-phase18-cap-smoke.ps1")
if($BeforeFix){
 $a=$controller.IndexOf('            foreach($owner in $owned)');$b=$controller.IndexOf('            # A live worker')
 if($a -lt 0 -or $b -le $a){throw 'Original controller cleanup section changed'}
 $legacyController=[scriptblock]::Create($controller.Substring($a,$b-$a))
}elseif($controller -notmatch 'Invoke-CapMysqlStopWhenReady'){throw 'Controller does not use tested Stop function'}
$lateStarter="$fixtureRoot/late-start.ps1"
[IO.File]::WriteAllText($lateStarter,@'
param($Python,$Finite,$Dir,$Case,$Helper)
. $Helper
$argsText=@($Finite,"$Dir/stop","$Dir/service-ready","$Dir/service-done")|ForEach-Object {[Phase18Cap.JobCapture]::Quote($_)}
$p=[Phase18Cap.JobCapture]::StartService($Python,($argsText -join ' '),(Get-Location).Path,"$Dir/service.out","$Dir/service.err")
$until=[DateTime]::UtcNow.AddSeconds(20)
while(-not(Test-Path "$Dir/service-ready") -and [DateTime]::UtcNow -lt $until){Start-Sleep -Milliseconds 10}
if(-not(Test-Path "$Dir/service-ready")){throw 'Finite fixture not ready'}
if($Case -eq 'unknown-descendant'){
 $extraArgs=@($Finite,"$Dir/stop","$Dir/extra-ready","$Dir/extra-done")|ForEach-Object {[Phase18Cap.JobCapture]::Quote($_)}
 $extra=[Phase18Cap.JobCapture]::StartService($Python,($extraArgs -join ' '),(Get-Location).Path,"$Dir/extra.out","$Dir/extra.err")
}
[IO.File]::WriteAllText("$Dir/worker-waiting",[DateTime]::UtcNow.ToString('o'))
while(-not(Test-Path "$Dir/go") -and [DateTime]::UtcNow -lt $until){Start-Sleep -Milliseconds 10}
if(-not(Test-Path "$Dir/go")){throw 'Finite worker gate exhausted'}
$id=@{Pid=$p.Id;StartUtc=$p.StartTime.ToUniversalTime().ToString('o');Binary=$p.Path;BinarySha256=(Get-FileHash $p.Path).Hash}
if($Case -eq 'wrong-identity'){$id.StartUtc='2000-01-01T00:00:00.0000000Z'}
$hash=(Get-FileHash "$Dir/service-ready").Hash
if($Case -eq 'bad-receipt'){$hash='0'*64}
$ready=$Case -eq 'already-ready';$suffix=if($ready){'service'}else{'cleanup-service'}
Save-CapJson "$env:PIXEL_PLACE_CAP_REQUEST.$suffix.json" @{Role='mysql3307';Ready=$ready;Purpose=if($ready){'ready'}else{'normal-stop-only'};Request=$env:PIXEL_PLACE_CAP_REQUEST;ReadinessReceipt="$Dir/service-ready";ReadinessHash=$hash;Processes=@($id)}
[IO.File]::WriteAllText("$Dir/offer-written",[DateTime]::UtcNow.ToString('o'))
# 대역 자신만 종료. 외부 STOP 이후 늦은 실제 exit0/7도 보존하는 경계
[Environment]::Exit($(if($Case -eq 'late-exit7'){7}else{0}))
'@,[Text.UTF8Encoding]::new($true))
$productionDbUsers=${function:Test-CapDbUsersClosed}
$productionSave=${function:Save-CapJson}
function Test-CapDbUsersClosed($Owned){
 $value=& $productionDbUsers $Owned
 if($script:observeCleanup -and [object]::ReferenceEquals($Owned,$script:caseOwner)){
  $script:observations++
  if($script:observations -eq 1){
   Save-CapJson "$script:caseDir/first-cleanup.json" @{WorkerExited=$Owned.Capture.Process.HasExited;OutCompleted=$Owned.Capture.OutTask.IsCompleted;ErrCompleted=$Owned.Capture.ErrTask.IsCompleted;DbUsersClosed=$value;StopCalls=$script:stopCalls;Utc=[DateTime]::UtcNow.ToString('o')}
   if($script:caseName -in @('late-worker','late-output-failure','late-exit7')){[IO.File]::WriteAllText("$script:caseDir/go",'release only after first cleanup observation')}
   if($script:caseName -in @('late-stdout','late-stderr')){$script:streamGate.SetResult($null)}
  }
 }
 return $value
}
function Save-CapJson([string]$Path,$Value){
 if($script:caseName -eq 'save-failure' -and $Path -eq "$script:caseDir/start.request.json.cleanup-handoff.json"){
  $script:saveAttempts++
  [IO.File]::WriteAllText($Path,'partial write retained')
  throw [IO.IOException]::new('Injected cleanup handoff write failure')
 }
 & $productionSave $Path $Value
}
function Invoke-Step($Name,$Kind,$Clock,$Parameters){
 if($Name -cne 'mysql-stop' -or $Parameters.Parameters.Action -cne 'Stop'){throw 'Unexpected controller branch'}
 & $script:stopAction $Clock
}
$raceResults=@()
$cases=if($BeforeFix){@('late-worker')}else{@('late-worker','late-output-failure','late-exit7','late-stdout','late-stderr','already-ready','already-cleanup','unknown-descendant','wrong-identity','bad-receipt','save-failure','worker-expiry','stream-expiry','expired-before-handoff','ordinary-no-adoption','final-no-adoption')}
if($OnlyUnstarted){$cases=@()}
foreach($case in $cases){
 $dir="$Output/$case";New-Item -ItemType Directory $dir -ErrorAction Stop|Out-Null
 $script:caseName=$case;$script:caseDir=$dir;$script:observeCleanup=$false;$script:observations=0;$script:stopCalls=0;$script:saveAttempts=0;$script:streamGate=$null
 $registry=[Collections.Generic.List[object]]::new();$released=$false;$rescue=$false;$normalStop=$false;$first=$null
 $original=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+$(if($case -eq 'late-worker'){4500}else{20000}))
 $req=@{Kind='script';Repo=$repo;File=$lateStarter;ServiceRole='mysql3307';ExpiresEpochMillis=$original.Expires;OriginalExpiresEpochMillis=$original.Expires;Output="$dir/worker-exit.json";Parameters=@{Python=$servicePython;Finite=$finite;Dir=$dir;Case=$case;Helper="$PSScriptRoot/phase18-cap-process.ps1"}}
 try{
  $o=Start-CapWorker $req "$dir/start.request.json" $original $registry;$script:caseOwner=$o
  while(-not(Test-Path "$dir/worker-waiting")){if((Get-CapRemaining $original) -le 0){throw 'Worker did not reach controlled gate'};Start-Sleep -Milliseconds 10}
  $gatedWorker=$case -in @('late-worker','late-output-failure','late-exit7','worker-expiry')
  if(-not $gatedWorker){
   [IO.File]::WriteAllText("$dir/go",'worker may finish before cleanup')
   while(-not(Test-CapWorkerExit $o)){if((Get-CapRemaining $original) -le 0){throw 'Worker exit not observed'};Start-Sleep -Milliseconds 10}
  }
  if($case -in @('late-stdout','late-stderr','stream-expiry')){
   $script:streamGate=[Threading.Tasks.TaskCompletionSource[object]]::new()
   if($case -eq 'late-stderr'){$o.Capture.ErrTask=$script:streamGate.Task}else{$o.Capture.OutTask=$script:streamGate.Task}
  }
  if($case -ne 'late-worker' -and $case -ne 'already-ready'){$o.Capture.Out.Failure='injected original output failure'}
  try{Wait-CapWorker $o $original|Out-Null}catch{}
  $first=$o.Failure
  if($case -ne 'already-ready' -and (-not $first -or $o.Result.NextTaskAllowed)){throw 'Original failure not captured'}
  if($case -eq 'already-cleanup'){Receive-CapService $o -CleanupOnly}
  $handoff="$dir/start.request.json.$(if($case -eq 'already-ready'){'handoff'}else{'cleanup-handoff'}).json"
  $beforeHash=if(Test-Path $handoff){(Get-FileHash $handoff).Hash}else{$null}
  [IO.File]::WriteAllText("$dir/mysql-start.json",'isolated start receipt existence gate; no DB')
  $cleanupClock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+$(if($case -in @('late-worker','late-output-failure','late-exit7','late-stdout','late-stderr','already-ready','already-cleanup')){5000}else{600}))
  $expiry=$cleanupClock.Expires
  if($case -eq 'expired-before-handoff'){$cleanupClock.Millis=0}
  $script:stopAction={param($Clock)
   if(-not [object]::ReferenceEquals($Clock,$cleanupClock) -or (Get-CapRemaining $Clock) -le 0){throw 'Stop received fresh or expired clock'}
   if(-not(Test-CapWorkerExit $o) -or -not(& $productionDbUsers $o)){throw 'Stop before DB users/streams/ownership closed'}
   $script:stopCalls++
   Save-CapJson "$dir/controller-stop.json" @{ClockExpires=$Clock.Expires;WorkerExit=$o.Capture.Process.ExitCode;OutCompleted=$o.Capture.OutTask.IsCompleted;ErrCompleted=$o.Capture.ErrTask.IsCompleted;Members=@($o.Capture.Members());FirstFailure=$o.Failure;Utc=[DateTime]::UtcNow.ToString('o')}
   [IO.File]::WriteAllText("$dir/stop",'controller Stop callback; finite fixture only')
  }
  $script:observeCleanup=$true
  if($BeforeFix){$owned=$registry;$out=$dir;$session='isolated-fixture';$failure=[Exception]::new($first);$clock=$original;. $legacyController}
  elseif($case -eq 'ordinary-no-adoption'){$null=Wait-CapCleanup $registry $cleanupClock}
  elseif($case -eq 'final-no-adoption'){$null=Wait-CapCleanup $registry $cleanupClock -Final}
  else{$normalStop=Invoke-CapMysqlStopWhenReady $registry $cleanupClock $cleanupClock "$dir/mysql-start.json" $script:stopAction}
  $script:observeCleanup=$false
  $expected= -not $BeforeFix -and $case -in @('late-worker','late-output-failure','late-exit7','late-stdout','late-stderr','already-ready','already-cleanup')
  if($normalStop -ne $expected -or $script:stopCalls -ne [int]$expected -or $o.Failure -cne $first -or $cleanupClock.Expires -ne $expiry){throw "Stop/first failure/clock contract: $case"}
  if($case -ne 'already-ready' -and ($o.Result.NextTaskAllowed -or $o.Services.Count)){throw 'Cleanup restored READY or next task'}
  if($beforeHash -and (Get-FileHash $handoff).Hash -cne $beforeHash){throw 'Duplicate handoff write'}
  if($case -eq 'save-failure' -and ($script:saveAttempts -ne 1 -or [IO.File]::ReadAllText($handoff) -cne 'partial write retained' -or $o.CleanupFailure -notmatch 'write failure')){throw 'Failed handoff write repeated or hidden'}
  if($case -in @('unknown-descendant','wrong-identity','bad-receipt') -and -not $o.CleanupFailure){throw 'Cleanup rejection detail missing'}
  $productFinal=@(Wait-CapCleanup $registry $cleanupClock -Final)
  $joined=Test-CapJoined $o -Final
  $releaseRejected=$false
  if($expected){if(-not $joined -or -not(Test-Path "$dir/service-done")){throw 'Controller Stop did not close actual descendant'};$o.Capture.Release();$released=$true}
  else{try{$o.Capture.Release()}catch{$releaseRejected=$true};if(-not $releaseRejected){throw 'Unresolved capture released'}}
  $snapshot=@{Case=$case;BeforeFix=[bool]$BeforeFix;ExpectedStop=$expected;StopCalls=$script:stopCalls;ActualExit=if($o.Capture.Process.HasExited){$o.Capture.Process.ExitCode}else{$null};OriginalResult=$o.Result;FirstFailure=$first;CleanupFailure=$o.CleanupFailure;Observations=$script:observations;CleanupExpires=$expiry;Final=$productFinal;ProductReleased=$released;UnresolvedReleaseRejected=$releaseRejected;Passed=$true}
  Save-CapJson "$dir/product-result.json" $snapshot;$raceResults+=$snapshot
 }finally{
  $script:observeCleanup=$false
  if(-not $released){
   $rescue=$true
   if($script:streamGate -and -not $script:streamGate.Task.IsCompleted){$script:streamGate.SetResult($null)}
   [IO.File]::WriteAllText("$dir/go",'test teardown only');[IO.File]::WriteAllText("$dir/stop",'test teardown only')
   $teardown=@(Wait-CapCleanup $registry (New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+15000)) -Final)
   foreach($owner in $registry){if(-not(Test-CapJoined $owner -Final)){throw 'Test fixture teardown unresolved'};$owner.Capture.Release()}
  }
  Save-CapJson "$dir/test-teardown.json" @{SeparateFromProductCleanup=$rescue;AllReleased=$true;ForceKilled=$false}
 }
}
# 시작 전 실패한 capture는 Job 조회나 서비스 인계 대상이 아님
if(-not $BeforeFix){
 $never=[pscustomobject]@{Started=$false;Pid=$null;StartUtc=$null;RequestPath="$Output/never-started";Capture=[Phase18Cap.JobCapture]::new();Failure='spawn rejected';Services=@();CleanupServices=@();CleanupFailure=$null;ServiceRole='mysql3307'}
 $neverClock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+1000)
 $called=Invoke-CapMysqlStopWhenReady @($never) $neverClock $neverClock "$Output/absent-start-receipt" {throw 'Unstarted worker reached Stop'}
 if($called -or $never.CleanupFailure -or $never.CleanupServices.Count -or $never.Failure -cne 'spawn rejected'){throw 'Unstarted worker was adopted'}
 $raceResults+=@{Case='not-started';Passed=$true;StopCalls=0;NoJobCreated=$true;NoReleaseRequired=$true}
}
Set-Item Function:Test-CapDbUsersClosed $productionDbUsers
Set-Item Function:Save-CapJson $productionSave
Save-CapJson "$Output/late-results.json" @{Passed=$raceResults.Count;Cases=$raceResults;DatabaseStarts=0;StreamDelay='Deterministic pending Task proxy after actual Windows pump completion';ControllerBranch=if($BeforeFix){'Original controller statements'}else{'Shared Invoke-CapMysqlStopWhenReady'}}
Write-Output "CAP_LATE_CLEANUP_PASS=$($raceResults.Count) BEFORE_FIX=$BeforeFix"
