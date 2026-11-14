# CAP-1 전용 연결. 기존 CAP worker/소진/복구/SQL Stop/장부 마감 재사용
param(
    [Parameter(Mandatory=$true)][string]$LedgerRoot,
    [Parameter(Mandatory=$true)][string]$PolicyPath,
    [Parameter(Mandatory=$true)][string]$PolicyHash
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
    $stepSeconds=switch($Name){'build' {$profile.parts.build};'analysis' {$profile.parts.analysis};default {0}}
    if($stepSeconds -gt 0){
        $Clock=New-CapClock ([Math]::Min($Clock.Expires,[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+[long]$stepSeconds*1000))
        $spec.Request.ExpiresEpochMillis=$Clock.Expires
    }
    $process=Start-CapWorker $spec.Request $spec.Path $Clock $owned
    Wait-CapWorker $process $Clock | Out-Null
}
function Invoke-Budget([string]$Name,$Clock) {
    $budgetArgs=@('-B',"$PSScriptRoot/phase18_cap_budget.py",'--ledger-root',$LedgerRoot,'--repo',$repo,'--output',"$out/$Name.json",'--execution-expiry',[string]$Clock.Expires)
    if($K6){$budgetArgs+=@('--reservation',$BudgetReservation);if($Name -eq 'budget-after'){$budgetArgs+='--close-reservation'}}
    Invoke-Step $Name 'native' $Clock @{File=$python;Arguments=$budgetArgs}
    return (Get-Content -Encoding UTF8 -LiteralPath "$out/$Name.json" -Raw|ConvertFrom-Json)
}
$lockPath="$LedgerRoot/cap-controller.lock"
# 준비 실패 잠금의 보존 이동과 실행 발급을 같은 배타 guard로 직렬화. 파일은 재사용하되 절단/삭제 금지
$preparationGuard=[IO.File]::Open("$LedgerRoot/cap-preparation-close.guard",[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {$lock=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
catch {$preparationGuard.Dispose();throw}
$session=$null;$serviceStarted=$null;$failure=$null;$hostProcess=$null;$normalStop=$false;$runExit=$null;$analysisExit=$null;$closed=$false
$createdSession=$false;$closureSaved=$false;$budget=$null;$budgetAfter=$null;$probe=$null;$resume=$null;$evidenceIndex=$null
$policyPath=[IO.Path]::GetFullPath($PolicyPath)
$python='C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
try {
    $registration=Get-Content -LiteralPath "$LedgerRoot/cap1-registration.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if($registration.path -ine $policyPath -or $registration.sha256 -ine $PolicyHash){throw 'CAP1 policy not registered'}
    $policy=Read-CapBoundFile $registration
    if($policy.stage -cne 'CAP-1' -or $policy.role -cnotin @('cap-target','cap-diagnostic-ab')){throw 'CAP1 scope required'}
    $profile=$policy.cap1Profile
    $K6=$policy.k6.binary.path;$BudgetReservation=$policy.budgetReservation.path
    $prior=@(Get-ChildItem -LiteralPath $LedgerRoot -Filter 'cap1-attempt-*.json')
    if($policy.attempt -ne $prior.Count+1){throw 'CAP1 registered attempt already consumed'}
    $serviceMillis=[long]$policy.serviceMillisBefore
    $resume=[pscustomobject]@{Policy=$registration;Approval=$policy.instruction;RecordedServiceMillisFloor=$policy.recordedServiceMillisFloorBefore;HistoricalServiceTimeUncertain=$true}
    $session=if($policy.role -ceq 'cap-diagnostic-ab'){'phase18-cap1-diag-'+$policy.comparison.variant.ToLowerInvariant()+'-w'+$profile.writeRate+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')}else{'phase18-cap1-w'+$profile.writeRate+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')}
    $runId='cap1-w'+$profile.writeRate+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
    $out=Get-PixelPlaceEvidencePath $session
    if(Test-Path $out){throw 'Fresh CAP1 session required'}
    New-Item -ItemType Directory -Path $out|Out-Null
    $createdSession=$true
    $issued=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();$expires=$issued+[long]$profile.totalSeconds*1000
    $clock=New-CapClock $expires
    Save-New "$out/cap-issued.json" @{issuedEpochMillis=$issued;expiresEpochMillis=$expires;Stage='CAP-1';Role=$policy.role;SessionId=$session;RunId=$runId;Policy=$registration;ConditionsHash=$policy.conditionsHash;ServiceMillisBefore=$serviceMillis;HistoricalServiceTimeUncertain=$true}
    # 순수 지원/기존 증거 검사 후에만 실제 시도 발급과 서비스 시작 허용
    Invoke-Step 'cap1-validation' 'native' $clock @{File=$python;Arguments=@('-B',"$PSScriptRoot/phase18_cap1.py",'--verify-policy',$policyPath,'--repo',$repo,'--ledger-root',$LedgerRoot)}
    Save-New "$LedgerRoot/cap1-attempt-$($prior.Count+1).json" @{Stage='CAP-1';Role=$policy.role;SessionId=$session;RunId=$runId;Policy=$registration;IssuedEpochMillis=$issued;ExpiresEpochMillis=$expires}
    $work=Set-PixelPlaceWorkEnvironment $session
    $budget=Invoke-Budget 'budget-before' $clock
    Invoke-Step 'devices' 'devices' $clock @{Output="$out/devices.json"}
    Invoke-Step 'mysql-inspect' 'script' $clock @{File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=@{Action='Inspect';RunId=$session}}
    $serviceStarted=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $mysqlStartParameters=@{Action='Start';RunId=$session}
    $mysqlStartParameters.MySqlGrowthBytes=[long]$profile.storage.limits.mysqlBytes
    $mysqlStartParameters.Cap1PolicyPath=$policyPath
    $mysqlStartParameters.Cap1LedgerRoot=$LedgerRoot
    $mysqlStartParameters.EvidenceBytes=[long]$profile.limits.evidenceBytes
    Invoke-Step 'mysql-start' 'script' $clock @{ServiceRole='mysql3307';File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=$mysqlStartParameters}
    Invoke-Step 'mysql-verify' 'script' $clock @{File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=@{Action='Verify';RunId=$session}}
    Invoke-Step 'redis-selection' 'redis' $clock @{Output="$out/redis-selection.json"}
    $redis=(Get-Content -Encoding UTF8 "$out/redis-selection.json" -Raw|ConvertFrom-Json).Identity
    Invoke-Step 'mysql-environment' 'script' $clock @{File="$PSScriptRoot/mysql-test-instance.ps1";Parameters=@{Action='Environment';RunId=$session;RedisDatabase=$redis.Database}}
    $buildArgs=@('phase18ToolsClasspath',"-Pphase18ClasspathFile=$out/classpath.txt")+$work.GradleArguments
    Save-New "$out/build-command.json" $buildArgs
    Invoke-Step 'build' 'native' $clock @{File="$repo/gradlew.bat";Arguments=$buildArgs}
    $null=Invoke-Budget 'budget-after-build' $clock
    $planArgs=@('-B',"$PSScriptRoot/new-phase18-cap1-plan.py",'--environment',"$out/environment-environment.json",'--predecessor',$policyPath,'--issued',"$out/cap-issued.json",'--run-id',$runId)
    Invoke-Step 'plan-generation' 'native' $clock @{File=$python;Arguments=$planArgs}
    $planIdentity=Get-Content "$out/plan-cap.json" -Raw -Encoding UTF8|ConvertFrom-Json
    $planClasspath=[IO.File]::ReadAllText("$out/classpath.txt").Trim()
    Invoke-Step 'plan' 'native' $clock @{File=$work.Java;Arguments=@('-Xmx128m',"-Djava.io.tmpdir=$($planIdentity.ownership.temp)","-Djna.tmpdir=$($planIdentity.ownership.jna)","-Dphase18.runtimeReceipt=$out/classpath.txt.json","-Dphase18.deadlineEpochMillis=$expires",'-cp',$planClasspath,'dev.cgt.benchmark.Phase18Main','--action','Plan','--plan',"$out/plan-cap.json")}
    . "$PSScriptRoot/harness-local-secrets.ps1"
    Import-HarnessLocalSecrets -Root $repo
    $application=[IO.File]::ReadAllText("$repo/src/main/resources/application.yml")
    $users=[regex]::Matches($application,'(?m)^    username: ([A-Za-z0-9_]+)\s*$')
    if($users.Count -ne 1 -or -not $env:DB_PASSWORD){throw 'Existing secret injection required'}
    $env:PIXEL_PLACE_BENCH_DB_HOST='127.0.0.1';$env:PIXEL_PLACE_BENCH_DB_PORT='3307';$env:PIXEL_PLACE_BENCH_DB_USERNAME=$users[0].Groups[1].Value;$env:PIXEL_PLACE_BENCH_DB_PASSWORD=$env:DB_PASSWORD
    $env:PIXEL_PLACE_BENCH_REDIS_HOST='127.0.0.1';$env:PIXEL_PLACE_BENCH_REDIS_PORT='6379';$env:PIXEL_PLACE_BENCH_REDIS_DATABASE=[string]$redis.Database;$env:PIXEL_PLACE_BENCH_PRODUCTION_REDIS_DATABASE='0'
    $hostCount=[int]$profile.totalSeconds
    $hostSpec=New-StepRequest 'host' 'script' $clock @{File="$PSScriptRoot/observe-phase18-host.ps1";Parameters=@{Output="$out/host.jsonl";Samples=$hostCount;IntervalMillis=1000;ExecutionExpiry=$expires;StopFile="$out/host-stop.json"}}
    $hostProcess=Start-CapWorker $hostSpec.Request $hostSpec.Path $clock $owned
    try{
        $runParameters=@{Action='Run';PlanPath="$out/plan-cap.json";Java=$work.Java;ClasspathFile="$out/classpath.txt";EnvironmentFile="$out/environment-environment.json";ObservationPolicy="$out/policy-cap.json"}
        if($K6){$runParameters.Action='ProduceK6';$runParameters.K6=$K6}
        Invoke-Step 'run' 'script' $clock @{File="$PSScriptRoot/measure-phase18.ps1";Parameters=$runParameters}
        $runExit=0
    }catch{$runExit=1;$failure=$_.Exception}
    if(-not(Test-Path "$out/host-stop.json")){Save-New "$out/host-stop.json" @{Reason='load and recovery completed';OriginalExpiresEpochMillis=$expires}}
    Wait-CapWorker $hostProcess $clock | Out-Null
    if((Test-Path "$out/$runId/initial/raw.jsonl") -or ($K6 -and (Test-Path "$out/$runId/initial/k6-input.json"))){
        $analysisArgs=@('-B',"$PSScriptRoot/analyze-phase18.py",'--plan',"$out/plan-cap.json",'--run-dir',"$out/$runId/initial",'--output',"$out/analysis.json",'--host-disk',"$out/host.jsonl",'--devices',"$out/devices.json",'--execution-expiry',[string]$expires)
        Save-New "$out/analysis-command.json" $analysisArgs
        try{Invoke-Step 'analysis' 'native' $clock @{File=$python;Arguments=$analysisArgs};$analysisExit=0}catch{$analysisExit=1;throw}
        $analysis=Get-Content -Encoding UTF8 "$out/analysis.json" -Raw|ConvertFrom-Json
        if(-not $analysis.integrity.complete -or $analysis.cap.verdict -notin @('PASS','PERFORMANCE_MISS','INPUT_LIMIT','TOOL_LIMIT')){throw 'CAP1 required execution/integrity evidence incomplete'}
    }else{throw 'CAP initial raw absent'}
    if($failure){throw $failure}
}catch{if(-not $failure){$failure=$_.Exception}}
finally {
    try{
        if($createdSession){
            # Existing 180s failure cleanup observation budget; never renew the trial expiry.
            $cleanupClock=$clock
            if((Get-CapRemaining $clock) -gt 0){$cleanupClock=New-CapClock ([Math]::Min($clock.Expires,[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+180000))}
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
                if($budget -and @($owned|Where-Object {-not(Test-CapJoined $_)}).Count -eq 0){
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
            Save-CapClosure "$out/cap-closure.json" @{SessionId=$session;RunId=$runId;Stage='CAP-1';Role=$policy.role;ConditionsHash=$policy.conditionsHash;Status=if($failure){'FAILED'}else{$analysis.cap.verdict};FailureType=if($failure){$failure.GetType().Name}else{$null};FailureMessage=if($failure){$failure.Message}else{$null};RunExit=$runExit;AnalysisExit=$analysisExit;NormalMysqlStop=$normalStop;OwnedResourcesClosed=$closed;OwnedWorkers=$owners;ServiceMillis=$serviceUsed;ServiceMillisIsLowerBound=(-not $closed);ServiceMillisCumulative=$serviceMillis+$serviceUsed;RecordedServiceMillisCumulativeFloor=if($resume){$resume.RecordedServiceMillisFloor+$serviceUsed}else{$serviceMillis+$serviceUsed};HistoricalServiceTimeUncertain=if($resume){$resume.HistoricalServiceTimeUncertain}else{$false};FinishedEpochMillis=$finished;Budget=$budgetAfter;BudgetAfterVerified=($null -ne $budgetAfter);OriginalExpiresEpochMillis=$expires;CleanupExpiresEpochMillis=$cleanupClock.Expires;ResumeApprovalHash=if($resume){$resume.Approval.sha256}else{$null};ResumePolicyHash=if($resume){$resume.Policy.sha256}else{$null};EvidenceIndex=$evidenceIndex} $probe
            $closureSaved=$true
            Write-Output "CAP1_SESSION=$session"
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
