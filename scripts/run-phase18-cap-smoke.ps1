# CAP-0 등록 smoke의 단일 만료·누적 예산·정상 종료 소유. 후속 단계 자동 실행 없음
param(
    [Parameter(Mandatory=$true)][ValidateSet('S1','S2')][string]$Smoke,
    [Parameter(Mandatory=$true)][string]$LedgerRoot,
    [string]$RetryOf,
    [string]$ResumeApproval,
    [string]$ResumeApprovalHash,
    [string]$S1ClosureSupplement,
    [string]$S1ClosureSupplementHash,
    [string]$K6,
    [string]$BudgetReservation
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5){throw 'CAP controller requires Windows PowerShell 5.1 for the existing C/F identity helpers; use powershell.exe -NoProfile -File'}
$repo=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $repo
. "$PSScriptRoot/pixel-place-work.ps1"
. "$PSScriptRoot/mysql-test-redis.ps1"
Assert-PixelPlaceWorkPath $LedgerRoot
$LedgerRoot=Resolve-PixelPlaceControlRoot $LedgerRoot
if(-not(Test-Path "$LedgerRoot/environment-before.json") -or -not(Test-Path "$LedgerRoot/instruction-v2.md")){throw 'CAP baseline and instruction required'}
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/phase18-cap-resume.ps1"
function Save-New($Path,$Value){Save-CapJson $Path $Value}
if(-not(Test-Path "$LedgerRoot/cap-budget.json")){throw 'Final cumulative cap-budget.json seed required; no baseline reset'}
$script:stepOrdinal=0
$owned=[Collections.Generic.List[object]]::new()
function New-StepRequest([string]$Name,[string]$Kind,$Clock,[hashtable]$Extra) {
    $script:stepOrdinal++
    $request=@{Kind=$Kind;Repo=$repo;SessionId=$session;ExpiresEpochMillis=$Clock.Expires;OriginalExpiresEpochMillis=$expires;Output="$out/$Name-exit.json"}
    foreach($key in $Extra.Keys){$request[$key]=$Extra[$key]}
    $path="$out/step-$('{0:D3}' -f $script:stepOrdinal)-$Name.request.json"
    return @{Request=$request;Path=$path}
}
function Invoke-Step([string]$Name,[string]$Kind,$Clock,[hashtable]$Extra) {
    $spec=New-StepRequest $Name $Kind $Clock $Extra
    $process=Start-CapWorker $spec.Request $spec.Path $Clock $owned
    Wait-CapWorker $process $Clock | Out-Null
}
function Invoke-Budget([string]$Name,$Clock) {
    $budgetArgs=@('-B',"$PSScriptRoot/phase18_cap_budget.py",'--ledger-root',$LedgerRoot,'--repo',$repo,'--output',"$out/$Name.json",'--execution-expiry',[string]$Clock.Expires)
    if($K6){$budgetArgs+=@('--reservation',$BudgetReservation);if($Name -eq 'budget-after'){$budgetArgs+='--close-reservation'}}
    Invoke-Step $Name 'native' $Clock @{File=$python;Arguments=$budgetArgs}
    return (Get-Content -Encoding UTF8 -LiteralPath "$out/$Name.json" -Raw|ConvertFrom-Json)
}
if(($K6 -or $BudgetReservation) -and (-not $K6 -or -not $BudgetReservation -or -not $ResumeApproval -or -not $ResumeApprovalHash)){throw 'k6 needs explicit registered resume policy and budget transition'}
if(($S1ClosureSupplement -or $S1ClosureSupplementHash) -and (-not $S1ClosureSupplement -or -not $S1ClosureSupplementHash -or $Smoke -ne 'S2' -or -not $ResumeApproval -or -not $ResumeApprovalHash)){throw 'CAP S1 supplement requires complete registered S2 input'}
$lockPath="$LedgerRoot/cap-controller.lock"
# 준비 실패 잠금의 보존 이동과 실행 발급을 같은 배타 guard로 직렬화. 파일은 재사용하되 절단/삭제 금지
$preparationGuard=[IO.File]::Open("$LedgerRoot/cap-preparation-close.guard",[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {$lock=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
catch {$preparationGuard.Dispose();throw}
$session=$null;$serviceStarted=$null;$failure=$null;$hostProcess=$null;$normalStop=$false;$runExit=$null;$analysisExit=$null;$closed=$false
$createdSession=$false;$closureSaved=$false;$budget=$null;$budgetAfter=$null;$probe=$null;$resume=$null;$evidenceIndex=$null
$policyPath="$LedgerRoot/cap-policy-v2-r2.json"
$python='C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
try {
    $prior=@(Get-ChildItem -LiteralPath $LedgerRoot -Filter 'attempt-*.json'|ForEach-Object {Get-Content -Encoding UTF8 $_.FullName -Raw|ConvertFrom-Json})
    if($ResumeApproval -or $ResumeApprovalHash){
        if(-not $ResumeApproval -or -not $ResumeApprovalHash -or $RetryOf){throw 'Complete resume approval binding required; RetryOf is reserved for legacy retries'}
        $resume=Get-CapResumeContext -Root $LedgerRoot -Repo $repo -ApprovalPath $ResumeApproval -ApprovalHash $ResumeApprovalHash -Smoke $Smoke -Prior $prior -S1ClosureSupplement $S1ClosureSupplement -S1ClosureSupplementHash $S1ClosureSupplementHash
        if($S1ClosureSupplement -and $resume.Policy.sha256 -ieq $resume.S1Policy.sha256){throw 'CAP S2 execution requires successor policy registration'}
        $serviceMillis=[long]$resume.ServiceMillis
        $policyPath=$resume.Policy.path
        if($K6){$s2Profile=Assert-CapK6Binding (Read-CapBoundFile $resume.Policy) $K6 $BudgetReservation $Smoke}
    }else{
    if($prior.Count -ge 4){throw 'CAP-0 four-attempt budget exhausted'}
    $same=@($prior|Where-Object Smoke -eq $Smoke)
    if($same.Count -ge 2 -or ($same.Count -gt 0 -and $RetryOf -ne $same[0].SessionId) -or ($same.Count -eq 0 -and $RetryOf)){throw 'Only one implementation-fix retry of same failed smoke'}
    $serviceMillis=0L
    foreach($attempt in $prior){
        $closurePath=Resolve-PixelPlaceHistoricalPath "$(Get-CapSessionEvidencePath $repo $attempt.SessionId)/cap-closure.json"
        if(-not(Test-Path $closurePath)){throw 'Earlier CAP attempt not closed'}
        $closure=Get-Content -Encoding UTF8 $closurePath -Raw|ConvertFrom-Json
        $finalPath=Resolve-PixelPlaceHistoricalPath "$(Get-CapSessionEvidencePath $repo $attempt.SessionId)/cap-closure-final.json"
        if(Test-Path $finalPath){
            $final=Get-Content -Encoding UTF8 $finalPath -Raw|ConvertFrom-Json
            if($final.PreviousClosureHash -ne (Get-FileHash $closurePath).Hash -or $final.Status -ne $closure.Status -or $final.ServiceMillis -lt $closure.ServiceMillis){throw 'Invalid supplemental ownership closure'}
            $closure=$final
        }
        if(-not $closure.OwnedResourcesClosed){throw 'Earlier owned resources unresolved'}
        $serviceMillis+=[long]$closure.ServiceMillis
        if($RetryOf -eq $attempt.SessionId -and $closure.Status -eq 'PASS'){throw 'A passed smoke cannot be retried'}
        if($closure.Status -ne 'PASS' -and $RetryOf -ne $attempt.SessionId){
            $fixed=@($prior|Where-Object {$_.RetryOf -eq $attempt.SessionId}|Where-Object {(Get-CapHistoricalContent -Encoding UTF8 "$(Get-CapSessionEvidencePath $repo $_.SessionId)/cap-closure.json" -Raw|ConvertFrom-Json).Status -eq 'PASS'})
            if($fixed.Count -ne 1){throw 'Earlier failed smoke requires its explicit implementation-fix retry'}
        }
    }
    if($Smoke -eq 'S2' -and @($prior|Where-Object Smoke -eq 'S1').Count -eq 0){throw 'S1 must close before S2'}
    if($serviceMillis+2400000 -gt 3600000){throw 'CAP-0 cumulative service-time reservation exhausted'}
    }
    $session='phase18-cap0-'+$Smoke.ToLower()+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
    $runId='cap0-'+$Smoke.ToLower()+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
    $out=Get-PixelPlaceEvidencePath $session
    if(Test-Path $out){throw 'Fresh CAP session required'}
    New-Item -ItemType Directory -Path $out|Out-Null
    $createdSession=$true
    $issued=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();$expires=$issued+2400000
    $clock=New-CapClock $expires
    if($resume){
        # 발급 도중 실패해도 슬롯 반환 없음. 같은 장부의 배타 제어 아래 CREATE_NEW
        Save-New $resume.SlotPath @{SessionId=$session;RunId=$runId;Smoke=$Smoke;IssuedEpochMillis=$issued;ResumeApprovalHash=$resume.Approval.sha256;ResumePolicyHash=$resume.Policy.sha256;ResolvesFailures=$resume.ResolvedFailures}
    }
    Save-New "$out/cap-issued.json" @{issuedEpochMillis=$issued;expiresEpochMillis=$expires;Smoke=$Smoke;SessionId=$session;RunId=$runId;ServiceMillisBefore=$serviceMillis;RecordedServiceMillisFloorBefore=if($resume){$resume.RecordedServiceMillisFloor}else{$serviceMillis};HistoricalServiceTimeUncertain=if($resume){$resume.HistoricalServiceTimeUncertain}else{$false};BudgetSeedHash=(Get-FileHash "$LedgerRoot/cap-budget.json").Hash;BudgetObservation='budget-before.json';RetryOf=$RetryOf;ResumeApproval=if($resume){$resume.Approval}else{$null};ResumePolicy=if($resume){$resume.Policy}else{$null};ResumeApprovalHash=if($resume){$resume.Approval.sha256}else{$null};ResumePolicyHash=if($resume){$resume.Policy.sha256}else{$null}}
    Save-New "$LedgerRoot/attempt-$($prior.Count+1).json" @{Smoke=$Smoke;SessionId=$session;RunId=$runId;RetryOf=$RetryOf;IssuedEpochMillis=$issued;ExpiresEpochMillis=$expires;ResumeApprovalHash=if($resume){$resume.Approval.sha256}else{$null};ResumePolicyHash=if($resume){$resume.Policy.sha256}else{$null}}
    $work=Set-PixelPlaceWorkEnvironment $session
    $budget=Invoke-Budget 'budget-before' $clock
    Invoke-Step 'devices' 'devices' $clock @{Output="$out/devices.json"}
    Invoke-Step 'mysql-inspect' 'script' $clock @{File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=@{Action='Inspect';RunId=$session}}
    $serviceStarted=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $mysqlStartParameters=@{Action='Start';RunId=$session}
    if($K6){$mysqlStartParameters.EvidenceBytes=if($Smoke -ceq 'S2'){$s2Profile.evidenceBytes}else{201326592L}}
    Invoke-Step 'mysql-start' 'script' $clock @{ServiceRole='mysql3307';File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=$mysqlStartParameters}
    Invoke-Step 'mysql-verify' 'script' $clock @{File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=@{Action='Verify';RunId=$session}}
    Invoke-Step 'redis-selection' 'redis' $clock @{Output="$out/redis-selection.json"}
    $redis=(Get-Content -Encoding UTF8 "$out/redis-selection.json" -Raw|ConvertFrom-Json).Identity
    Invoke-Step 'mysql-environment' 'script' $clock @{File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=@{Action='Environment';RunId=$session;RedisDatabase=$redis.Database}}
    $buildArgs=@('phase18ToolsClasspath',"-Pphase18ClasspathFile=$out/classpath.txt")+$work.GradleArguments
    Save-New "$out/build-command.json" $buildArgs
    Invoke-Step 'build' 'native' $clock @{File="$repo/gradlew.bat";Arguments=$buildArgs}
    $null=Invoke-Budget 'budget-after-build' $clock
    $planArgs=@('-B',"$PSScriptRoot/new-phase18-cap-plan.py",'--environment',"$out/environment-environment.json",'--predecessor',$policyPath,'--issued',"$out/cap-issued.json",'--smoke',$Smoke,'--run-id',$runId)
    if($K6){$planArgs+='--k6';if($Smoke -ceq 'S2'){$planArgs+=@('--s2-profile',(Read-CapBoundFile $resume.Policy).k6.s2Profile.path)}}
    Invoke-Step 'plan-generation' 'native' $clock @{File=$python;Arguments=$planArgs}
    Invoke-Step 'plan' 'script' $clock @{File="$PSScriptRoot/measure-phase18.ps1";Parameters=@{Action='Plan';PlanPath="$out/plan-cap.json";Java=$work.Java;ClasspathFile="$out/classpath.txt"}}
    . "$PSScriptRoot/harness-local-secrets.ps1"
    Import-HarnessLocalSecrets -Root $repo
    $application=[IO.File]::ReadAllText("$repo/src/main/resources/application.yml")
    $users=[regex]::Matches($application,'(?m)^    username: ([A-Za-z0-9_]+)\s*$')
    if($users.Count -ne 1 -or -not $env:DB_PASSWORD){throw 'Existing secret injection required'}
    $env:PIXEL_PLACE_BENCH_DB_HOST='127.0.0.1';$env:PIXEL_PLACE_BENCH_DB_PORT='3307';$env:PIXEL_PLACE_BENCH_DB_USERNAME=$users[0].Groups[1].Value;$env:PIXEL_PLACE_BENCH_DB_PASSWORD=$env:DB_PASSWORD
    $env:PIXEL_PLACE_BENCH_REDIS_HOST='127.0.0.1';$env:PIXEL_PLACE_BENCH_REDIS_PORT='6379';$env:PIXEL_PLACE_BENCH_REDIS_DATABASE=[string]$redis.Database;$env:PIXEL_PLACE_BENCH_PRODUCTION_REDIS_DATABASE='0'
    $hostCount=if($Smoke -eq 'S1'){120}else{360}
    $hostSpec=New-StepRequest 'host' 'script' $clock @{File="$PSScriptRoot/observe-phase18-host.ps1";Parameters=@{Output="$out/host.jsonl";Samples=$hostCount;IntervalMillis=1000;ExecutionExpiry=$expires;StopFile="$out/host-stop.json"}}
    $hostProcess=Start-CapWorker $hostSpec.Request $hostSpec.Path $clock $owned
    try{
        $runParameters=@{Action='Run';PlanPath="$out/plan-cap.json";Java=$work.Java;ClasspathFile="$out/classpath.txt";EnvironmentFile="$out/environment-environment.json";ObservationPolicy="$out/policy-cap.json"}
        if($K6){$runParameters.Action='ProduceK6';$runParameters.K6=$K6}
        Invoke-Step 'run' 'script' $clock @{File="$PSScriptRoot/measure-phase18.ps1";Parameters=$runParameters}
        $runExit=0
    }catch{$runExit=1;$failure=$_.Exception}
    Wait-CapWorker $hostProcess $clock | Out-Null
    if((Test-Path "$out/$runId/initial/raw.jsonl") -or ($K6 -and (Test-Path "$out/$runId/initial/k6-input.json"))){
        $analysisArgs=@('-B',"$PSScriptRoot/analyze-phase18.py",'--plan',"$out/plan-cap.json",'--run-dir',"$out/$runId/initial",'--output',"$out/analysis.json",'--host-disk',"$out/host.jsonl",'--devices',"$out/devices.json",'--execution-expiry',[string]$expires)
        Save-New "$out/analysis-command.json" $analysisArgs
        try{Invoke-Step 'analysis' 'native' $clock @{File=$python;Arguments=$analysisArgs};$analysisExit=0}catch{$analysisExit=1;throw}
        $analysis=Get-Content -Encoding UTF8 "$out/analysis.json" -Raw|ConvertFrom-Json
        if($analysis.cap.smokeVerification -ne 'PASS'){throw 'CAP smoke analysis did not pass'}
    }else{throw 'CAP initial raw absent'}
    if($failure){throw $failure}
}catch{if(-not $failure){$failure=$_.Exception}}
finally {
    try{
        if($createdSession){
            # Existing 180s failure cleanup observation budget; never renew the trial expiry.
            $cleanupClock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+180000)
            if($hostProcess -and -not(Test-Path "$out/host-stop.json")){
                try{Save-New "$out/host-stop.json" @{Reason='controller cleanup';OriginalExpiresEpochMillis=$expires}}catch{if(-not $failure){$failure=$_.Exception}}
            }
            try{
                $closeWorkClock=if($failure){$cleanupClock}else{$clock}
                $normalStop=Invoke-CapMysqlStopWhenReady $owned $cleanupClock $closeWorkClock "$out/mysql-start.json" {
                    param($stopClock)
                    Invoke-Step 'mysql-stop' 'script' $stopClock @{File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=@{Action='Stop';RunId=$session}}
                }
            }catch{if(-not $failure){$failure=$_.Exception}}
            # A live worker may still own SQL/native work. Do not stop its DB underneath it.
            if(@($owned|Where-Object {-not(Test-CapJoined $_)}).Count -eq 0 -and (Get-CapRemaining $cleanupClock) -gt 0){
                try{
                    $closeWorkClock=if($failure){$cleanupClock}else{$clock}
                    Invoke-Step 'closure-probe' 'closure-probe' $closeWorkClock @{Output="$out/closure-probe.json"}
                    $probe=Get-Content -Encoding UTF8 "$out/closure-probe.json" -Raw|ConvertFrom-Json
                }catch{if(-not $failure){$failure=$_.Exception}}
                if(@($owned|Where-Object {-not(Test-CapJoined $_)}).Count -eq 0){
                    try{$closeWorkClock=if($failure){$cleanupClock}else{$clock};$budgetAfter=Invoke-Budget 'budget-after' $closeWorkClock}catch{if(-not $failure){$failure=$_.Exception}}
                }
            }
            $owners=@(Wait-CapCleanup $owned $cleanupClock -Final)
            $joined=@($owners|Where-Object {-not $_.Joined}).Count -eq 0
            # 최종 후손/stream 종료 확인 뒤 handle 해제. Release 실패도 최초 실패/필수 마감에 반영
            foreach($owner in $owned){
                $record=@($owners|Where-Object Request -eq $owner.RequestPath)[0]
                $record.Released=$false
                if($record.Joined){
                    try{$owner.Capture.Release();$record.Released=$true}
                    catch{if(-not $owner.Failure){$owner.Failure='CAP worker Release failed: '+$_.Exception.Message};$record.FirstFailure=$owner.Failure}
                }
                if($owner.Failure -and -not $failure){$failure=[InvalidOperationException]::new($owner.Failure)}
            }
            $closed=$joined -and @($owners|Where-Object {-not $_.Released}).Count -eq 0 -and (Test-CapClosureProbe $probe) -and (-not $serviceStarted -or $normalStop)
            if(-not $closed -and -not $failure){$failure=[InvalidOperationException]::new('CAP owned resources unresolved')}
            if($resume){try{$evidenceIndex=Save-CapExecutionEvidence $out $runId}catch{if(-not $failure){$failure=$_.Exception}}}
            $finished=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            if(-not $failure -and (Get-CapRemaining $clock) -le 0){$failure=[TimeoutException]::new('CAP original deadline exhausted during final closure')}
            $serviceUsed=if($serviceStarted){$finished-$serviceStarted}else{0}
            Save-CapClosure "$out/cap-closure.json" @{SessionId=$session;RunId=$runId;Smoke=$Smoke;Status=if($failure){'FAILED'}else{'PASS'};FailureType=if($failure){$failure.GetType().Name}else{$null};FailureMessage=if($failure){$failure.Message}else{$null};RunExit=$runExit;AnalysisExit=$analysisExit;NormalMysqlStop=$normalStop;OwnedResourcesClosed=$closed;OwnedWorkers=$owners;ServiceMillis=$serviceUsed;ServiceMillisIsLowerBound=(-not $closed);ServiceMillisCumulative=$serviceMillis+$serviceUsed;RecordedServiceMillisCumulativeFloor=if($resume){$resume.RecordedServiceMillisFloor+$serviceUsed}else{$serviceMillis+$serviceUsed};HistoricalServiceTimeUncertain=if($resume){$resume.HistoricalServiceTimeUncertain}else{$false};FinishedEpochMillis=$finished;Budget=$budgetAfter;BudgetAfterVerified=($null -ne $budgetAfter);OriginalExpiresEpochMillis=$expires;CleanupExpiresEpochMillis=$cleanupClock.Expires;ResumeApprovalHash=if($resume){$resume.Approval.sha256}else{$null};ResumePolicyHash=if($resume){$resume.Policy.sha256}else{$null};EvidenceIndex=$evidenceIndex} $probe
            $closureSaved=$true
            Write-Output "CAP_SMOKE_SESSION=$session"
        }
    }finally{
        try{
            $lock.Dispose()
            if(-not $createdSession -or ($closed -and $closureSaved)){[IO.File]::Delete($lockPath)}
        }finally{$preparationGuard.Dispose()}
        # Unresolved ownership keeps the lock file; no automatic removal or subsequent trial.
    }
}
if($failure){throw $failure}
