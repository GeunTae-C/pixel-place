param([Parameter(Mandatory=$true)][string]$EvidenceRoot,[Parameter(Mandatory=$true)][string]$ApprovalTemplate,[string]$S2BindingCandidate,[string]$AdditionalApprovalTemplate,[switch]$AdditionalOnly,[string]$CompletionApprovalTemplate,[switch]$CompletionOnly)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/phase18-cap-resume.ps1"
$repo=Split-Path $PSScriptRoot -Parent
$realRoot="$repo/build/agent-runs/phase18-cap0-prep-20261004-181654"
$realApproval=$ApprovalTemplate
$template=Get-Content $realApproval -Raw -Encoding UTF8|ConvertFrom-Json
$latest=$template.schemaVersion -eq 3
$fixtureSession=if($latest){'phase18-cap0-s1-20261012-041711'}else{'phase18-resumed-s1'}
$fixtureRun=if($latest){'cap0-s1-20261012-041711'}else{'resumed-s1'}
$tokens=$null;$parseErrors=$null
$controllerAst=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/run-phase18-cap-smoke.ps1",[ref]$tokens,[ref]$parseErrors)
$gateCall=@($controllerAst.FindAll({param($n)$n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Get-CapResumeContext'},$true))
if($parseErrors.Count -or $gateCall.Count -ne 1){throw 'Controller resume call missing or invalid'}
foreach($name in @('S1ClosureSupplement','S1ClosureSupplementHash')){if($name -notin $controllerAst.ParamBlock.Parameters.Name.VariablePath.UserPath){throw 'Controller supplement argument missing'}}
$successorGuard=@($controllerAst.FindAll({param($n)$n -is [Management.Automation.Language.IfStatementAst] -and $n.Extent.Text.StartsWith('if($S1ClosureSupplement -and $resume.Policy.sha256')},$true))
if($successorGuard.Count -ne 1){throw 'Controller successor execution guard missing'}
$controllerGate=[scriptblock]::Create('$resume='+$gateCall[0].Extent.Text+"`n"+$successorGuard[0].Extent.Text+"`n"+'$resume')
$historyCount=@($template.history).Count
$authId=$template.authorizationId
New-Item -ItemType Directory -Path $EvidenceRoot -ErrorAction Stop|Out-Null
$results=[Collections.Generic.List[object]]::new()
function Write-Fixture($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))}
function New-Fixture([string]$Name,[switch]$S2){
 $r="$EvidenceRoot/$Name/repo";$l="$r/build/agent-runs/ledger";New-Item -ItemType Directory -Path $l -Force|Out-Null
 $a=Get-Content $realApproval -Raw -Encoding UTF8|ConvertFrom-Json
 $a.repo=[IO.Path]::GetFullPath($r);$a.ledgerRoot=[IO.Path]::GetFullPath($l)
 for($i=0;$i -lt $historyCount;$i++){
  $h=$a.history[$i];$v=Read-CapBoundFile $h.attempt
  Copy-Item -LiteralPath $h.attempt.path -Destination "$l/attempt-$($i+1).json"
  $h.attempt=Get-CapReference "$l/attempt-$($i+1).json"
  $d="$r/build/agent-runs/$($v.SessionId)";New-Item -ItemType Directory -Path $d|Out-Null
  Copy-Item -LiteralPath $h.issued.path -Destination "$d/cap-issued.json";$h.issued=Get-CapReference "$d/cap-issued.json"
 }
 $ap="$l/approval.json";Write-Fixture $ap $a;$ar=Get-CapReference $ap
 New-Item -ItemType Directory -Path "$r/scripts"|Out-Null
 [IO.File]::WriteAllText("$r/scripts/fixture.ps1",'historical source fixture')
 $source=@{'scripts/fixture.ps1'=(Get-CapReference "$r/scripts/fixture.ps1").sha256}
 $pol=@{resumeApproval=$ar;previousPolicy=$a.previousPolicy;sourceBinding=$source;expiry=@{smokeMillis=2400000;hardTrialSeconds=3600};storage=@{reservationF=5368709120L}};Write-Fixture "$l/policy.json" $pol;$pr=Get-CapReference "$l/policy.json"
 Write-Fixture "$l/cap-resume-registration.json" @{approval=$ar;policy=$pr}
 $priorFailure=Read-CapBoundFile $a.history[2].continuation
 $baseCharge=150511L+$priorFailure.ServiceMillisBudgetCharge
 if($latest){$baseCharge+=138546L}
 $f=@{BaseCharge=$baseCharge;Root=$l;Repo=$r;Approval=$ap;Hash=$ar.sha256;Context=@{Approval=$ar;Policy=$pr};Smoke=if($S2){'S2'}else{'S1'}}
 if($S2){
  $at=@{SessionId=$fixtureSession;RunId=$fixtureRun;Smoke='S1';ResumeApprovalHash=$ar.sha256;ResumePolicyHash=$pr.sha256;IssuedEpochMillis=1000L;ExpiresEpochMillis=2401000L}
  Write-Fixture "$l/attempt-$($historyCount+1).json" $at
  Write-Fixture "$l/cap-resume-$authId-S1.json" $at
  $out="$r/build/agent-runs/$($at.SessionId)";New-Item -ItemType Directory -Path "$out/$fixtureRun/initial","$out/$fixtureRun/recovery" -Force|Out-Null
  foreach($rel in @('cap-issued.json','policy-cap.json','classpath.txt','classpath.txt.json','run-exit.json','analysis-exit.json','mysql-stop.json','closure-probe.json','budget-after.json',"$fixtureRun/initial/raw.jsonl","$fixtureRun/initial/manifest.json","$fixtureRun/recovery/manifest.json")){Write-Fixture "$out/$rel" @{SessionId=$at.SessionId}}
  Write-Fixture "$out/cap-issued.json" $at
  $inputs=@(@{path='scripts/fixture.ps1';sha256=$source['scripts/fixture.ps1']})
  Write-Fixture "$out/plan-cap.json" @{predecessor=$pr;inputs=$inputs}
  Write-Fixture "$out/classpath.txt.json" @{inputs=$inputs;runtime=@(@{path='F:/isolated-runtime.class';sha256=('1'*64)})}
  foreach($stage in @('initial','recovery')){Write-Fixture "$out/$fixtureRun/$stage/manifest.json" @{complete=$true;normalShutdown=$true;planHash=(Get-CapReference "$out/plan-cap.json").sha256;runtimeReceiptHash=(Get-CapReference "$out/classpath.txt.json").sha256}}
  $probe=@{OwnedJava=@();Remaining3307=0;RemainingRunLock=$false}
  Write-Fixture "$out/closure-probe.json" $probe
  Write-Fixture "$out/mysql-stop.json" @{RunId=$at.SessionId;SqlExit=0;NormalShutdown=$true;ShutdownObserved=$true;Failures=@()}
  Write-Fixture "$out/run-exit.json" @{Exit=0};Write-Fixture "$out/analysis-exit.json" @{Exit=0}
  Write-Fixture "$out/analysis.json" @{cap=@{smokeVerification='PASS';capacityPoint=$false};integrity=@{complete=$true;recoveryComplete=$true;initialComplete=$true;drainComplete=$true;wsComplete=$true}}
  $budget=Get-CapReference "$out/budget-after.json"
  $index=Save-CapExecutionEvidence $out $at.RunId
  $c=@{SessionId=$at.SessionId;RunId=$at.RunId;Smoke='S1';Status='PASS';RunExit=0;AnalysisExit=0;OwnedResourcesClosed=$true;NormalMysqlStop=$true;Remaining3307=0;RemainingRunLock=$false;OriginalExpiresEpochMillis=$at.ExpiresEpochMillis;FinishedEpochMillis=2000L;ServiceMillisIsLowerBound=$false;ServiceMillis=1000;ServiceMillisCumulative=($baseCharge+1000);BudgetAfterVerified=$true;Budget=@{ledger=$budget.path;sha256=$budget.sha256;gateMet=$true};ResumeApprovalHash=$ar.sha256;ResumePolicyHash=$pr.sha256;EvidenceIndex=$index;OwnedWorkers=@(@{Joined=$true;Released=$true;DbUsersClosed=$true;ActualExit=0;FirstFailure=$null})}
  Save-CapClosure "$out/cap-closure.json" $c $probe;$f.Out=$out;$f.Closure=$c
 }
 return $f
}
function Gate($f){
 $prior=@(Get-ChildItem $f.Root -Filter 'attempt-*.json'|ForEach-Object {Get-Content $_.FullName -Raw -Encoding UTF8|ConvertFrom-Json})
 $LedgerRoot=$f.Root;$repo=$f.Repo;$ResumeApproval=$f.Approval;$ResumeApprovalHash=$f.Hash;$Smoke=$f.Smoke
 $S1ClosureSupplement=$f.SupplementPath;$S1ClosureSupplementHash=$f.SupplementHash
 # controller 시작/잠금/서비스는 실행하지 않고 실제 source의 gate 호출문과 인자 전달을 실행
 & $controllerGate
}
function Check([string]$Name,[bool]$Accept,[string]$Expected,[scriptblock]$Mutate,[switch]$S2){
 $f=New-Fixture $Name -S2:$S2
 if($Mutate){& $Mutate $f}
 $ok=$false;$message=$null
 try{$g=Gate $f;$ok=$true}catch{$message=$_.Exception.Message}
 $pass=($ok -eq $Accept) -and ($Accept -or $message -like "*$Expected*")
 $results.Add(@{Case=$Name;ExpectedAccept=$Accept;Accepted=$ok;Message=$message;Pass=$pass})
 if(-not $pass){throw "Resume test $Name failed: $message"}
}
if(-not $AdditionalOnly -and -not $CompletionOnly){
Check 'approved-s1' $true '' {param($f)}
Check 'missing-approval' $false 'not registered' {param($f)$f.Approval="$($f.Root)/absent.json"}
Check 'hash-tamper' $false 'not registered' {param($f)$f.Hash='0'*64}
Check 'content-tamper' $false 'hash mismatch' {param($f)[IO.File]::AppendAllText($f.Approval,' ')}
Check 'history-tamper' $false 'hash mismatch' {param($f)[IO.File]::AppendAllText("$($f.Root)/attempt-1.json",' ')}
Check 'slot-consumed' $false 'slot already consumed' {param($f)Write-Fixture "$($f.Root)/cap-resume-$authId-S1.json" @{claimed=$true}}
Check 'issued-orphan' $false 'unregistered issued' {param($f)$d="$($f.Repo)/build/agent-runs/orphan";New-Item -ItemType Directory $d|Out-Null;Write-Fixture "$d/cap-issued.json" @{SessionId='orphan'}}
Check 's2-before-s1' $false 'history mismatch' {param($f)$f.Smoke='S2'}
Check 's1-failed' $false 'must pass all closure' {param($f)$f.Closure.Status='FAILED';Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-incomplete-budget' $false 'must pass all closure' {param($f)$f.Closure.BudgetAfterVerified=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-unresolved' $false 'resources unresolved' {param($f)$f.Closure.OwnedResourcesClosed=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-worker-unresolved' $false 'workers not normally' {param($f)$f.Closure.OwnedWorkers[0].Joined=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 'approved-s2' $true '' {param($f)} -S2
Check 's2-duplicate-slot' $false 'slot already consumed' {param($f)Write-Fixture "$($f.Root)/cap-resume-$authId-S2.json" @{claimed=$true}} -S2
Check 'total-exhausted' $false 'attempts exhausted' {param($f)Write-Fixture "$($f.Root)/attempt-$($historyCount+2).json" @{Smoke='S2'}} -S2
Check 'time-exhausted' $false 'service reservation exhausted' {param($f)$f.Closure.ServiceMillis=1200000;$f.Closure.ServiceMillisCumulative=$f.BaseCharge+1200000;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-storage-exhausted' $false 'storage reservation failed' {param($f)$f.Closure.Budget.gateMet=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-raw-changed' $false 'execution evidence changed' {param($f)[IO.File]::AppendAllText("$($f.Out)/$fixtureRun/initial/raw.jsonl",' ')} -S2
Check 's1-recovery-absent' $false 'evidence index' {param($f)$f.Closure.EvidenceIndex=$null;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-analysis-incomplete' $false 'formal analysis/recovery incomplete' {param($f)
 $p="$($f.Out)/analysis.json";$a=Get-Content $p -Raw|ConvertFrom-Json;$a.integrity.recoveryComplete=$false;Write-Fixture $p $a
 $i=Get-Content "$($f.Out)/execution-evidence.json" -Raw|ConvertFrom-Json
 foreach($r in $i.Files){if($r.path -ieq [IO.Path]::GetFullPath($p)){$r.sha256=(Get-CapReference $p).sha256}}
 Write-Fixture "$($f.Out)/execution-evidence.json" $i;$f.Closure.EvidenceIndex=Get-CapReference "$($f.Out)/execution-evidence.json";Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure
} -S2
Check 's1-analysis-not-smoke' $false 'formal analysis/recovery incomplete' {param($f)
 $p="$($f.Out)/analysis.json";$a=Get-Content $p -Raw|ConvertFrom-Json;$a.cap.capacityPoint=$true;Write-Fixture $p $a
 $i=Get-Content "$($f.Out)/execution-evidence.json" -Raw|ConvertFrom-Json
 foreach($r in $i.Files){if($r.path -ieq [IO.Path]::GetFullPath($p)){$r.sha256=(Get-CapReference $p).sha256}}
 Write-Fixture "$($f.Out)/execution-evidence.json" $i;$f.Closure.EvidenceIndex=Get-CapReference "$($f.Out)/execution-evidence.json";Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure
} -S2
Check 'approved-k6-s2' $true '' {param($f)
 Move-Item -LiteralPath "$($f.Out)/$fixtureRun/initial/raw.jsonl" -Destination "$($f.Out)/$fixtureRun/initial/k6-records.jsonl"
 $p="$($f.Out)/analysis.json";$a=Get-Content $p -Raw|ConvertFrom-Json
 $a|Add-Member -NotePropertyName inputKind -NotePropertyValue 'phase18-k6-v2';Write-Fixture $p $a
 $files=@(Get-ChildItem -LiteralPath $f.Out -File -Recurse|Where-Object {$_.Name -notin @('execution-evidence.json','cap-closure.json')}|ForEach-Object {Get-CapReference $_.FullName})
 Write-Fixture "$($f.Out)/execution-evidence.json" @{RunId=$fixtureRun;Files=$files}
 $f.Closure.EvidenceIndex=Get-CapReference "$($f.Out)/execution-evidence.json";Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure
} -S2
# 새 v2도 과거 실패·별도 상한·미확정 정상 종료를 조작하면 거부
function Rebind-Approval($f,$a) {
 Write-Fixture $f.Approval $a;$ref=Get-CapReference $f.Approval;$f.Hash=$ref.sha256
 $p=Get-Content "$($f.Root)/policy.json" -Raw|ConvertFrom-Json;$p.resumeApproval=$ref
 Write-Fixture "$($f.Root)/policy.json" $p
 Write-Fixture "$($f.Root)/cap-resume-registration.json" @{approval=$ref;policy=(Get-CapReference "$($f.Root)/policy.json")}
}
if(-not $latest){
Check 'v2-s1-cap-not-transferable' $false 'frozen limits' {param($f)$a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$a.s1Max=5;Rebind-Approval $f $a}
Check 'v2-time-cap-unchanged' $false 'frozen limits' {param($f)$a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$a.serviceMillisMax=7200000;Rebind-Approval $f $a}
Check 'v2-original-floor-retained' $false 'frozen limits' {param($f)$a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$a.baselineServiceMillis=150511;Rebind-Approval $f $a}
Check 'v2-no-retroactive-normal-stop' $false 'continuation binding' {param($f)
 $a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$c=Read-CapBoundFile $a.history[2].continuation
 $c.NormalShutdownConfirmed=$true;Write-Fixture "$($f.Root)/continuation.json" $c
 $a.history[2].continuation=Get-CapReference "$($f.Root)/continuation.json";Rebind-Approval $f $a
}
Check 'v2-live-resource-refused' $false 'current absence/lock' {param($f)
 $a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$c=Read-CapBoundFile $a.history[2].continuation;$o=Read-CapBoundFile $c.CurrentObservation
 $o.CurrentResourcesAbsent=$false;Write-Fixture "$($f.Root)/observation.json" $o;$c.CurrentObservation=Get-CapReference "$($f.Root)/observation.json"
 Write-Fixture "$($f.Root)/continuation.json" $c;$a.history[2].continuation=Get-CapReference "$($f.Root)/continuation.json";Rebind-Approval $f $a
}
Check 'v2-charge-no-refund' $false 'time accounting' {param($f)
 $a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$c=Read-CapBoundFile $a.history[2].continuation;$c.ServiceMillisBudgetCharge=37327
 Write-Fixture "$($f.Root)/continuation.json" $c;$a.history[2].continuation=Get-CapReference "$($f.Root)/continuation.json";Rebind-Approval $f $a
}
Check 'v2-release-required' $false 'Release incomplete' {param($f)$f.Closure.OwnedWorkers[0].Released=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
}
if($latest){
Check 'v3-no-sixth-s1' $false 'frozen limits' {param($f)$a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$a.s1Max=6;Rebind-Approval $f $a}
Check 'v3-no-time-increase' $false 'frozen limits' {param($f)$a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$a.serviceMillisMax=7200000;Rebind-Approval $f $a}
Check 'v3-no-floor-refund' $false 'frozen limits' {param($f)$a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$a.baselineServiceMillis=187838;Rebind-Approval $f $a}
Check 'v3-release-required' $false 'Release incomplete' {param($f)$f.Closure.OwnedWorkers[0].Released=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
foreach($case in @('live','refund','no-sql-log','no-stop-release','no-db-users','different-sql-pid')){
 $expected=switch($case){'live'{'current absence/lock'}'refund'{'continuation binding/accounting'}'no-sql-log'{'SQL log normal closure'}'no-stop-release'{'supplemental Stop unresolved'}'no-db-users'{'supplemental Stop unresolved'}'different-sql-pid'{'SQL identity mismatch'}}
 Check "v3-$case" $false $expected {
  param($f)
  $a=Get-Content $f.Approval -Raw|ConvertFrom-Json;$c=Read-CapBoundFile $a.history[3].continuation
  switch($case){
   'live'{$o=Read-CapBoundFile $c.CurrentObservation;$o.CurrentResourcesAbsent=$false;Write-Fixture "$($f.Root)/observation.json" $o;$c.CurrentObservation=Get-CapReference "$($f.Root)/observation.json"}
   'refund'{$c.ServiceMillisBudgetCharge=0}
   'no-stop-release'{$o=Read-CapBoundFile $c.StopWorkerClosure;$o.Released=$false;Write-Fixture "$($f.Root)/worker.json" $o;$c.StopWorkerClosure=Get-CapReference "$($f.Root)/worker.json"}
   'no-db-users'{$o=Read-CapBoundFile $c.StopPrecondition;$o.DbUsersClosed=$false;Write-Fixture "$($f.Root)/pre.json" $o;$c.StopPrecondition=Get-CapReference "$($f.Root)/pre.json"}
   'different-sql-pid'{$o=Read-CapBoundFile $c.StopPrecondition;$o.VerifiedServices[1].Pid=1;Write-Fixture "$($f.Root)/pre.json" $o;$c.StopPrecondition=Get-CapReference "$($f.Root)/pre.json"}
   'no-sql-log'{
    $log=(Get-Content $c.ServerLogEvidence.path -Raw).Replace('Normal shutdown.','No normal shutdown evidence')
    [IO.File]::WriteAllText("$($f.Root)/server-excerpt.txt",$log);$c.ServerLogEvidence=Get-CapReference "$($f.Root)/server-excerpt.txt"
    $o=Read-CapBoundFile $c.HistoricalObservation;$o.ServerLogEvidence=$c.ServerLogEvidence;Write-Fixture "$($f.Root)/historical.json" $o;$c.HistoricalObservation=Get-CapReference "$($f.Root)/historical.json"
   }
  }
  Write-Fixture "$($f.Root)/continuation.json" $c;$a.history[3].continuation=Get-CapReference "$($f.Root)/continuation.json";Rebind-Approval $f $a
 }
}
}
# 실제 승인/장부를 쓰지 않는 새 정책 결합 경계. S1 마감 뒤에는 별도 S2 예약이 필요.
foreach($case in @('bound-s1','bound-s2','wrong-smoke-reservation','binary-tamper','missing-peak')){
 $d="$EvidenceRoot/binding-$case";New-Item -ItemType Directory -Path $d|Out-Null
 $binary="$d/k6.exe";[IO.File]::WriteAllText($binary,'finite binding fixture')
 $budget=@{schemaVersion=2;scope='k6-mod3';additionalPeak=@{evidence=201326592L;graph=0;work=2147483648L;cache=1073741824L;mysql=2147483648L;wal=67108864L};transientAllowance=@{C=0;F=1073741824L}}
 if($case -eq 'missing-peak'){$budget.additionalPeak.mysql=0}
 Write-Fixture "$d/S1.json" $budget;Write-Fixture "$d/S2.json" $budget
 $policy=@{k6=@{inputKind='phase18-k6-v2';executable=(Get-CapReference $binary);script=(Get-CapReference "$PSScriptRoot/phase18-k6.js");reservations=@{S1=(Get-CapReference "$d/S1.json");S2=(Get-CapReference "$d/S2.json")}}}
 if($case -eq 'binary-tamper'){[IO.File]::AppendAllText($binary,'changed')}
 $smoke=if($case -in @('bound-s2','wrong-smoke-reservation')){'S2'}else{'S1'}
 $reservation=if($case -eq 'wrong-smoke-reservation'){"$d/S1.json"}else{"$d/$smoke.json"}
 $message=$null;try{Assert-CapK6Binding $policy $binary $reservation $smoke}catch{$message=$_.Exception.Message}
 $expected=switch($case){'bound-s2'{'Frozen S2 output profile required'}'wrong-smoke-reservation'{'binding required'}'binary-tamper'{'binary/script changed'}'missing-peak'{'peak reservation required'}default{$null}}
 $pass=if($expected){$message -like "*$expected*"}else{$null -eq $message}
 $results.Add(@{Case=$case;Message=$message;Pass=$pass});if(-not $pass){throw "Binding test $case failed: $message"}
}
if($latest) {
 function Result($Name,$Pass,$Message){$results.Add(@{Case=$Name;Pass=$Pass;Message=$Message});if(-not $Pass){throw "Closure test $Name failed: $Message"}}
 # 제품 생성/CREATE_NEW/재읽기에서 배열 크기와 identity 보존, 잘못된 관측의 의미 보존
 foreach($case in @('zero','one','many','probe-absent','field-absent','null','object','string','remaining-port','missing-lock')) {
  $f=New-Fixture "serialize-$case" -S2
  $probe=@{OwnedJava=@();Remaining3307=0;RemainingRunLock=$false}
  switch($case) {
   'one'{$probe.OwnedJava=@(@{Pid=17;StartUtc='2026-10-12T00:00:00Z';Binary='F:/owned/java.exe'})}
   'many'{$probe.OwnedJava=@(@{Pid=17;StartUtc='a';Binary='F:/a/java.exe'},@{Pid=23;StartUtc='b';Binary='F:/b/java.exe'})}
   'probe-absent'{$probe=$null}
   'field-absent'{$probe.Remove('OwnedJava')}
   'null'{$probe.OwnedJava=$null}
   'object'{$probe.OwnedJava=@{}}
   'string'{$probe.OwnedJava=''}
   'remaining-port'{$probe.Remaining3307=1}
   'missing-lock'{$probe.Remove('RemainingRunLock')}
  }
  $c=$f.Closure
  Save-CapClosure "$($f.Out)/saved.json" $c $probe
  $read=Get-Content "$($f.Out)/saved.json" -Raw|ConvertFrom-Json
  if($case -in @('zero','one','many')) {
   $count=switch($case){'zero'{0}'one'{1}'many'{2}}
   $identity=($read.OwnedJava|ConvertTo-Json -Compress) -ceq ($probe.OwnedJava|ConvertTo-Json -Compress)
   Result "array-$case" ($read.OwnedJava -is [array] -and $read.OwnedJava.Count -eq $count -and $identity) 'JSON array count/identity'
  }
  # 저장된 bytes를 실제 S2 선행 파일로 전달. 정상 객체를 다시 생성하지 않음
  [IO.File]::WriteAllBytes("$($f.Out)/cap-closure.json",[IO.File]::ReadAllBytes("$($f.Out)/saved.json"))
  $message=$null;try{$null=Gate $f}catch{$message=$_.Exception.Message}
  $pass=if($case -eq 'zero'){$null -eq $message}else{$message -like '*resources unresolved*' -and -not $read.OwnedResourcesClosed -and $read.Status -ceq 'FAILED'}
  Result "saved-to-s2-$case" $pass $message
 }
 $f=New-Fixture 'first-failure-preserved' -S2
 $f.Closure.Status='FAILED';$f.Closure.FailureMessage='original timeout';$f.Closure.FailureType='TimeoutException';$f.Closure.RunExit=7
 Save-CapClosure "$($f.Out)/failed.json" $f.Closure $null
 $read=Get-Content "$($f.Out)/failed.json" -Raw|ConvertFrom-Json
 Result 'first-failure-and-exit-preserved' ($read.FailureMessage -ceq 'original timeout' -and $read.FailureType -ceq 'TimeoutException' -and $read.RunExit -eq 7 -and $read.ServiceMillis -eq 1000) 'Original failure/exit/time'
 $hash=(Get-CapReference "$($f.Out)/failed.json").sha256;$message=$null
 try{Save-CapClosure "$($f.Out)/failed.json" $f.Closure $null}catch{$message=$_.Exception.Message}
 Result 'create-new-preserved' ($null -ne $message -and (Get-CapReference "$($f.Out)/failed.json").sha256 -ceq $hash) $message

 function Bind-Supplement($f) {
  $c=$f.Closure;$c.OwnedJava=[pscustomobject]@{}
  Write-Fixture "$($f.Out)/cap-closure.json" $c
  $s=@{schemaVersion=1;kind='cap-s1-ownedjava-empty-array';field='OwnedJava';SessionId=$fixtureSession;RunId=$fixtureRun;AttemptNumber=5;Reason='PS5 conditional empty array serialized as object; indexed raw probe is empty array';Approval=(Get-CapReference $f.Approval);Policy=$f.Context.Policy;Attempt=(Get-CapReference "$($f.Root)/attempt-5.json");Slot=(Get-CapReference "$($f.Root)/cap-resume-$authId-S1.json");Closure=(Get-CapReference "$($f.Out)/cap-closure.json");EvidenceIndex=$c.EvidenceIndex;Probe=(Get-CapReference "$($f.Out)/closure-probe.json")}
  $f.SupplementPath="$($f.Root)/s1-supplement.json";Save-CapJson $f.SupplementPath $s;$f.SupplementHash=(Get-CapReference $f.SupplementPath).sha256
 }
 function Refresh-Index($f) {
  $refs=@(Get-ChildItem -LiteralPath $f.Out -File -Recurse|Where-Object {$_.Name -notin @('cap-closure.json','execution-evidence.json')}|ForEach-Object {Get-CapReference $_.FullName})
  Write-Fixture "$($f.Out)/execution-evidence.json" @{RunId=$fixtureRun;Files=$refs};$f.Closure.EvidenceIndex=Get-CapReference "$($f.Out)/execution-evidence.json"
 }
 function Successor($f) {
  $p=Get-Content "$($f.Root)/policy.json" -Raw|ConvertFrom-Json
  [IO.File]::WriteAllText("$($f.Repo)/scripts/fixture.ps1",'current source fixture')
  $p.sourceBinding.'scripts/fixture.ps1'=(Get-CapReference "$($f.Repo)/scripts/fixture.ps1").sha256
  $p|Add-Member -NotePropertyName s1Predecessor -NotePropertyValue $f.Context.Policy
  $p|Add-Member -NotePropertyName s1ClosureSupplement -NotePropertyValue (Get-CapReference $f.SupplementPath)
  Save-CapJson "$($f.Root)/successor.json" $p
  Write-Fixture "$($f.Root)/cap-resume-registration.json" @{approval=(Get-CapReference $f.Approval);policy=(Get-CapReference "$($f.Root)/successor.json")}
 }
 foreach($case in @('valid-original-policy','valid-successor','missing-supplement','wrong-run','wrong-original-hash','probe-tamper','index-tamper','probe-not-indexed','probe-file-missing','raw-java','raw-port','raw-null','nested-contradiction','failed-s1','stop-incomplete','recovery-incomplete','release-incomplete','worker-exit','run-exit','budget-incomplete','storage-exhausted','time-exhausted','duplicate-s2','extra-s1','wrong-successor-link','changed-successor-limits','successor-no-supplement','source-tamper','historical-source-tamper','deadline-exceeded','pass-with-failure','field-null','field-missing','slot-identity','partial-supplement','extra-s1-no-supplement','changed-successor-budget')) {
  $f=New-Fixture "supplement-$case" -S2
  $expected=$null
  switch($case) {
   'probe-not-indexed'{$i=Get-Content "$($f.Out)/execution-evidence.json" -Raw|ConvertFrom-Json;$i.Files=@($i.Files|Where-Object {$_.path -notlike '*closure-probe.json'});Write-Fixture "$($f.Out)/execution-evidence.json" $i;$f.Closure.EvidenceIndex=Get-CapReference "$($f.Out)/execution-evidence.json";$expected='probe missing from original index'}
   'raw-java'{$p=Get-Content "$($f.Out)/closure-probe.json" -Raw|ConvertFrom-Json;$p.OwnedJava=@(@{Pid=17});Write-Fixture "$($f.Out)/closure-probe.json" $p;Refresh-Index $f;$expected='raw resources unresolved'}
   'raw-port'{$p=Get-Content "$($f.Out)/closure-probe.json" -Raw|ConvertFrom-Json;$p.Remaining3307=1;Write-Fixture "$($f.Out)/closure-probe.json" $p;Refresh-Index $f;$expected='raw resources unresolved'}
   'raw-null'{$p=Get-Content "$($f.Out)/closure-probe.json" -Raw|ConvertFrom-Json;$p.OwnedJava=$null;Write-Fixture "$($f.Out)/closure-probe.json" $p;Refresh-Index $f;$expected='raw resources unresolved'}
   'nested-contradiction'{$f.Closure.Probe.RemainingRunLock=$true;$expected='contradictory probe'}
   'failed-s1'{$f.Closure.Status='FAILED';$expected='must pass all closure'}
   'stop-incomplete'{$p=Get-Content "$($f.Out)/mysql-stop.json" -Raw|ConvertFrom-Json;$p.NormalShutdown=$false;Write-Fixture "$($f.Out)/mysql-stop.json" $p;Refresh-Index $f;$expected='SQL stop incomplete'}
   'recovery-incomplete'{$p=Get-Content "$($f.Out)/$fixtureRun/recovery/manifest.json" -Raw|ConvertFrom-Json;$p.complete=$false;Write-Fixture "$($f.Out)/$fixtureRun/recovery/manifest.json" $p;Refresh-Index $f;$expected='runtime/recovery incomplete'}
   'release-incomplete'{$f.Closure.OwnedWorkers[0].Released=$false;$expected='Release incomplete'}
   'worker-exit'{$f.Closure.OwnedWorkers[0].ActualExit=7;$expected='worker closure incomplete'}
   'run-exit'{Write-Fixture "$($f.Out)/run-exit.json" @{Exit=7};Refresh-Index $f;$expected='actual exit incomplete'}
   'budget-incomplete'{$f.Closure.BudgetAfterVerified=$false;$expected='must pass all closure'}
   'storage-exhausted'{$f.Closure.Budget.gateMet=$false;$expected='storage reservation failed'}
   'time-exhausted'{$f.Closure.ServiceMillis=1200000;$f.Closure.ServiceMillisCumulative=$f.BaseCharge+1200000;$expected='service reservation exhausted'}
   'duplicate-s2'{Write-Fixture "$($f.Root)/cap-resume-$authId-S2.json" @{claimed=$true};$expected='slot already consumed'}
   'extra-s1'{$f.Smoke='S1';$expected='requires complete S2 schema3 binding'}
   'historical-source-tamper'{$p=Get-Content "$($f.Out)/classpath.txt.json" -Raw|ConvertFrom-Json;$p.inputs[0].sha256='0'*64;Write-Fixture "$($f.Out)/classpath.txt.json" $p;Refresh-Index $f;$expected='historical source binding mismatch'}
   'pass-with-failure'{$f.Closure.FailureType='TimeoutException';$f.Closure.FailureMessage='original timeout';$expected='must pass all closure'}
   'valid-original-policy'{$expected='successor policy registration'}
   'deadline-exceeded'{$f.Closure.FinishedEpochMillis=2401001;$expected='issued/deadline mismatch'}
  }
  Bind-Supplement $f
  switch($case) {
   'field-null'{$f.Closure.OwnedJava=$null;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure;$s=Get-Content $f.SupplementPath -Raw|ConvertFrom-Json;$s.Closure=Get-CapReference "$($f.Out)/cap-closure.json";Write-Fixture $f.SupplementPath $s;$f.SupplementHash=(Get-CapReference $f.SupplementPath).sha256;$expected='original empty object defect'}
   'field-missing'{$f.Closure.Remove('OwnedJava');Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure;$s=Get-Content $f.SupplementPath -Raw|ConvertFrom-Json;$s.Closure=Get-CapReference "$($f.Out)/cap-closure.json";Write-Fixture $f.SupplementPath $s;$f.SupplementHash=(Get-CapReference $f.SupplementPath).sha256;$expected='original empty object defect'}
   'slot-identity'{$s=Get-Content $f.SupplementPath -Raw|ConvertFrom-Json;$slot=Read-CapBoundFile $s.Slot;$slot.RunId='wrong-run';Write-Fixture $s.Slot.path $slot;$s.Slot=Get-CapReference $s.Slot.path;Write-Fixture $f.SupplementPath $s;$f.SupplementHash=(Get-CapReference $f.SupplementPath).sha256;$expected='attempt/slot identity mismatch'}
   'partial-supplement'{$f.SupplementHash=$null;$expected='requires complete S2 schema3 binding'}
   'extra-s1-no-supplement'{$f.SupplementHash=$null;$f.SupplementPath=$null;$f.Smoke='S1';$expected='history mismatch'}
   'missing-supplement' {$f.SupplementPath=$null;$f.SupplementHash=$null;$expected='resources unresolved'}
   'wrong-run'{$s=Get-Content $f.SupplementPath -Raw|ConvertFrom-Json;$s.RunId='different-run';Write-Fixture $f.SupplementPath $s;$f.SupplementHash=(Get-CapReference $f.SupplementPath).sha256;$expected='target mismatch'}
   'wrong-original-hash'{$s=Get-Content $f.SupplementPath -Raw|ConvertFrom-Json;$s.Closure.sha256='0'*64;Write-Fixture $f.SupplementPath $s;$f.SupplementHash=(Get-CapReference $f.SupplementPath).sha256;$expected='hash mismatch'}
   'probe-tamper'{[IO.File]::AppendAllText("$($f.Out)/closure-probe.json",' ');$expected='hash mismatch'}
   'index-tamper'{[IO.File]::AppendAllText("$($f.Out)/execution-evidence.json",' ');$expected='hash mismatch'}
   'probe-file-missing'{Remove-Item -LiteralPath "$($f.Out)/closure-probe.json";$expected='does not exist'}
  }
  if($case -in @('valid-successor','wrong-successor-link','changed-successor-limits','successor-no-supplement','source-tamper','changed-successor-budget')) {
   Successor $f
   $p=Get-Content "$($f.Root)/successor.json" -Raw|ConvertFrom-Json
   switch($case) {
    'wrong-successor-link'{$p.s1Predecessor.sha256='0'*64;$expected='hash mismatch'}
    'changed-successor-limits'{$p.expiry.hardTrialSeconds=7200;$expected='frozen policy changed'}
    'successor-no-supplement'{$f.SupplementPath=$null;$f.SupplementHash=$null;$expected='requires schema3 S1 supplement'}
    'changed-successor-budget'{$p.storage.reservationF=1;$expected='frozen policy changed'}
    'source-tamper'{[IO.File]::AppendAllText("$($f.Repo)/scripts/fixture.ps1",'changed');$expected='successor current source changed'}
   }
   Write-Fixture "$($f.Root)/successor.json" $p
   Write-Fixture "$($f.Root)/cap-resume-registration.json" @{approval=(Get-CapReference $f.Approval);policy=(Get-CapReference "$($f.Root)/successor.json")}
  }
  $message=$null;$context=$null;try{$context=Gate $f}catch{$message=$_.Exception.Message}
  $pass=if($expected){$message -like "*$expected*"}else{$null -eq $message -and $context.ServiceMillis -eq 737673 -and $context.RecordedServiceMillisFloor -eq 327384 -and $context.HistoricalServiceTimeUncertain -eq $true}
  if($case -eq 'valid-successor'){$pass=$pass -and $context.Policy.sha256 -ine $context.S1Policy.sha256}
  Result "supplement-$case" $pass $message
 }
}

if($S2BindingCandidate){
 $binding=Get-Content -LiteralPath $S2BindingCandidate -Raw -Encoding UTF8|ConvertFrom-Json
 $profile=Read-CapBoundFile $binding.k6.s2Profile
 $budget=Read-CapBoundFile $binding.k6.reservations.S2
 Assert-CapS2Profile $profile $budget
 Result 's2-profile-normal' ($profile.evidenceBytes -eq 1677721600L -and 2*$profile.usage.runBytes -lt $profile.outputBytes) $null
 $message=$null;try{Assert-CapK6Binding $binding $binding.k6.executable.path $binding.k6.reservations.S2.path 'S2'}catch{$message=$_.Exception.Message}
 Result 's2-expanded-normal-binding' ($null -eq $message) $message
 foreach($pair in @(@(49L,100L),@(50L,500L),@(51L,500L),@(400L,2500L))){Result ('expand-'+$pair[0]) ((Expand-CapStorageLimit 100L $pair[0]) -eq $pair[1]) $null}
 foreach($key in @('maxSampleBytes','evidenceBytes','samples','metricsBytes','outputBytes')){
  $bad=$profile|ConvertTo-Json -Depth 20|ConvertFrom-Json
  $bad.$key=if($key -eq 'maxSampleBytes'){1048576L}elseif($key -eq 'evidenceBytes'){201326592L}else{$bad.$key-1}
  $message=$null;try{Assert-CapS2Profile $bad $budget}catch{$message=$_.Exception.Message}
  Result "s2-profile-$key" ($message -like 'S2 *') $message
 }
 $bad=$budget|ConvertTo-Json -Depth 20|ConvertFrom-Json;$bad.additionalPeak.evidence=201326592L
 $message=$null;try{Assert-CapS2Profile $profile $bad}catch{$message=$_.Exception.Message}
 Result 's2-short-storage' ($message -eq 'S2 evidence peak reservation') $message
 $bindingCall=@($controllerAst.FindAll({param($n)$n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Assert-CapK6Binding'},$true))[0]
 $slotCall=@($controllerAst.FindAll({param($n)$n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Save-New' -and $n.Extent.Text.StartsWith('Save-New $resume.SlotPath')},$true))[0]
 $mysqlCall=@($controllerAst.FindAll({param($n)$n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Invoke-Step' -and $n.Extent.Text.Contains("'mysql-start'")},$true))[0]
 Result 's2-precheck-before-slot-and-service' ($bindingCall.Extent.StartOffset -lt $slotCall.Extent.StartOffset -and $slotCall.Extent.StartOffset -lt $mysqlCall.Extent.StartOffset) $null
 # controller의 실제 assignment와 helper의 receipt/Environment expression만 실행. 서비스 명령은 실행하지 않음
 $Smoke='S2';$K6='fixture';$s2Profile=$profile;$session='isolated-output';$mysqlStartParameters=@{}
 $assignment=@($controllerAst.FindAll({param($n)$n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$mysqlStartParameters.EvidenceBytes'},$true))[0]
 . ([scriptblock]::Create($assignment.Extent.Text))
 Result 's2-controller-mysql-parameter' ($mysqlStartParameters.EvidenceBytes -eq 1677721600L) $null
 . "$PSScriptRoot/pixel-place-work.ps1"
 $tokens=$null;$errors=$null;$helper=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/mysql-test-instance.ps1",[ref]$tokens,[ref]$errors)
 if($errors.Count){throw 'MySQL helper parse'}
 foreach($name in @('Get-Bytes','Get-Budget')){
  $function=@($helper.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true))[0]
  . ([scriptblock]::Create($function.Extent.Text))
 }
 $root="$EvidenceRoot/evidence-propagation";$run=$root;New-Item -ItemType Directory -Path $root|Out-Null
 $minimumFree=10GB;$MySqlGrowthBytes=2GB;$EvidenceBytes=$mysqlStartParameters.EvidenceBytes
 $start=[pscustomobject]@{Budget=(Get-Budget 0)}
 $inherit=@($helper.FindAll({param($n)$n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$EvidenceBytes' -and $n.Right.Extent.Text.Contains('$start.Budget.EvidenceLimitBytes')},$true))
 foreach($statement in $inherit){$EvidenceBytes=0L;. ([scriptblock]::Create($statement.Extent.Text));if($EvidenceBytes -ne 1677721600L){throw 'Helper evidence inheritance'}}
 $instance=@{owner='fixture';uuid='fixture';binarySha256='fixture';configSha256='fixture'};$live=@{Pid=1;StartUtc='fixture'}
 $redis=@{Pid=2;StartUtc='fixture';RunId='fixture'};$RunId='fixture';$binary='fixture';$envFile='fixture';$script:capturedEnvironment=$null
 function Save-NewJson($Path,$Value){$script:capturedEnvironment=$Value}
 $save=@($helper.FindAll({param($n)$n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Save-NewJson' -and $n.Extent.Text.StartsWith('Save-NewJson $envFile ')},$true))[0]
 . ([scriptblock]::Create($save.Extent.Text))
 Result 's2-helper-receipt-verify-environment' ($start.Budget.EvidenceLimitBytes -eq 1677721600L -and $inherit.Count -eq 2 -and $script:capturedEnvironment.evidenceBytes -eq 1677721600L) $null
}

Save-CapJson "$EvidenceRoot/results.json" @{Cases=$results;Passed=$results.Count;ServicesStarted=0;Scope='Real PowerShell gate with isolated on-disk synthetic evidence; no actual smoke attempts'}
Write-Output "CAP_RESUME_BOUNDARIES_PASS=$($results.Count)"

}

# schema4의 실제 이력/순수 gate 선택 검증. 활성 등록·장부·슬롯/서비스를 변경하지 않음
if($AdditionalApprovalTemplate){
 $additional=Get-Content -LiteralPath $AdditionalApprovalTemplate -Raw -Encoding UTF8|ConvertFrom-Json
 $candidate=Get-Content -LiteralPath (Join-Path (Split-Path $AdditionalApprovalTemplate -Parent) 'registration-candidate.json') -Raw -Encoding UTF8|ConvertFrom-Json
 $actualPrior=@(Get-ChildItem -LiteralPath $additional.ledgerRoot -Filter 'attempt-*.json'|ForEach-Object {Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8|ConvertFrom-Json})
 function Extra([string]$Name,[string]$Expected,[scriptblock]$Mutate){
  $a=Get-Content -LiteralPath $AdditionalApprovalTemplate -Raw -Encoding UTF8|ConvertFrom-Json
  $p=Read-CapBoundFile $candidate.policy
  $f=@{Approval=$a;Policy=$p;Smoke='S2';Prior=$actualPrior;Root=$a.ledgerRoot;Repo=$a.repo}
  if($Mutate){& $Mutate $f}
  $dir="$EvidenceRoot/extra-$Name";New-Item -ItemType Directory -Path $dir|Out-Null
  Write-Fixture "$dir/approval.json" $f.Approval;$ar=Get-CapReference "$dir/approval.json"
  $f.Policy.resumeApproval=$ar;Write-Fixture "$dir/policy.json" $f.Policy
  $registration=@{approval=$ar;policy=(Get-CapReference "$dir/policy.json")}
  $accepted=$false;$message=$null
  try{
   $context=Get-CapAdditionalS2Context $registration $f.Approval $f.Root $f.Repo $f.Smoke $f.Prior $p.s1ClosureSupplement.path $p.s1ClosureSupplement.sha256
   if($context.ServiceMillis -ne 1467670 -or $context.RecordedServiceMillisFloor -ne 1057381 -or -not $context.HistoricalServiceTimeUncertain){throw 'Test consumption expectation mismatch'}
   $accepted=$true
  }catch{$message=$_.Exception.Message}
  $pass=if($Expected){-not $accepted -and $message -like "*$Expected*"}else{$accepted}
  $results.Add(@{Case="schema4-$Name";Accepted=$accepted;Message=$message;Pass=$pass})
  if(-not $pass){throw "Additional S2 test $Name failed: $message"}
 }
 Extra 'accepted-preserved-history' '' {}
 Extra 'mixed-path-separators' '' {param($f)$f.Root=$f.Root.Replace('\','/');$f.Repo=$f.Repo.Replace('\','/')}
 Extra 's1-forbidden' 'frozen limits' {param($f)$f.Smoke='S1'}
 Extra 's2-limit' 'frozen limits' {param($f)$f.Approval.s2Max=3}
 Extra 's1-limit' 'frozen limits' {param($f)$f.Approval.s1Max=6}
 Extra 'total-limit' 'frozen limits' {param($f)$f.Approval.totalAttempts=8}
 Extra 'single-vs-cumulative' 'frozen limits' {param($f)$f.Approval.reservationMillis=4500000}
 Extra 'cleanup-limit' 'frozen limits' {param($f)$f.Approval.cleanupMillis=2400000}
 Extra 'no-time-extension' 'frozen limits' {param($f)$f.Approval.serviceMillisMax=3600000}
 Extra 'prior-refund' 'preserved attempts' {param($f)$f.Prior=@($f.Prior|Where-Object Smoke -ceq 'S1')}
 Extra 'duplicate-attempt' 'attempts exhausted' {param($f)$f.Prior=@($f.Prior)+@($f.Prior[-1])}
 Extra 'duplicate-authorization' 'slot already consumed' {param($f)$f.Approval.authorizationId='resume-k6-20261012-01'}
 Extra 'wrong-history-hash' 'hash mismatch' {param($f)$f.Approval.failedS2.closure.sha256='0'*64}
 Extra 'wrong-ledger-hash' 'hash mismatch' {param($f)$f.Approval.previousLedger.sha256='0'*64}
 Extra 'charge-refund' 'preserved consumption' {param($f)$f.Approval.baselineServiceMillis=1057381}
 Extra 'floor-refund' 'preserved consumption' {param($f)$f.Approval.recordedServiceMillisFloor=0}
 Extra 'uncertainty-cleared' 'preserved consumption' {param($f)$f.Approval.historicalServiceTimeUncertain=$false}
 Extra 'workload-changed' 'frozen policy' {param($f)$f.Policy.smokes.S2='measure 200 seconds'}
 Extra 'memory-changed' 'k6 contract' {param($f)$f.Policy.k6.nativeMemoryBytes=1073741824}
 Extra 'profile-hash' 'hash mismatch' {param($f)$f.Policy.k6.s2Profile.sha256='0'*64}
 Extra 'reservation-hash' 'hash mismatch' {param($f)$f.Policy.k6.reservations.S2.sha256='0'*64}
 Extra 'runtime-receipt-hash' 'hash mismatch' {param($f)$f.Policy.runtimeReceipt.sha256='0'*64}
 Extra 'runtime-classpath-hash' 'classpath changed' {param($f)$f.Policy.runtimeClasspath.sha256='0'*64}
 Extra 'source-hash' 'current source changed' {param($f)$f.Policy.sourceBinding.'scripts/phase18-cap-resume.ps1'='0'*64}
 # 누적75분과 단일40분의 정확 경계. 실제 gate의 예약 조건을 요구값으로 실행
 $resumeAst=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/phase18-cap-resume.ps1",[ref]$tokens,[ref]$parseErrors)
 $reservationGate=@($resumeAst.FindAll({param($n)$n -is [Management.Automation.Language.IfStatementAst] -and $n.Extent.Text.StartsWith('if($sum+[long]$a.reservationMillis')},$true))
 if($parseErrors.Count -or $reservationGate.Count -ne 1){throw 'Additional service reservation gate missing'}
 foreach($case in @(@(1467670L,$true),@(2100000L,$true),@(2100001L,$false))){
  $sum=$case[0];$a=@{reservationMillis=2400000L;serviceMillisMax=4500000L};$accepted=$true;$message=$null
  try{& ([scriptblock]::Create($reservationGate[0].Extent.Text))}catch{$accepted=$false;$message=$_.Exception.Message}
  $pass=$accepted -eq $case[1] -and ($accepted -or $message -like '*cumulative service reservation exhausted*')
  $results.Add(@{Case="schema4-time-$sum";Pass=$pass;Accepted=$accepted;Message=$message})
  if(-not $pass){throw 'Additional service reservation boundary mismatch'}
 }
 Write-Fixture "$EvidenceRoot/results-with-additional.json" @($results.ToArray())
 Write-Output "ADDITIONAL_PASS=$($results.Count)"
}

# 실제 보존 이력으로 새 완료 권한의 정상 수용/과거 제한 우회 없는 연결 확인. 활성 파일 쓰기 없음
if($CompletionApprovalTemplate){
 $a=Get-Content $CompletionApprovalTemplate -Raw -Encoding UTF8|ConvertFrom-Json
 $candidate=Get-Content (Join-Path (Split-Path $CompletionApprovalTemplate -Parent) 'registration-candidate.json') -Raw -Encoding UTF8|ConvertFrom-Json
 $actualPrior=@(Get-ChildItem $a.ledgerRoot -Filter 'attempt-*.json'|ForEach-Object {Get-Content $_.FullName -Raw -Encoding UTF8|ConvertFrom-Json})
 function CompletionCase($Name,$Expected,[scriptblock]$Mutate){
  $a=Get-Content $CompletionApprovalTemplate -Raw -Encoding UTF8|ConvertFrom-Json;$p=Read-CapBoundFile $candidate.policy
  $f=@{Approval=$a;Policy=$p;Prior=$actualPrior;Smoke='S2'}
  if($Mutate){& $Mutate $f}
  $dir="$EvidenceRoot/completion-$Name";New-Item -ItemType Directory -Path $dir|Out-Null
  Write-Fixture "$dir/approval.json" $f.Approval;$ar=Get-CapReference "$dir/approval.json"
  $f.Policy.resumeApproval=$ar;Write-Fixture "$dir/policy.json" $f.Policy
  $reg=@{approval=$ar;policy=(Get-CapReference "$dir/policy.json")};$accepted=$false;$message=$null
  try{
   $ctx=Get-CapAdditionalS2Context $reg $f.Approval $a.ledgerRoot $a.repo $f.Smoke $f.Prior $p.s1ClosureSupplement.path $p.s1ClosureSupplement.sha256
   if($ctx.ServiceMillis -ne $a.baselineServiceMillis -or $ctx.RecordedServiceMillisFloor -ne $a.recordedServiceMillisFloor -or -not $ctx.HistoricalServiceTimeUncertain -or $ctx.SlotPath -notlike "*-S2-$($actualPrior.Count+1).json"){throw 'Completion accounting/slot expectation'}
   $accepted=$true
  }catch{$message=$_.Exception.Message}
  $pass=if($Expected){-not $accepted -and $message -like "*$Expected*"}else{$accepted}
  $results.Add(@{Case="completion-$Name";Pass=$pass;Accepted=$accepted;Message=$message})
  if(-not $pass){throw "Completion test $Name failed: $message"}
 }
 CompletionCase 'accepted-after-attempt7' '' {}
 CompletionCase 's1-forbidden' 'frozen limits' {param($f)$f.Smoke='S1'}
 CompletionCase 'no-artificial-attempt-limit' 'frozen limits' {param($f)$f.Approval.totalAttempts=8}
 CompletionCase 'no-artificial-s2-limit' 'frozen limits' {param($f)$f.Approval.s2Max=3}
 CompletionCase 'no-cumulative-time-cap' 'frozen limits' {param($f)$f.Approval.serviceMillisMax=4500000}
 CompletionCase 'single-expiry-kept' 'frozen limits' {param($f)$f.Approval.reservationMillis=4500000}
 CompletionCase 'cleanup-kept' 'frozen limits' {param($f)$f.Approval.cleanupMillis=360000}
 CompletionCase 'missing-failure' 'preserved attempts' {param($f)$f.Approval.continuationFailures=@()}
 CompletionCase 'missing-attempt' 'single registered attempt' {param($f)$f.Prior=@($f.Prior|Where-Object SessionId -ne 'phase18-cap0-s2-20261012-202805')}
 CompletionCase 'no-cap1-scope' 'single registered attempt' {param($f)$f.Approval.scope='CAP-1'}
 CompletionCase 'wrong-next-attempt' 'single registered attempt' {param($f)$f.Approval.nextAttemptNumber=9}
 CompletionCase 'old-candidate-after-consumption' 'single registered attempt' {param($f)$f.Prior=@($f.Prior)+@($f.Prior[-1])}
 CompletionCase 'failure-hash' 'hash mismatch' {param($f)$f.Approval.continuationFailures[0].closure.sha256='0'*64}
 CompletionCase 'failure-slot-hash' 'hash mismatch' {param($f)$f.Approval.continuationFailures[0].slot.sha256='0'*64}
 CompletionCase 'consumption-refund' 'preserved consumption' {param($f)$f.Approval.baselineServiceMillis=0}
 CompletionCase 'floor-refund' 'preserved consumption' {param($f)$f.Approval.recordedServiceMillisFloor=0}
 CompletionCase 'uncertainty-cleared' 'preserved consumption' {param($f)$f.Approval.historicalServiceTimeUncertain=$false}
 CompletionCase 'minimum-free-kept' 'policy limits' {param($f)$f.Policy.budgets.minimumFreeEach=0}
 CompletionCase 'policy-time-cap' 'policy limits' {param($f)$f.Policy.budgets.newServiceMillisMax=4500000}
 CompletionCase 'workload-kept' 'frozen policy' {param($f)$f.Policy.smokes.S2='measure 200 seconds'}
 CompletionCase 'runtime-receipt' 'hash mismatch' {param($f)$f.Policy.runtimeReceipt.sha256='0'*64}
 CompletionCase 'current-source' 'current source changed' {param($f)$f.Policy.sourceBinding.'scripts/phase18-cap-resume.ps1'='0'*64}
 Write-Fixture "$EvidenceRoot/results-completion.json" @($results.ToArray())
 Write-Output "CAP_COMPLETION_PASS=$($results.Count)"
}
