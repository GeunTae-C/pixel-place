param([Parameter(Mandatory=$true)][string]$EvidenceRoot)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/phase18-cap-resume.ps1"
$repo=Split-Path $PSScriptRoot -Parent
$realRoot="$repo/build/agent-runs/phase18-cap0-prep-20261004-181654"
$realApproval="$repo/build/agent-runs/phase18-cap0-resume-20261006/resume-approval.json"
New-Item -ItemType Directory -Path $EvidenceRoot -ErrorAction Stop|Out-Null
$results=[Collections.Generic.List[object]]::new()
function Write-Fixture($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))}
function New-Fixture([string]$Name,[switch]$S2){
 $r="$EvidenceRoot/$Name/repo";$l="$r/build/agent-runs/ledger";New-Item -ItemType Directory -Path $l -Force|Out-Null
 $a=Get-Content $realApproval -Raw -Encoding UTF8|ConvertFrom-Json
 $a.repo=[IO.Path]::GetFullPath($r);$a.ledgerRoot=[IO.Path]::GetFullPath($l)
 for($i=0;$i -lt 2;$i++){
  $h=$a.history[$i];$v=Read-CapBoundFile $h.attempt
  Copy-Item -LiteralPath $h.attempt.path -Destination "$l/attempt-$($i+1).json"
  $h.attempt=Get-CapReference "$l/attempt-$($i+1).json"
  $d="$r/build/agent-runs/$($v.SessionId)";New-Item -ItemType Directory -Path $d|Out-Null
  Copy-Item -LiteralPath $h.issued.path -Destination "$d/cap-issued.json";$h.issued=Get-CapReference "$d/cap-issued.json"
 }
 $ap="$l/approval.json";Write-Fixture $ap $a;$ar=Get-CapReference $ap
 $pol=@{resumeApproval=$ar;previousPolicy=$a.previousPolicy};Write-Fixture "$l/policy.json" $pol;$pr=Get-CapReference "$l/policy.json"
 Write-Fixture "$l/cap-resume-registration.json" @{approval=$ar;policy=$pr}
 $f=@{Root=$l;Repo=$r;Approval=$ap;Hash=$ar.sha256;Context=@{Approval=$ar;Policy=$pr};Smoke=if($S2){'S2'}else{'S1'}}
 if($S2){
  $at=@{SessionId='phase18-resumed-s1';RunId='resumed-s1';Smoke='S1';ResumeApprovalHash=$ar.sha256}
  Write-Fixture "$l/attempt-3.json" $at
  Write-Fixture "$l/cap-resume-resume-20261006-S1.json" @{SessionId=$at.SessionId;ResumeApprovalHash=$ar.sha256}
  $out="$r/build/agent-runs/$($at.SessionId)";New-Item -ItemType Directory -Path "$out/resumed-s1/initial","$out/resumed-s1/recovery" -Force|Out-Null
  foreach($rel in @('cap-issued.json','policy-cap.json','classpath.txt','classpath.txt.json','run-exit.json','analysis-exit.json','mysql-stop.json','closure-probe.json','budget-after.json','resumed-s1/initial/raw.jsonl','resumed-s1/initial/manifest.json','resumed-s1/recovery/manifest.json')){Write-Fixture "$out/$rel" @{SessionId=$at.SessionId}}
  Write-Fixture "$out/plan-cap.json" @{predecessor=$pr}
  Write-Fixture "$out/analysis.json" @{cap=@{smokeVerification='PASS';capacityPoint=$false};integrity=@{complete=$true;recoveryComplete=$true;initialComplete=$true;drainComplete=$true;wsComplete=$true}}
  $budget=Get-CapReference "$out/budget-after.json"
  $index=Save-CapExecutionEvidence $out $at.RunId
  $c=@{SessionId=$at.SessionId;RunId=$at.RunId;Smoke='S1';Status='PASS';RunExit=0;AnalysisExit=0;OwnedResourcesClosed=$true;NormalMysqlStop=$true;Remaining3307=0;RemainingRunLock=$false;OwnedJava=@();ServiceMillis=1000;ServiceMillisCumulative=151511;BudgetAfterVerified=$true;Budget=@{ledger=$budget.path;sha256=$budget.sha256;gateMet=$true};ResumeApprovalHash=$ar.sha256;ResumePolicyHash=$pr.sha256;EvidenceIndex=$index;OwnedWorkers=@(@{Joined=$true;FirstFailure=$null})}
  Write-Fixture "$out/cap-closure.json" $c;$f.Out=$out;$f.Closure=$c
 }
 return $f
}
function Gate($f){$p=@(Get-ChildItem $f.Root -Filter 'attempt-*.json'|ForEach-Object {Get-Content $_.FullName -Raw -Encoding UTF8|ConvertFrom-Json});Get-CapResumeContext $f.Root $f.Repo $f.Approval $f.Hash $f.Smoke $p}
function Check([string]$Name,[bool]$Accept,[string]$Expected,[scriptblock]$Mutate,[switch]$S2){
 $f=New-Fixture $Name -S2:$S2
 if($Mutate){& $Mutate $f}
 $ok=$false;$message=$null
 try{$g=Gate $f;$ok=$true}catch{$message=$_.Exception.Message}
 $pass=($ok -eq $Accept) -and ($Accept -or $message -like "*$Expected*")
 $results.Add(@{Case=$Name;ExpectedAccept=$Accept;Accepted=$ok;Message=$message;Pass=$pass})
 if(-not $pass){throw "Resume test $Name failed: $message"}
}
Check 'approved-s1' $true '' {param($f)}
Check 'missing-approval' $false 'not registered' {param($f)$f.Approval="$($f.Root)/absent.json"}
Check 'hash-tamper' $false 'not registered' {param($f)$f.Hash='0'*64}
Check 'content-tamper' $false 'hash mismatch' {param($f)[IO.File]::AppendAllText($f.Approval,' ')}
Check 'history-tamper' $false 'hash mismatch' {param($f)[IO.File]::AppendAllText("$($f.Root)/attempt-1.json",' ')}
Check 'slot-consumed' $false 'slot already consumed' {param($f)Write-Fixture "$($f.Root)/cap-resume-resume-20261006-S1.json" @{claimed=$true}}
Check 'issued-orphan' $false 'unregistered issued' {param($f)$d="$($f.Repo)/build/agent-runs/orphan";New-Item -ItemType Directory $d|Out-Null;Write-Fixture "$d/cap-issued.json" @{SessionId='orphan'}}
Check 's2-before-s1' $false 'history mismatch' {param($f)$f.Smoke='S2'}
Check 's1-failed' $false 'must pass all closure' {param($f)$f.Closure.Status='FAILED';Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-incomplete-budget' $false 'must pass all closure' {param($f)$f.Closure.BudgetAfterVerified=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-unresolved' $false 'resources unresolved' {param($f)$f.Closure.OwnedResourcesClosed=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-worker-unresolved' $false 'workers not normally' {param($f)$f.Closure.OwnedWorkers[0].Joined=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 'approved-s2' $true '' {param($f)} -S2
Check 's2-duplicate-slot' $false 'slot already consumed' {param($f)Write-Fixture "$($f.Root)/cap-resume-resume-20261006-S2.json" @{claimed=$true}} -S2
Check 'total-exhausted' $false 'attempts exhausted' {param($f)Write-Fixture "$($f.Root)/attempt-4.json" @{Smoke='S2'}} -S2
Check 'time-exhausted' $false 'service reservation exhausted' {param($f)$f.Closure.ServiceMillis=1200000;$f.Closure.ServiceMillisCumulative=1350511;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-storage-exhausted' $false 'storage reservation failed' {param($f)$f.Closure.Budget.gateMet=$false;Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure} -S2
Check 's1-raw-changed' $false 'execution evidence changed' {param($f)[IO.File]::AppendAllText("$($f.Out)/resumed-s1/initial/raw.jsonl",' ')} -S2
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
 Move-Item -LiteralPath "$($f.Out)/resumed-s1/initial/raw.jsonl" -Destination "$($f.Out)/resumed-s1/initial/k6-records.jsonl"
 $p="$($f.Out)/analysis.json";$a=Get-Content $p -Raw|ConvertFrom-Json
 $a|Add-Member -NotePropertyName inputKind -NotePropertyValue 'phase18-k6-v2';Write-Fixture $p $a
 $files=@(Get-ChildItem -LiteralPath $f.Out -File -Recurse|Where-Object {$_.Name -notin @('execution-evidence.json','cap-closure.json')}|ForEach-Object {Get-CapReference $_.FullName})
 Write-Fixture "$($f.Out)/execution-evidence.json" @{RunId='resumed-s1';Files=$files}
 $f.Closure.EvidenceIndex=Get-CapReference "$($f.Out)/execution-evidence.json";Write-Fixture "$($f.Out)/cap-closure.json" $f.Closure
} -S2
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
 $expected=switch($case){'wrong-smoke-reservation'{'binding required'}'binary-tamper'{'binary/script changed'}'missing-peak'{'peak reservation required'}default{$null}}
 $pass=if($expected){$message -like "*$expected*"}else{$null -eq $message}
 $results.Add(@{Case=$case;Message=$message;Pass=$pass});if(-not $pass){throw "Binding test $case failed: $message"}
}
Save-CapJson "$EvidenceRoot/results.json" @{Cases=$results;Passed=$results.Count;ServicesStarted=0;Scope='Real PowerShell gate with isolated on-disk synthetic evidence; no actual smoke attempts'}
Write-Output "CAP_RESUME_BOUNDARIES_PASS=$($results.Count)"
