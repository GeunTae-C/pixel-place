# 과거 경로 identity는 그대로 비교하고 읽을 때만 검증된 재배치 위치 사용
. "$PSScriptRoot/pixel-place-work.ps1"
function Get-CapHistoricalContent {
    param([Parameter(Position=0)][string]$LiteralPath,[switch]$Raw,[string]$Encoding='UTF8')
    Get-Content -LiteralPath (Resolve-PixelPlaceHistoricalPath $LiteralPath) -Raw:$Raw -Encoding $Encoding
}
function Get-CapHistoricalHash {
    param([Parameter(Position=0)][string]$LiteralPath,[string]$Algorithm='SHA256')
    Get-FileHash -LiteralPath (Resolve-PixelPlaceHistoricalPath $LiteralPath) -Algorithm $Algorithm
}
function Get-CapIssuedFiles([string]$Repo) {
    foreach($base in @("$Repo/build/agent-runs",'F:/pixel-place-work/evidence','F:/pixel-place-archive/pixel-place/build/agent-runs')) {
        if(Test-Path -LiteralPath $base) {
            Get-ChildItem -LiteralPath $base -Directory | ForEach-Object {Get-Item -LiteralPath "$($_.FullName)/cap-issued.json" -ErrorAction SilentlyContinue}
        }
    }
}
function Get-CapSessionEvidencePath([string]$Repo,[string]$SessionId) {
    $current=Get-PixelPlaceEvidencePath $SessionId
    if(Test-Path -LiteralPath $current){return $current}
    return "$Repo/build/agent-runs/$SessionId"
}
function Get-CapControlIdentity([string]$Path) {
    return [IO.Path]::GetFullPath((Resolve-PixelPlaceControlRoot $Path))
}
# 명시 재개 승인에 한정한 이력·증거·일회성 슬롯 gate. 일반 retry 제한은 controller에 보존
function Read-CapBoundFile($Reference) {
    if(-not $Reference.path -or -not $Reference.sha256 -or -not [IO.Path]::IsPathRooted($Reference.path)){throw 'CAP resume absolute evidence reference required'}
    if((Get-CapHistoricalHash -LiteralPath $Reference.path -Algorithm SHA256 -ErrorAction Stop).Hash -ine $Reference.sha256){throw 'CAP resume evidence hash mismatch'}
    return (Get-CapHistoricalContent -LiteralPath $Reference.path -Raw -Encoding UTF8|ConvertFrom-Json)
}
function Get-CapReference([string]$Path) {
    @{path=[IO.Path]::GetFullPath($Path);sha256=(Get-CapHistoricalHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
}
# null/누락/객체를 빈 관측으로 바꾸지 않음. JSON 배열과 명시된 listener/lock 값만 마감 가능
function Test-CapClosureProbe($Probe) {
    return $null -ne $Probe -and $Probe.OwnedJava -is [array] -and $Probe.OwnedJava.Count -eq 0 -and
        ($Probe.Remaining3307 -is [int] -or $Probe.Remaining3307 -is [long]) -and $Probe.Remaining3307 -eq 0 -and
        $Probe.RemainingRunLock -is [bool] -and $Probe.RemainingRunLock -eq $false
}
# controller와 회귀에서 같은 생성/CREATE_NEW 저장 경로 사용. 조건식 출력 열거 없이 배열 직접 대입
function Save-CapClosure([string]$Path,[hashtable]$Value,$Probe) {
    $Value.Probe=$Probe
    $Value.OwnedJava=$Probe.OwnedJava
    $Value.Remaining3307=$Probe.Remaining3307
    $Value.RemainingRunLock=$Probe.RemainingRunLock
    if(-not(Test-CapClosureProbe $Probe)) {
        $Value.OwnedResourcesClosed=$false
        $Value.ServiceMillisIsLowerBound=$true
        $Value.Status='FAILED'
        if(-not $Value.FailureMessage -and -not $Value.FailureType) {
            $Value.FailureType='InvalidOperationException'
            $Value.FailureMessage='CAP owned resources unresolved'
        }
    }
    Save-CapJson $Path $Value
}
# 이번 S1의 배열 결함에만 허용한 후속 정책. sourceBinding 외 예산/승인/부하 정책 변경 금지
function Assert-CapS2SuccessorPolicy($Policy,$Supplement,[string]$Smoke,[int]$Schema,[string]$Repo) {
    if($Smoke -cne 'S2' -or $Schema -ne 3 -or -not $Supplement){throw 'CAP S2 successor requires schema3 S1 supplement'}
    if($Policy.s1ClosureSupplement.path -ine $Supplement.path -or $Policy.s1ClosureSupplement.sha256 -ine $Supplement.sha256){throw 'CAP S2 successor supplement binding mismatch'}
    $previous=Read-CapBoundFile $Policy.s1Predecessor
    if($previous.s1Predecessor -or $previous.s1ClosureSupplement){throw 'CAP S2 successor cannot extend another successor'}
    $names=@($previous.PSObject.Properties.Name)+@('s1Predecessor','s1ClosureSupplement')
    if(@($Policy.PSObject.Properties.Name|Where-Object {$_ -notin $names}).Count){throw 'CAP S2 successor frozen policy changed'}
    foreach($key in $previous.PSObject.Properties.Name) {
        if($key -eq 'sourceBinding'){continue}
        if(($Policy.$key|ConvertTo-Json -Depth 30 -Compress) -cne ($previous.$key|ConvertTo-Json -Depth 30 -Compress)){throw 'CAP S2 successor frozen policy changed'}
    }
    if(-not $previous.sourceBinding -or -not $Policy.sourceBinding){throw 'CAP S2 successor source binding required'}
    if(Compare-Object @($previous.sourceBinding.PSObject.Properties.Name) @($Policy.sourceBinding.PSObject.Properties.Name)){throw 'CAP S2 successor source set changed'}
    foreach($key in $Policy.sourceBinding.PSObject.Properties.Name) {
        $path=[IO.Path]::GetFullPath("$Repo/$key")
        if(-not $path.StartsWith(([IO.Path]::GetFullPath($Repo).TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase)){throw 'CAP S2 successor source outside repo'}
        if((Get-CapHistoricalHash -LiteralPath $path -ErrorAction Stop).Hash -ine $Policy.sourceBinding.$key){throw 'CAP S2 successor current source changed'}
    }
}
# 원본 파일을 수정하지 않고 한 필드의 해석만 보완. 호출자가 나머지 S1 필수 gate를 계속 수행
function Assert-CapS1ArraySupplement([string]$Out,$Closure,$Attempt,$Context) {
    $s=Read-CapBoundFile $Context.S1ClosureSupplement
    if($s.schemaVersion -ne 1 -or $s.kind -cne 'cap-s1-ownedjava-empty-array' -or $s.field -cne 'OwnedJava' -or
       $s.SessionId -cne 'phase18-cap0-s1-20261012-041711' -or $s.RunId -cne 'cap0-s1-20261012-041711' -or
       $s.AttemptNumber -ne 5 -or $s.SessionId -cne $Attempt.SessionId -or $s.RunId -cne $Attempt.RunId -or $Attempt.Smoke -cne 'S1') {throw 'CAP S1 array supplement target mismatch'}
    if($s.Approval.path -ine $Context.Approval.path -or $s.Approval.sha256 -ine $Context.Approval.sha256 -or
       $s.Policy.path -ine $Context.S1Policy.path -or $s.Policy.sha256 -ine $Context.S1Policy.sha256) {throw 'CAP S1 array supplement authorization mismatch'}
    $approval=Read-CapBoundFile $s.Approval
    if($approval.schemaVersion -ne 3 -or $s.Attempt.path -ine [IO.Path]::GetFullPath("$($approval.ledgerRoot)/attempt-5.json") -or
       $s.Slot.path -ine [IO.Path]::GetFullPath("$($approval.ledgerRoot)/cap-resume-$($approval.authorizationId)-S1.json")) {throw 'CAP S1 array supplement attempt/slot binding mismatch'}
    $boundAttempt=Read-CapBoundFile $s.Attempt;$slot=Read-CapBoundFile $s.Slot
    foreach($v in @($boundAttempt,$slot)) {
        if($v.SessionId -cne $Attempt.SessionId -or $v.RunId -cne $Attempt.RunId -or $v.Smoke -cne 'S1' -or
           $v.ResumeApprovalHash -ine $Context.Approval.sha256 -or $v.ResumePolicyHash -ine $Context.S1Policy.sha256 -or
           $v.IssuedEpochMillis -ne $Attempt.IssuedEpochMillis) {throw 'CAP S1 array supplement attempt/slot identity mismatch'}
    }
    if($boundAttempt.ExpiresEpochMillis -ne $Attempt.ExpiresEpochMillis){throw 'CAP S1 array supplement attempt expiry mismatch'}
    if($s.Closure.path -ine [IO.Path]::GetFullPath("$Out/cap-closure.json")){throw 'CAP S1 array supplement original closure mismatch'}
    $null=Read-CapBoundFile $s.Closure
    if($Closure.OwnedJava -isnot [pscustomobject] -or @($Closure.OwnedJava.PSObject.Properties).Count -ne 0){throw 'CAP S1 array supplement requires original empty object defect'}
    if($s.EvidenceIndex.path -ine [IO.Path]::GetFullPath("$Out/execution-evidence.json") -or
       $s.EvidenceIndex.path -ine $Closure.EvidenceIndex.path -or $s.EvidenceIndex.sha256 -ine $Closure.EvidenceIndex.sha256) {throw 'CAP S1 array supplement index binding mismatch'}
    $index=Read-CapBoundFile $s.EvidenceIndex
    if($index.RunId -cne $Attempt.RunId -or $s.Probe.path -ine [IO.Path]::GetFullPath("$Out/closure-probe.json")){throw 'CAP S1 array supplement probe/index identity mismatch'}
    $refs=@($index.Files|Where-Object {$_.path -ieq $s.Probe.path -and $_.sha256 -ieq $s.Probe.sha256})
    if($refs.Count -ne 1){throw 'CAP S1 array supplement probe missing from original index'}
    $probe=Read-CapBoundFile $s.Probe
    if(-not(Test-CapClosureProbe $probe)){throw 'CAP S1 array supplement raw resources unresolved'}
    if(-not(Test-CapClosureProbe $Closure.Probe) -or $Closure.Remaining3307 -ne $probe.Remaining3307 -or
       $Closure.RemainingRunLock -ne $probe.RemainingRunLock){throw 'CAP S1 array supplement contradictory probe'}
    $issued=Get-CapHistoricalContent -LiteralPath "$Out/cap-issued.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if($issued.SessionId -cne $Attempt.SessionId -or $issued.RunId -cne $Attempt.RunId -or
       $issued.issuedEpochMillis -ne $Attempt.IssuedEpochMillis -or $issued.expiresEpochMillis -ne $Attempt.ExpiresEpochMillis -or
       $issued.ResumeApprovalHash -ine $Context.Approval.sha256 -or $issued.ResumePolicyHash -ine $Context.S1Policy.sha256 -or
       $Closure.OriginalExpiresEpochMillis -ne $Attempt.ExpiresEpochMillis -or $Closure.FinishedEpochMillis -gt $Attempt.ExpiresEpochMillis) {throw 'CAP S1 array supplement issued/deadline mismatch'}
}
# 보완 S1의 당시 source/runtime 연결 확인. 현재 source로 과거 receipt를 재서명하지 않음
function Assert-CapS1HistoricalInputs([string]$Out,$Attempt,$Context,$Closure,$Plan) {
    $policy=Read-CapBoundFile $Context.S1Policy
    $receipt=Get-CapHistoricalContent -LiteralPath "$Out/classpath.txt.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if(-not $policy.sourceBinding -or @($receipt.runtime).Count -eq 0){throw 'CAP S1 historical source/runtime missing'}
    foreach($key in $policy.sourceBinding.PSObject.Properties.Name) {
        $rel=$key.Replace('\','/')
        foreach($inputs in @($Plan.inputs,$receipt.inputs)) {
            $match=@($inputs|Where-Object {$_.path.Replace('\','/') -ieq $rel})
            if($match.Count -ne 1 -or $match[0].sha256 -ine $policy.sourceBinding.$key){throw 'CAP S1 historical source binding mismatch'}
        }
    }
    $planHash=(Get-CapReference "$Out/plan-cap.json").sha256
    $receiptHash=(Get-CapReference "$Out/classpath.txt.json").sha256
    foreach($stage in @('initial','recovery')) {
        $m=Get-CapHistoricalContent -LiteralPath "$Out/$($Attempt.RunId)/$stage/manifest.json" -Raw -Encoding UTF8|ConvertFrom-Json
        if($m.complete -ne $true -or $m.normalShutdown -ne $true -or $m.planHash -ine $planHash -or $m.runtimeReceiptHash -ine $receiptHash){throw 'CAP S1 historical runtime/recovery incomplete'}
    }
    $stop=Get-CapHistoricalContent -LiteralPath "$Out/mysql-stop.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if($stop.RunId -cne $Attempt.SessionId -or $stop.SqlExit -ne 0 -or $stop.NormalShutdown -ne $true -or $stop.ShutdownObserved -ne $true -or @($stop.Failures).Count){throw 'CAP S1 historical SQL stop incomplete'}
    foreach($name in @('run-exit','analysis-exit')) {
        $exit=Get-CapHistoricalContent -LiteralPath "$Out/$name.json" -Raw -Encoding UTF8|ConvertFrom-Json
        if($null -eq $exit.Exit -or $exit.Exit -ne 0){throw 'CAP S1 historical actual exit incomplete'}
    }
    if(@($Closure.OwnedWorkers|Where-Object {$null -eq $_.ActualExit -or $_.ActualExit -ne 0 -or -not $_.DbUsersClosed -or $_.CleanupFailure}).Count){throw 'CAP S1 historical worker closure incomplete'}
}

function Assert-CapClosed($Closure,$Attempt) {
    if($Closure.SessionId -cne $Attempt.SessionId -or $Closure.RunId -cne $Attempt.RunId -or $Closure.Smoke -cne $Attempt.Smoke){throw 'CAP resume closure identity mismatch'}
    if(-not $Closure.OwnedResourcesClosed -or -not $Closure.NormalMysqlStop -or $Closure.Remaining3307 -ne 0 -or $Closure.RemainingRunLock -or ($Closure.OwnedJava -isnot [array]) -or @($Closure.OwnedJava).Count -ne 0 -or $Closure.ServiceMillisIsLowerBound){throw 'CAP resume prior owned resources unresolved'}
    if($null -eq $Closure.ServiceMillis -or [long]$Closure.ServiceMillis -lt 0){throw 'CAP resume prior service time missing'}
}
function Assert-CapResumePass([string]$Out,$Attempt,$Context) {
    if(-not $Context.S1Policy){$Context.S1Policy=$Context.Policy}
    $c=Get-CapHistoricalContent -LiteralPath "$Out/cap-closure.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if($Context.S1ClosureSupplement) {
        Assert-CapS1ArraySupplement $Out $c $Attempt $Context
        # 검증한 필드의 로컬 해석만 교정. 원본 bytes/index와 나머지 필수 검사는 그대로 유지
        $c.OwnedJava=@()
    }
    Assert-CapClosed $c $Attempt
    if($c.Status -cne 'PASS' -or $c.FailureType -or $c.FailureMessage -or $c.RunExit -ne 0 -or $c.AnalysisExit -ne 0 -or -not $c.BudgetAfterVerified){throw 'CAP resume S1 must pass all closure gates'}
    if($c.ResumeApprovalHash -ine $Context.Approval.sha256 -or $c.ResumePolicyHash -ine $Context.S1Policy.sha256){throw 'CAP resume S1 closure authorization mismatch'}
    if(@($c.OwnedWorkers).Count -eq 0 -or @($c.OwnedWorkers|Where-Object {-not $_.Joined -or $_.FirstFailure}).Count){throw 'CAP resume S1 workers not normally closed'}
    if($Context.HistoricalServiceTimeUncertain -and @($c.OwnedWorkers|Where-Object {-not $_.Released}).Count){throw 'CAP resume S1 worker Release incomplete'}
    if(-not $c.EvidenceIndex){throw 'CAP resume S1 evidence index required'}
    $index=Read-CapBoundFile $c.EvidenceIndex
    if(@($index.Files).Count -eq 0){throw 'CAP resume S1 evidence index empty'}
    foreach($ref in $index.Files){
        if((Get-CapHistoricalHash -LiteralPath $ref.path -ErrorAction Stop).Hash -ine $ref.sha256){throw 'CAP resume S1 execution evidence changed'}
    }
    $analysis=Get-CapHistoricalContent -LiteralPath "$Out/analysis.json" -Raw -Encoding UTF8|ConvertFrom-Json
    $raw=if($analysis.inputKind -ceq 'phase18-k6-v2'){"$($Attempt.RunId)/initial/k6-records.jsonl"}else{"$($Attempt.RunId)/initial/raw.jsonl"}
    $required=@('cap-issued.json','plan-cap.json','policy-cap.json','classpath.txt','classpath.txt.json','analysis.json','run-exit.json','analysis-exit.json','mysql-stop.json','closure-probe.json','budget-after.json',$raw,"$($Attempt.RunId)/initial/manifest.json","$($Attempt.RunId)/recovery/manifest.json")
    foreach($rel in $required){if([IO.Path]::GetFullPath("$Out/$rel") -notin @($index.Files.path)){throw 'CAP resume S1 required evidence missing from index'}}
    $analysis=Get-CapHistoricalContent -LiteralPath "$Out/analysis.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if($analysis.cap.smokeVerification -cne 'PASS' -or $analysis.cap.capacityPoint -ne $false -or -not $analysis.integrity.complete -or -not $analysis.integrity.recoveryComplete -or -not $analysis.integrity.initialComplete -or -not $analysis.integrity.drainComplete -or -not $analysis.integrity.wsComplete){throw 'CAP resume S1 formal analysis/recovery incomplete'}
    $plan=Get-CapHistoricalContent -LiteralPath "$Out/plan-cap.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if($plan.predecessor.path -ine $Context.S1Policy.path -or $plan.predecessor.sha256 -ine $Context.S1Policy.sha256){throw 'CAP resume S1 plan authorization mismatch'}
    $null=Read-CapBoundFile @{path=$c.Budget.ledger;sha256=$c.Budget.sha256}
    if(-not $c.Budget.gateMet){throw 'CAP resume S1 final storage reservation failed'}
    if($Context.S1ClosureSupplement){Assert-CapS1HistoricalInputs $Out $Attempt $Context $c $plan}
    return $c
}
function Get-CapResumeContext([string]$Root,[string]$Repo,[string]$ApprovalPath,[string]$ApprovalHash,[string]$Smoke,$Prior,[string]$S1ClosureSupplement,[string]$S1ClosureSupplementHash) {
    $registration=Get-CapHistoricalContent -LiteralPath "$Root/cap-resume-registration.json" -Raw -Encoding UTF8|ConvertFrom-Json
    if($registration.approval.path -ine [IO.Path]::GetFullPath($ApprovalPath) -or $registration.approval.sha256 -ine $ApprovalHash){throw 'CAP resume approval not registered or changed'}
    $a=Read-CapBoundFile $registration.approval
    if($a.schemaVersion -notin @(1,2,3,4) -or $a.ledgerRoot -ine [IO.Path]::GetFullPath($Root) -and (Resolve-PixelPlaceControlRoot $a.ledgerRoot) -ine [IO.Path]::GetFullPath($Root) -or $a.repo -ine [IO.Path]::GetFullPath($Repo)){throw 'CAP resume ledger/repo binding mismatch'}
    if($a.schemaVersion -eq 4){return (Get-CapAdditionalS2Context $registration $a $Root $Repo $Smoke $Prior $S1ClosureSupplement $S1ClosureSupplementHash)}
    foreach($ref in @($a.instruction,$a.previousInstruction)){
        if((Get-CapHistoricalHash -LiteralPath $ref.path).Hash -ine $ref.sha256){throw 'CAP resume instruction changed'}
    }
    $null=Read-CapBoundFile $a.previousPolicy
    $seed=Read-CapBoundFile $a.budgetSeed
    $policy=Read-CapBoundFile $registration.policy
    if($policy.resumeApproval.path -ine $registration.approval.path -or $policy.resumeApproval.sha256 -ine $ApprovalHash -or $policy.previousPolicy.sha256 -ine $a.previousPolicy.sha256){throw 'CAP resume policy chain mismatch'}
    $supplement=$null
    if($S1ClosureSupplement -or $S1ClosureSupplementHash) {
        if(-not $S1ClosureSupplement -or -not $S1ClosureSupplementHash -or $Smoke -cne 'S2' -or $a.schemaVersion -ne 3){throw 'CAP S1 supplement requires complete S2 schema3 binding'}
        $supplement=@{path=[IO.Path]::GetFullPath($S1ClosureSupplement);sha256=$S1ClosureSupplementHash}
        $null=Read-CapBoundFile $supplement
    }
    $s1Policy=$registration.policy
    if($policy.s1Predecessor -or $policy.s1ClosureSupplement) {
        Assert-CapS2SuccessorPolicy $policy $supplement $Smoke $a.schemaVersion $Repo
        $s1Policy=$policy.s1Predecessor
    }
    $history=Get-CapResumeHistory $a $Root $Prior $seed
    $sum=$history.ServiceMillis;$floor=$history.RecordedServiceMillisFloor;$historyIds=$history.ResolvedFailures
    $total=$history.Total;$s1Max=$history.S1Max;$historyCount=$history.HistoryCount;$extended=$history.Extended
    if($Prior.Count -ge $total -or @($Prior|Where-Object Smoke -eq 'S1').Count -gt $s1Max -or @($Prior|Where-Object Smoke -eq 'S2').Count -gt 0){throw 'CAP resume attempts exhausted'}
    $expected=if($Smoke -eq 'S1'){$historyCount}else{$historyCount+1}
    if($Smoke -cnotin @('S1','S2') -or $Prior.Count -ne $expected -or @($a.history).Count -ne $historyCount){throw 'CAP resume history mismatch'}
    $context=@{Approval=$registration.approval;Policy=$registration.policy;S1Policy=$s1Policy;S1ClosureSupplement=$supplement;ApprovalId=$a.authorizationId;ServiceMillis=$sum;ResolvedFailures=$historyIds;RecordedServiceMillisFloor=$floor;HistoricalServiceTimeUncertain=$extended;SlotPath="$Root/cap-resume-$($a.authorizationId)-$Smoke.json"}
    if(Test-Path -LiteralPath $context.SlotPath){throw 'CAP resume slot already consumed'}
    # 새 출력 폴더·다른 장부로 기존 issued 이력을 숨길 수 없도록 원본 repo 전체 등록 대조
    $allIssued=@(Get-CapIssuedFiles $Repo)
    if($allIssued.Count -ne $Prior.Count){throw 'CAP resume unregistered issued attempt present'}
    foreach($file in $allIssued){$v=Get-CapHistoricalContent -LiteralPath $file.FullName -Raw -Encoding UTF8|ConvertFrom-Json;if(@($Prior|Where-Object SessionId -eq $v.SessionId).Count -ne 1){throw 'CAP resume issued history mismatch'}}
    if($Smoke -eq 'S2'){
        $new=@($Prior|Where-Object {$_.SessionId -notin $historyIds})
        if($new.Count -ne 1 -or $new[0].Smoke -cne 'S1' -or $new[0].ResumeApprovalHash -ine $ApprovalHash){throw 'CAP resume new S1 identity mismatch'}
        $slot=Get-CapHistoricalContent -LiteralPath "$Root/cap-resume-$($a.authorizationId)-S1.json" -Raw -Encoding UTF8|ConvertFrom-Json
        if($slot.SessionId -cne $new[0].SessionId -or $slot.ResumeApprovalHash -ine $ApprovalHash){throw 'CAP resume S1 slot mismatch'}
        $c=Assert-CapResumePass (Get-CapSessionEvidencePath $Repo $new[0].SessionId) $new[0] $context
        $sum+=[long]$c.ServiceMillis
        $context.RecordedServiceMillisFloor+=[long]$c.ServiceMillis
        if($c.ServiceMillisCumulative -ne $sum){throw 'CAP resume final cumulative time mismatch'}
    }
    if($sum+2400000 -gt 3600000){throw 'CAP resume cumulative service reservation exhausted'}
    $context.ServiceMillis=$sum
    return $context
}
function Save-CapExecutionEvidence([string]$Out,[string]$RunId) {
    $files=@(Get-ChildItem -LiteralPath $Out -File -Recurse|Where-Object {$_.Name -notin @('execution-evidence.json','cap-closure.json')})
    $refs=@($files|ForEach-Object {Get-CapReference $_.FullName})
    Save-CapJson "$Out/execution-evidence.json" @{RunId=$RunId;Files=$refs}
    return (Get-CapReference "$Out/execution-evidence.json")
}
# 기존 승인 이력을 유지하고 새 실행 입력만 명시된 정책에 결합. 이 함수는 쓰기/잠금 없음.
# 순수 정적 검사. controller가 배타 guard를 보유한 뒤 슬롯/서비스 발급 전에 원본과 예약을 대조
function Expand-CapStorageLimit($Limit,$Usage) {
    foreach($value in @($Limit,$Usage)){
        if($value -isnot [long] -and $value -isnot [int]){throw 'Finite integer expansion inputs required'}
    }
    if($Limit -le 0 -or $Usage -lt 0 -or [decimal]$Usage*2 -gt [long]::MaxValue){throw 'Finite integer expansion inputs required'}
    $result=[long]$Limit
    while([decimal]$Usage*2 -ge $result){
        if([decimal]$result*5 -gt [long]::MaxValue){throw 'Storage expansion overflow'}
        $result=[long]([decimal]$result*5)
    }
    return $result
}
function Assert-CapS2Profile($Profile,$Budget) {
    if($Profile.schemaVersion -ne 2 -or $Profile.kind -cne 'cap0-s2-output-50pct-v1'){throw 'Frozen S2 profile required'}
    $expected=@{sampleMillis=250L;rowBytes=6144L;totalMillis=2400000L;cleanupMillis=180000L;measurementSeconds=220L;drainMillis=180000L;readyMillis=90000L;controlMillis=30000L;stopAckMillis=2000L}
    foreach($key in $expected.Keys){if(($Profile.$key -isnot [long] -and $Profile.$key -isnot [int]) -or $Profile.$key -ne $expected[$key]){throw "S2 profile mismatch: $key"}}
    $p=Read-CapBoundFile $Profile.planTemplate;$o=Read-CapBoundFile $Profile.policyTemplate
    if($Profile.planTemplate.path -ine [IO.Path]::GetFullPath("$PSScriptRoot/../build/agent-runs/phase18-cf-smoke-20261003-1540/plan-cf-smoke.json") -or
       $Profile.policyTemplate.path -ine [IO.Path]::GetFullPath("$PSScriptRoot/../build/agent-runs/phase18-cf-smoke-20261003-1540/policy-cf-smoke.json")){throw 'S2 original template paths'}
    $w=$p.cases[0].workload;$obs=$p.cases[0].observation
    if($w.wsConnections -ne 2 -or $w.writeInFlight -ne 4 -or $w.readInFlight -ne 4 -or $p.cases[0].runtime.batchMax -ne 16 -or $p.bounds.logBytesPerStream -ne 1048576){throw 'Unsupported S2 template/profile'}
    if($w.readyMillis -ne $Profile.readyMillis -or $w.controlMillis -ne $Profile.controlMillis -or $obs.sampleMillis -ne $Profile.sampleMillis -or $obs.stopAckMillis -ne $Profile.stopAckMillis -or
       $o.maxTraceBytes -ne 2097152 -or $o.maxTraceEntries -ne 6000){throw 'S2 original template timing/limits'}
    # 최초 만료와 유한 cleanup, 각 JVM 관측 + 고정 추가 기록/RPC 여유. 한도는 사용량에 재합산하지 않음.
    $seconds=2580L;$samples=2L+4*$seconds;$sampleBytes=$samples*6144
    $trace=4*$seconds*512+2048*2048+512*1024;$observation=$trace+1048576
    $rpc=11*$seconds+4*4410+256;$metrics=$rpc*4096+16777216
    $run=2*($sampleBytes+$observation)+$metrics+33554432
    $usage=@{observationMillis=2580000L;requiredSamples=$samples;sampleBytes=$sampleBytes;traceEntries=(4*$seconds+2048);traceAggregateEntries=512L;traceBytes=$trace;observationBytes=$observation;controlRpcs=$rpc;metricsBytes=$metrics;otherRunBytes=33554432L;runBytes=$run;evidenceBytes=($run+67108864)}
    foreach($key in $usage.Keys){
        if(($Profile.usage.$key -isnot [long] -and $Profile.usage.$key -isnot [int]) -or $Profile.usage.$key -ne $usage[$key]){throw "S2 expected usage mismatch: $key"}
    }
    $base=@{maxSampleBytes=67108864L;outputBytes=268435456L;evidenceBytes=335544320L;samples=10000L;maxTraceBytes=2097152L;maxTraceEntries=6000L;metricsBytes=101188608L}
    $use=@{maxSampleBytes=$sampleBytes;outputBytes=$run;evidenceBytes=$usage.evidenceBytes;samples=$samples;maxTraceBytes=$trace;maxTraceEntries=$usage.traceEntries;metricsBytes=$metrics}
    foreach($key in $base.Keys){
        if(($Profile.$key -isnot [long] -and $Profile.$key -isnot [int]) -or
           ($Profile.baseLimits.$key -isnot [long] -and $Profile.baseLimits.$key -isnot [int]) -or
           $Profile.baseLimits.$key -ne $base[$key] -or $Profile.$key -ne (Expand-CapStorageLimit $base[$key] $use[$key])){throw "S2 expanded limit mismatch: $key"}
    }
    if($Budget.additionalPeak.evidence -lt $usage.evidenceBytes){throw 'S2 evidence peak reservation'}
    $peak=[decimal]0
    foreach($key in @('evidence','graph','work','cache','mysql','wal')){
        $value=$Budget.additionalPeak.$key
        if(($value -isnot [long] -and $value -isnot [int]) -or $value -lt 0){throw 'S2 finite storage reservation'}
        $peak+=$value
    }
    foreach($key in @('C','F')){
        $value=$Budget.transientAllowance.$key
        if(($value -isnot [long] -and $value -isnot [int]) -or $value -lt 0){throw 'S2 finite storage allowance'}
        $peak+=$value
    }
    if($peak -gt [long]::MaxValue -or $Budget.storagePolicy.kind -cne 's2-storage-50pct-v1' -or
       $Budget.storagePolicy.usage.trial -ne $peak -or $Budget.registeredTrialBytes -ne (Expand-CapStorageLimit 8589934592L ([long]$peak))){throw 'S2 total trial reservation'}
    foreach($d in @('C','F')){
        $start=if($d -eq 'C'){21474836480L}else{107374182400L}
        if($Budget.storagePolicy.limits.$d -ne (Expand-CapStorageLimit $start $Budget.storagePolicy.usage.$d)){throw 'S2 cumulative storage limits'}
    }
}
function Assert-CapK6Binding($Policy,[string]$K6,[string]$Reservation,[string]$Smoke) {
    if($Smoke -cnotin @('S1','S2')){throw 'Registered smoke required'}
    $reference=$Policy.k6.reservations.$Smoke
    if($Policy.k6.inputKind -cne 'phase18-k6-v2' -or $Policy.k6.executable.path -ine [IO.Path]::GetFullPath($K6) -or
       $reference.path -ine [IO.Path]::GetFullPath($Reservation)){throw 'Registered k6 input/reservation binding required'}
    foreach($ref in @($Policy.k6.executable,$Policy.k6.script)){
        if((Get-CapHistoricalHash -LiteralPath $ref.path -Algorithm SHA256).Hash -ine $ref.sha256){throw 'Registered k6 binary/script changed'}
    }
    if($Policy.k6.script.path -ine [IO.Path]::GetFullPath("$PSScriptRoot/phase18-k6.js")){throw 'Registered local k6 script required'}
    $budget=Read-CapBoundFile $reference
    if($budget.schemaVersion -ne 2 -or $budget.scope -cne 'k6-mod3'){throw 'Registered budget transition required'}
    $profile=$null
    if($Smoke -ceq 'S2'){
        if($null -eq $Policy.k6.s2Profile){throw 'Frozen S2 output profile required'}
        $profile=Read-CapBoundFile $Policy.k6.s2Profile
        Assert-CapS2Profile $profile $budget
    }
    if($budget.additionalPeak.evidence -lt 201326592L -or $budget.additionalPeak.work -lt 2147483648L -or
       $budget.additionalPeak.cache -lt 1073741824L -or $budget.additionalPeak.mysql -lt 2147483648L -or
       $budget.additionalPeak.wal -lt 67108864L -or $budget.transientAllowance.F -lt 1073741824L){throw 'k6 complete preparation/runtime/analysis peak reservation required'}
    return $profile
}

# 과거 schema1~3 실패 이력의 동일 검증/보수 계상 재사용. 슬롯 발급은 호출자가 담당
function Get-CapResumeHistory($a,[string]$Root,$Prior,$seed) {
    $latest=$a.schemaVersion -eq 3;$extended=$a.schemaVersion -in @(2,3)
    $total=if($latest){6}elseif($extended){5}else{4};$s1Max=if($latest){5}elseif($extended){4}else{3};$historyCount=if($latest){4}elseif($extended){3}else{2}
    $floor=if($latest){326384L}elseif($extended){187838L}else{150511L}
    $thirdFloor=187838L
    if($a.totalAttempts -ne $total -or $a.s1Max -ne $s1Max -or $a.s2Max -ne 1 -or $a.serviceMillisMax -ne 3600000 -or $a.reservationMillis -ne 2400000 -or $a.baselineServiceMillis -ne $floor){throw 'CAP resume frozen limits mismatch'}
    if($extended){
        $mode=if($latest){'failed-start-sql-log-closed'}else{'failed-start-currently-absent'}
        if($a.continuationMode -cne $mode){throw 'CAP explicit absent-start continuation required'}
        $previous=Read-CapBoundFile $a.previousApproval
        if($previous.schemaVersion -ne ($a.schemaVersion-1) -or $previous.totalAttempts -ne ($total-1) -or $previous.s1Max -ne ($s1Max-1) -or $previous.s2Max -ne 1){throw 'CAP previous approval limits mismatch'}
        if($latest){
            if($a.previousInstruction.sha256 -ine $previous.instruction.sha256 -or $previous.baselineServiceMillis -ne $thirdFloor -or $previous.serviceMillisMax -ne 3600000 -or $previous.reservationMillis -ne 2400000){throw 'CAP previous time/instruction changed'}
            foreach($key in @('attempt','issued','closure','stop','continuation')){if($a.history[2].$key.sha256 -ine $previous.history[2].$key.sha256){throw 'CAP earlier failure evidence changed'}}
        }
        for($i=0;$i -lt 2;$i++){foreach($key in @('attempt','issued','closure','stop','supplemental')){
            if($a.history[$i].$key.sha256 -ine $previous.history[$i].$key.sha256){throw 'CAP earlier failure evidence changed'}
        }}
    }
    $sum=0L;$historyIds=@()
    for($i=0;$i -lt 2;$i++){
        $h=$a.history[$i];$attempt=Read-CapBoundFile $h.attempt;$issued=Read-CapBoundFile $h.issued
        if((Get-CapControlIdentity $h.attempt.path) -ine [IO.Path]::GetFullPath("$Root/attempt-$($i+1).json") -or $attempt.Smoke -cne 'S1' -or @($Prior|Where-Object SessionId -eq $attempt.SessionId).Count -ne 1){throw 'CAP resume original attempt mismatch'}
        if($issued.SessionId -cne $attempt.SessionId -or $issued.RunId -cne $attempt.RunId -or $issued.expiresEpochMillis -ne $attempt.ExpiresEpochMillis -or $issued.issuedEpochMillis -ne $attempt.IssuedEpochMillis){throw 'CAP resume original issued mismatch'}
        $original=Read-CapBoundFile $h.closure;$effective=$original
        if($h.supplemental){$effective=Read-CapBoundFile $h.supplemental;if($effective.PreviousClosureHash -ine $h.closure.sha256 -or $effective.Status -cne $original.Status -or $effective.ServiceMillis -lt $original.ServiceMillis){throw 'CAP resume supplemental closure mismatch'}}
        Assert-CapClosed $effective $attempt
        if($effective.Status -cne 'FAILED'){throw 'CAP resume original failure must remain FAILED'}
        $stop=Read-CapBoundFile $h.stop
        if($stop.SqlExit -ne 0 -or -not $stop.NormalShutdown -or -not $stop.ShutdownObserved -or $stop.RunId -cne $attempt.SessionId){throw 'CAP resume original SQL stop unverified'}
        $sum+=[long]$effective.ServiceMillis
        if($effective.ServiceMillisCumulative -ne $sum){throw 'CAP resume prior cumulative time mismatch'}
        $historyIds+=$attempt.SessionId
    }
    if($sum -ne 150511 -or $seed.ServiceMillisCumulative -ne $sum){throw 'CAP resume prior consumption mismatch'}
    if($extended){
        $h=$a.history[2];$attempt=Read-CapBoundFile $h.attempt;$issued=Read-CapBoundFile $h.issued;$original=Read-CapBoundFile $h.closure
        if((Get-CapControlIdentity $h.attempt.path) -ine [IO.Path]::GetFullPath("$Root/attempt-3.json") -or $attempt.Smoke -cne 'S1' -or @($Prior|Where-Object SessionId -eq $attempt.SessionId).Count -ne 1){throw 'CAP third attempt mismatch'}
        if($issued.SessionId -cne $attempt.SessionId -or $issued.RunId -cne $attempt.RunId -or $issued.issuedEpochMillis -ne $attempt.IssuedEpochMillis -or $issued.expiresEpochMillis -ne $attempt.ExpiresEpochMillis){throw 'CAP third issued mismatch'}
        if($original.SessionId -cne $attempt.SessionId -or $original.RunId -cne $attempt.RunId -or $original.Status -cne 'FAILED' -or $original.NormalMysqlStop -ne $false -or $original.ServiceMillisIsLowerBound -ne $true -or $original.ServiceMillis -lt 0 -or $sum+$original.ServiceMillis -ne $thirdFloor -or $original.ServiceMillisCumulative -ne $thirdFloor){throw 'CAP third original failure/floor mismatch'}
        $stop=Read-CapBoundFile $h.stop
        if($stop.RunId -cne $attempt.SessionId -or $stop.NormalShutdown -ne $false -or $stop.ProbeInvoked -ne $false -or $null -ne $stop.SqlExit){throw 'CAP third SQL stop history changed'}
        $continuation=Read-CapBoundFile $h.continuation
        $continuationInstruction=if($latest){$previous.instruction}else{$a.instruction}
        if($continuation.OriginalClosure.sha256 -ine $h.closure.sha256 -or $continuation.Instruction.sha256 -ine $continuationInstruction.sha256 -or $continuation.OriginalStatus -cne 'FAILED' -or $continuation.NormalShutdownConfirmed -ne $false -or $continuation.CurrentResourcesAbsent -ne $true){throw 'CAP absent-start continuation binding mismatch'}
        $oldObservation=Read-CapBoundFile $continuation.PriorAbsenceObservation
        $now=Read-CapBoundFile $continuation.CurrentObservation
        if(@($oldObservation.RemainingOwnedTestProcesses).Count -ne 0 -or @($oldObservation.Ports|Where-Object LocalPort -in @(3307,8080)).Count -or $oldObservation.MysqlNormalShutdownConfirmed -ne $false){throw 'CAP historical absence unverified'}
        if(@($now.RemainingOwnedTestProcesses).Count -ne 0 -or @($now.Ports|Where-Object LocalPort -in @(3307,8080)).Count -or $now.CurrentResourcesAbsent -ne $true -or -not $now.CommonGuardHeld -or -not $now.ControllerLockHeld){throw 'CAP current absence/lock unverified'}
        if(@($original.OwnedWorkers).Count -eq 0 -or @($original.OwnedWorkers|Where-Object {-not $_.Joined -or -not $_.Released}).Count){throw 'CAP third workers unresolved'}
        # 정상 종료를 소급 인정하지 않음. 발급부터 보존된 부재 관측까지 전 구간을 보수적으로 시간 계상
        $absentAt=([DateTimeOffset]::Parse($oldObservation.ObservedUtc)).ToUnixTimeMilliseconds()
        $charge=$absentAt-[long]$attempt.IssuedEpochMillis
        if($charge -lt $original.ServiceMillis -or $charge -ne $continuation.ServiceMillisBudgetCharge -or $continuation.RecordedServiceMillisFloor -ne $original.ServiceMillis -or ([DateTimeOffset]::Parse($now.ObservedUtc)).ToUnixTimeMilliseconds() -lt $absentAt){throw 'CAP absent-start time accounting mismatch'}
        $sum+=$charge;$historyIds+=$attempt.SessionId
    }
    if($latest){
        # 이번 5/1/6 승인에만 네 번째 FAILED와 SQL 로그 기반 별도 마감 연결. 원래 receipt 성공 변경 금지
        $h=$a.history[3];$attempt=Read-CapBoundFile $h.attempt;$issued=Read-CapBoundFile $h.issued;$original=Read-CapBoundFile $h.closure
        if((Get-CapControlIdentity $h.attempt.path) -ine [IO.Path]::GetFullPath("$Root/attempt-4.json") -or $attempt.Smoke -cne 'S1' -or @($Prior|Where-Object SessionId -eq $attempt.SessionId).Count -ne 1){throw 'CAP fourth attempt mismatch'}
        if($issued.SessionId -cne $attempt.SessionId -or $issued.RunId -cne $attempt.RunId -or $issued.issuedEpochMillis -ne $attempt.IssuedEpochMillis -or $issued.expiresEpochMillis -ne $attempt.ExpiresEpochMillis -or $attempt.ResumeApprovalHash -ine $a.previousApproval.sha256){throw 'CAP fourth issued mismatch'}
        if($original.SessionId -cne $attempt.SessionId -or $original.RunId -cne $attempt.RunId -or $original.Smoke -cne 'S1' -or $original.Status -cne 'FAILED' -or $original.NormalMysqlStop -ne $false -or $original.ServiceMillisIsLowerBound -ne $true -or $original.ServiceMillis -ne 138546 -or $original.RecordedServiceMillisCumulativeFloor -ne $floor -or $original.ServiceMillisCumulative -ne ($sum+$original.ServiceMillis)){throw 'CAP fourth original failure/floor mismatch'}
        if(@($original.OwnedWorkers).Count -eq 0 -or @($original.OwnedWorkers|Where-Object {-not $_.Joined -or -not $_.Released -or -not $_.DbUsersClosed}).Count){throw 'CAP fourth workers unresolved'}
        $stop=Read-CapBoundFile $h.stop;$continuation=Read-CapBoundFile $h.continuation
        if($stop.RunId -cne $attempt.SessionId -or $stop.NormalShutdown -ne $false -or $stop.ProbeInvoked -ne $true -or $stop.ShutdownObserved -ne $true -or $null -ne $stop.SqlExit){throw 'CAP fourth SQL stop history changed'}
        if($continuation.OriginalClosure.sha256 -ine $h.closure.sha256 -or $continuation.Instruction.sha256 -ine $a.instruction.sha256 -or $continuation.OriginalStatus -cne 'FAILED' -or $continuation.OriginalNormalShutdownFlag -ne $false -or $continuation.SqlShutdownLogObserved -ne $true -or $continuation.CurrentResourcesAbsent -ne $true -or $continuation.RecordedServiceMillisFloor -ne $original.ServiceMillis -or $continuation.ServiceMillisBudgetCharge -ne $original.ServiceMillis -or $continuation.HistoricalServiceTimeUncertain -ne $true){throw 'CAP fourth continuation binding/accounting mismatch'}
        $historical=Read-CapBoundFile $continuation.HistoricalObservation
        $priorAbsence=Read-CapBoundFile $historical.CurrentResources
        $now=Read-CapBoundFile $continuation.CurrentObservation
        if($historical.OriginalClosure.sha256 -ine $h.closure.sha256 -or $historical.OriginalStatus -cne 'FAILED' -or $historical.ServerLogNormalSqlShutdownObserved -ne $true -or $historical.ServerLogEvidence.sha256 -ine $continuation.ServerLogEvidence.sha256 -or $historical.ServiceMillisBudgetCharge -ne ($sum+$original.ServiceMillis) -or $historical.RecordedServiceMillisFloor -ne $floor){throw 'CAP fourth historical closure mismatch'}
        foreach($observation in @($priorAbsence,$now)){
            if(@($observation.RemainingOwnedTestProcesses).Count -ne 0 -or @($observation.Ports|Where-Object LocalPort -in @(3307,8080)).Count -or -not $observation.CommonGuardHeld -or -not $observation.ControllerLockHeld){throw 'CAP fourth current absence/lock unverified'}
        }
        if($priorAbsence.CurrentTestResourcesAbsent -ne $true -or $now.CurrentResourcesAbsent -ne $true -or ([DateTimeOffset]::Parse($now.ObservedUtc)) -lt ([DateTimeOffset]::Parse($priorAbsence.ObservedUtc))){throw 'CAP fourth current absence/lock unverified'}
        $pre=Read-CapBoundFile $continuation.StopPrecondition;$worker=Read-CapBoundFile $continuation.StopWorkerClosure
        if(-not $pre.DbUsersClosed -or -not $worker.Released -or @($worker.Workers).Count -eq 0 -or @($worker.Workers|Where-Object {-not $_.Joined -or -not $_.DbUsersClosed}).Count){throw 'CAP fourth supplemental Stop unresolved'}
        $owned=@($pre.VerifiedServices|Where-Object Pid -eq $stop.Live.Pid)
        if($owned.Count -ne 1 -or $owned[0].Binary -ine $stop.Live.Binary -or ([DateTimeOffset]::Parse($owned[0].StartUtc)).ToUnixTimeMilliseconds() -ne ([DateTimeOffset]::Parse($stop.Live.StartUtc)).ToUnixTimeMilliseconds()){throw 'CAP fourth SQL identity mismatch'}
        $logRef=$continuation.ServerLogEvidence
        if((Get-CapHistoricalHash -LiteralPath $logRef.path).Hash -ine $logRef.sha256){throw 'CAP fourth SQL log hash mismatch'}
        $log=Get-CapHistoricalContent -LiteralPath $logRef.path -Raw -Encoding UTF8
        $start=$log.LastIndexOf('starting as process '+$stop.Live.Pid)
        if($start -lt 0){throw 'CAP fourth SQL log identity absent'}
        $tail=$log.Substring($start);$shutdown=$tail.IndexOf('Received SHUTDOWN');$normal=$tail.IndexOf('Normal shutdown.');$complete=$tail.IndexOf('Shutdown complete')
        if($shutdown -lt 0 -or $normal -le $shutdown -or $complete -le $normal -or $tail.Substring(1).Contains('starting as process')){throw 'CAP fourth SQL log normal closure absent'}
        $sum+=[long]$original.ServiceMillis;$historyIds+=$attempt.SessionId
    }
    return @{ServiceMillis=$sum;RecordedServiceMillisFloor=$floor;ResolvedFailures=$historyIds;Total=$total;S1Max=$s1Max;HistoryCount=$historyCount;Extended=$extended}
}

# 기존 5/2/7 승인과 후속 CAP-0 완료 권한 연결. 소진 이력·실행별 동결/마감은 계속 검증
function Get-CapAdditionalS2Context($Registration,$Approval,[string]$Root,[string]$Repo,[string]$Smoke,$Prior,[string]$SupplementPath,[string]$SupplementHash) {
    # 호출 셸의 슬래시 표기를 원본 절대 경로와 같은 형식으로 대조
    $Root=[IO.Path]::GetFullPath($Root);$Repo=[IO.Path]::GetFullPath($Repo)
    $a=$Approval
    $completion=$a.kind -ceq 'cap0-completion'
    $limitsValid=if($completion){$null -eq $a.totalAttempts -and $null -eq $a.s2Max -and $null -eq $a.serviceMillisMax}else{
        $a.kind -ceq 'cap0-additional-s2-75min' -and $a.totalAttempts -eq 7 -and $a.s2Max -eq 2 -and $a.serviceMillisMax -eq 4500000}
    if(-not $limitsValid -or $Smoke -cne 'S2' -or $a.s1Max -ne 5 -or $a.reservationMillis -ne 2400000 -or $a.cleanupMillis -ne 180000){throw 'CAP additional S2 frozen limits mismatch'}
    if($a.authorizationId -cnotmatch '^[a-z0-9-]+$' -or -not $SupplementPath -or -not $SupplementHash){throw 'CAP additional S2 authorization/supplement required'}
    if($completion){
        # 완료 권한도 등록당 지정된 다음 시도 한 개만 발급. 재실행은 수정/검증 후 새 입력 등록 필요
        if($a.scope -cne 'CAP-0:S2-only:until-first-pass' -or ($a.nextAttemptNumber -isnot [int] -and $a.nextAttemptNumber -isnot [long]) -or
           $a.nextAttemptNumber -lt 8 -or $a.nextAttemptNumber -ne $Prior.Count+1){throw 'CAP completion single registered attempt mismatch'}
    }
    $slotPath=if($completion){"$Root/cap-resume-$($a.authorizationId)-S2-$($a.nextAttemptNumber).json"}else{"$Root/cap-resume-$($a.authorizationId)-S2.json"}
    if(Test-Path -LiteralPath $slotPath){throw 'CAP resume slot already consumed'}
    if(-not $completion -and $Prior.Count -ge 7){throw 'CAP resume attempts exhausted'}
    $expectedPrior=if($completion){6+@($a.continuationFailures).Count}else{6}
    if(($completion -and $expectedPrior -lt 7) -or $Prior.Count -ne $expectedPrior -or @($Prior|Where-Object Smoke -ceq 'S1').Count -ne 5 -or
       @($Prior|Where-Object Smoke -ceq 'S2').Count -ne ($expectedPrior-5)){throw 'CAP additional S2 preserved attempts mismatch'}
    if((Get-CapHistoricalHash -LiteralPath $a.instruction.path -ErrorAction Stop).Hash -ine $a.instruction.sha256){throw 'CAP resume instruction changed'}
    $oldRegistration=Read-CapBoundFile $a.previousRegistration
    $old=Read-CapBoundFile $a.previousApproval
    $oldPolicy=Read-CapBoundFile $a.previousPolicy
    if($old.schemaVersion -ne 3 -or $old.repo -ine $Repo -or (Get-CapControlIdentity $old.ledgerRoot) -ine $Root -or $old.authorizationId -ceq $a.authorizationId -or
       $old.instruction.sha256 -ieq $a.instruction.sha256 -or $oldRegistration.approval.path -ine $a.previousApproval.path -or
       $oldRegistration.approval.sha256 -ine $a.previousApproval.sha256 -or $oldRegistration.policy.path -ine $a.previousPolicy.path -or
       $oldRegistration.policy.sha256 -ine $a.previousPolicy.sha256){throw 'CAP additional S2 previous registration mismatch'}
    foreach($ref in @($old.instruction,$old.previousInstruction)){if((Get-CapHistoricalHash -LiteralPath $ref.path).Hash -ine $ref.sha256){throw 'CAP historical instruction changed'}}
    $null=Read-CapBoundFile $old.previousPolicy
    $seed=Read-CapBoundFile $old.budgetSeed
    $history=Get-CapResumeHistory $old $Root $Prior $seed
    $supplement=@{path=[IO.Path]::GetFullPath($SupplementPath);sha256=$SupplementHash}
    if($oldPolicy.resumeApproval.sha256 -ine $a.previousApproval.sha256 -or
       $oldPolicy.s1ClosureSupplement.path -ine $supplement.path -or $oldPolicy.s1ClosureSupplement.sha256 -ine $supplement.sha256){throw 'CAP additional S2 historical policy mismatch'}
    $historical=@{Approval=$a.previousApproval;Policy=$a.previousPolicy;S1Policy=$oldPolicy.s1Predecessor;S1ClosureSupplement=$supplement;HistoricalServiceTimeUncertain=$true}
    $s1=Read-CapBoundFile $a.s1Attempt
    if((Get-CapControlIdentity $a.s1Attempt.path) -ine [IO.Path]::GetFullPath("$Root/attempt-5.json")){throw 'CAP additional S2 S1 attempt mismatch'}
    $s1Closure=Assert-CapResumePass "$Repo/build/agent-runs/$($s1.SessionId)" $s1 $historical
    $h=$a.failedS2;$attempt=Read-CapBoundFile $h.attempt
    if((Get-CapControlIdentity $h.attempt.path) -ine [IO.Path]::GetFullPath("$Root/attempt-6.json") -or $attempt.Smoke -cne 'S2' -or
       $attempt.ResumeApprovalHash -ine $a.previousApproval.sha256 -or $attempt.ResumePolicyHash -ine $a.previousPolicy.sha256){throw 'CAP additional S2 failed attempt mismatch'}
    $out="$Repo/build/agent-runs/$($attempt.SessionId)"
    foreach($entry in @(@('issued','cap-issued.json'),@('closure','cap-closure.json'),@('stop','mysql-stop.json'))){
        if($h.($entry[0]).path -ine [IO.Path]::GetFullPath("$out/$($entry[1])")){throw 'CAP additional S2 failed evidence path mismatch'}
    }
    $issued=Read-CapBoundFile $h.issued;$closure=Read-CapBoundFile $h.closure;$stop=Read-CapBoundFile $h.stop;$slot=Read-CapBoundFile $h.slot
    if((Get-CapControlIdentity $h.slot.path) -ine [IO.Path]::GetFullPath("$Root/cap-resume-$($old.authorizationId)-S2.json")){throw 'CAP additional S2 failed slot path mismatch'}
    foreach($record in @($issued,$slot,$closure)){
        if($record.SessionId -cne $attempt.SessionId -or $record.RunId -cne $attempt.RunId -or $record.Smoke -cne 'S2' -or
           $record.ResumeApprovalHash -ine $a.previousApproval.sha256 -or $record.ResumePolicyHash -ine $a.previousPolicy.sha256){throw 'CAP additional S2 failed authorization mismatch'}
    }
    if($issued.issuedEpochMillis -ne $attempt.IssuedEpochMillis -or $slot.IssuedEpochMillis -ne $attempt.IssuedEpochMillis -or
       $issued.expiresEpochMillis -ne $attempt.ExpiresEpochMillis -or $closure.OriginalExpiresEpochMillis -ne $attempt.ExpiresEpochMillis){throw 'CAP additional S2 failed expiry mismatch'}
    Assert-CapClosed $closure $attempt
    if($closure.Status -cne 'FAILED' -or -not $closure.FailureMessage -or $closure.RunExit -ne 1 -or $closure.AnalysisExit -ne 0 -or -not $closure.BudgetAfterVerified -or
       $stop.RunId -cne $attempt.SessionId -or $stop.SqlExit -ne 0 -or -not $stop.NormalShutdown -or -not $stop.ShutdownObserved -or @($stop.Failures).Count){throw 'CAP additional S2 original failure/closure changed'}
    if(@($closure.OwnedWorkers).Count -eq 0 -or @($closure.OwnedWorkers|Where-Object {-not $_.Joined -or -not $_.Released -or -not $_.DbUsersClosed -or $null -eq $_.ActualExit}).Count){throw 'CAP additional S2 failed workers unresolved'}
    $index=Read-CapBoundFile $closure.EvidenceIndex
    if($index.RunId -cne $attempt.RunId -or @($index.Files).Count -eq 0){throw 'CAP additional S2 failed index missing'}
    foreach($ref in $index.Files){if((Get-CapHistoricalHash -LiteralPath $ref.path).Hash -ine $ref.sha256){throw 'CAP additional S2 failed evidence changed'}}
    $sum=[long]$history.ServiceMillis+[long]$s1Closure.ServiceMillis+[long]$closure.ServiceMillis
    $floor=[long]$history.RecordedServiceMillisFloor+[long]$s1Closure.ServiceMillis+[long]$closure.ServiceMillis
    if($s1Closure.ServiceMillisCumulative -ne ($history.ServiceMillis+$s1Closure.ServiceMillis) -or
       $issued.ServiceMillisBefore -ne $s1Closure.ServiceMillisCumulative -or $closure.ServiceMillisCumulative -ne $sum -or
       $closure.RecordedServiceMillisCumulativeFloor -ne $floor -or (-not $completion -and $a.baselineServiceMillis -ne $sum) -or
       (-not $completion -and $a.recordedServiceMillisFloor -ne $floor) -or $a.historicalServiceTimeUncertain -ne $true){throw 'CAP additional S2 preserved consumption mismatch'}
    $continuationIds=@()
    if($completion){
        # 모든 후속 실패를 등록 순서로 연결. 실패 전 단계에 따라 Run/Analysis는 null일 수 있음
        for($i=0;$i -lt @($a.continuationFailures).Count;$i++){
            $h=$a.continuationFailures[$i];$number=7+$i;$next=Read-CapBoundFile $h.attempt
            if((Get-CapControlIdentity $h.attempt.path) -ine [IO.Path]::GetFullPath("$Root/attempt-$number.json") -or $next.Smoke -cne 'S2' -or
               @($Prior|Where-Object SessionId -ceq $next.SessionId).Count -ne 1){throw 'CAP completion attempt sequence mismatch'}
            $failedOut="$Repo/build/agent-runs/$($next.SessionId)"
            foreach($entry in @(@('issued','cap-issued.json'),@('closure','cap-closure.json'),@('stop','mysql-stop.json'))){
                if($h.($entry[0]).path -ine [IO.Path]::GetFullPath("$failedOut/$($entry[1])")){throw 'CAP completion failure evidence path mismatch'}
            }
            $nextIssued=Read-CapBoundFile $h.issued;$nextClosure=Read-CapBoundFile $h.closure;$nextSlot=Read-CapBoundFile $h.slot
            $nextApproval=Read-CapBoundFile $nextIssued.ResumeApproval;$nextPolicy=Read-CapBoundFile $nextIssued.ResumePolicy
            if($nextPolicy.resumeApproval.sha256 -ine $next.ResumeApprovalHash -or $nextIssued.ResumeApproval.sha256 -ine $next.ResumeApprovalHash -or
               $nextIssued.ResumePolicy.sha256 -ine $next.ResumePolicyHash){throw 'CAP completion failure registration mismatch'}
            $expectedSlot=if($nextApproval.kind -ceq 'cap0-completion'){"$Root/cap-resume-$($nextApproval.authorizationId)-S2-$number.json"}else{"$Root/cap-resume-$($nextApproval.authorizationId)-S2.json"}
            if((Get-CapControlIdentity $h.slot.path) -ine [IO.Path]::GetFullPath($expectedSlot)){throw 'CAP completion failure slot mismatch'}
            foreach($record in @($nextIssued,$nextClosure,$nextSlot)){
                if($record.SessionId -cne $next.SessionId -or $record.RunId -cne $next.RunId -or $record.Smoke -cne 'S2' -or
                   $record.ResumeApprovalHash -ine $next.ResumeApprovalHash -or $record.ResumePolicyHash -ine $next.ResumePolicyHash){throw 'CAP completion failure identity mismatch'}
            }
            Assert-CapClosed $nextClosure $next
            $stop=Read-CapBoundFile $h.stop
            if($nextClosure.Status -cne 'FAILED' -or -not $nextClosure.FailureMessage -or -not $nextClosure.BudgetAfterVerified -or
               $nextIssued.issuedEpochMillis -ne $next.IssuedEpochMillis -or $nextSlot.IssuedEpochMillis -ne $next.IssuedEpochMillis -or
               $nextIssued.expiresEpochMillis -ne $next.ExpiresEpochMillis -or $nextClosure.OriginalExpiresEpochMillis -ne $next.ExpiresEpochMillis -or
               $stop.RunId -cne $next.SessionId -or $stop.SqlExit -ne 0 -or -not $stop.NormalShutdown -or -not $stop.ShutdownObserved -or @($stop.Failures).Count){throw 'CAP completion failure/closure mismatch'}
            if(@($nextClosure.OwnedWorkers).Count -eq 0 -or @($nextClosure.OwnedWorkers|Where-Object {-not $_.Joined -or -not $_.Released -or -not $_.DbUsersClosed -or $null -eq $_.ActualExit}).Count){throw 'CAP completion failed workers unresolved'}
            $index=Read-CapBoundFile $nextClosure.EvidenceIndex
            if($index.RunId -cne $next.RunId -or @($index.Files).Count -eq 0){throw 'CAP completion failure index missing'}
            foreach($ref in $index.Files){if((Get-CapHistoricalHash -LiteralPath $ref.path).Hash -ine $ref.sha256){throw 'CAP completion failure evidence changed'}}
            if($nextIssued.ServiceMillisBefore -ne $sum -or $nextIssued.RecordedServiceMillisFloorBefore -ne $floor){throw 'CAP completion prior consumption mismatch'}
            $sum+=[long]$nextClosure.ServiceMillis;$floor+=[long]$nextClosure.ServiceMillis
            if($nextClosure.ServiceMillisCumulative -ne $sum -or $nextClosure.RecordedServiceMillisCumulativeFloor -ne $floor -or -not $nextClosure.HistoricalServiceTimeUncertain){throw 'CAP completion cumulative consumption mismatch'}
            $lastFailureLedger=Read-CapBoundFile @{path=$nextClosure.Budget.ledger;sha256=$nextClosure.Budget.sha256}
            if(-not $lastFailureLedger.gateMet -or -not $lastFailureLedger.transition.closed){throw 'CAP completion previous budget unresolved'}
            $continuationIds+=$next.SessionId
        }
        if($a.baselineServiceMillis -ne $sum -or $a.recordedServiceMillisFloor -ne $floor){throw 'CAP completion preserved consumption mismatch'}
    }
    if(-not $completion -and $sum+[long]$a.reservationMillis -gt [long]$a.serviceMillisMax){throw 'CAP resume cumulative service reservation exhausted'}
    $ledger=Read-CapBoundFile $a.previousLedger
    if(((-not $completion) -and ($closure.Budget.ledger -ine $a.previousLedger.path -or $closure.Budget.sha256 -ine $a.previousLedger.sha256)) -or
       ($completion -and $ledger.sequence -lt $lastFailureLedger.sequence) -or -not $ledger.gateMet -or -not $ledger.transition.closed){throw 'CAP additional S2 previous ledger mismatch'}
    $allIssued=@(Get-CapIssuedFiles $Repo)
    if($allIssued.Count -ne $Prior.Count){throw 'CAP resume unregistered issued attempt present'}
    foreach($file in $allIssued){$v=Get-CapHistoricalContent -LiteralPath $file.FullName -Raw -Encoding UTF8|ConvertFrom-Json;if(@($Prior|Where-Object SessionId -ceq $v.SessionId).Count -ne 1){throw 'CAP resume issued history mismatch'}}
    $policy=Read-CapBoundFile $Registration.policy
    if($policy.resumeApproval.path -ine $Registration.approval.path -or $policy.resumeApproval.sha256 -ine $Registration.approval.sha256 -or
       $policy.previousPolicy.path -ine $a.previousPolicy.path -or $policy.previousPolicy.sha256 -ine $a.previousPolicy.sha256 -or
       $policy.instruction.sha256 -ine $a.instruction.sha256 -or $policy.s1ClosureSupplement.sha256 -ine $supplement.sha256 -or
       $policy.s1Predecessor.sha256 -ine $oldPolicy.s1Predecessor.sha256){throw 'CAP additional S2 policy chain mismatch'}
    # 승인된 항목 외의 부하/성능/소진/메모리/시간 계약은 원본과 동일해야 함
    $allowed=@('instruction','revision','previousPolicy','resumeApproval','currentExecutionGate','sourceBinding','budgets','k6','runtimeReceipt','runtimeClasspath')
    if(@($policy.PSObject.Properties.Name|Where-Object {$_ -notin @($oldPolicy.PSObject.Properties.Name)+@('runtimeReceipt','runtimeClasspath')}).Count){throw 'CAP additional S2 unknown policy field'}
    foreach($key in $oldPolicy.PSObject.Properties.Name){
        if($key -notin $allowed -and ($policy.$key|ConvertTo-Json -Depth 30 -Compress) -cne ($oldPolicy.$key|ConvertTo-Json -Depth 30 -Compress)){throw 'CAP additional S2 frozen policy changed'}
    }
    if($policy.budgets.smokeAttemptsMax -ne $a.totalAttempts -or $policy.budgets.newServiceMillisMax -ne $a.serviceMillisMax -or
       $policy.budgets.resumedPerSmokeMax.S1 -ne 5 -or $policy.budgets.resumedPerSmokeMax.S2 -ne $a.s2Max -or $policy.budgets.minimumFreeEach -ne 10737418240){throw 'CAP additional S2 policy limits mismatch'}
    foreach($key in @('nativeMemoryBytes','analysisMemoryBytes','benchmarkAccessTokenSeconds','productionAccessTokenSeconds','inputKind')){
        if($policy.k6.$key -ne $oldPolicy.k6.$key){throw 'CAP additional S2 k6 contract changed'}
    }
    $profile=Assert-CapK6Binding $policy $policy.k6.executable.path $policy.k6.reservations.S2.path 'S2'
    $reservation=Read-CapBoundFile $policy.k6.reservations.S2
    if($reservation.storagePolicy.previousHash -ine $a.previousLedger.sha256 -or $policy.budgets.perTrialReservationMax -ne $reservation.registeredTrialBytes -or
       $policy.budgets.CBytes -ne $reservation.storagePolicy.limits.C -or $policy.budgets.FBytes -ne $reservation.storagePolicy.limits.F){throw 'CAP additional S2 storage policy mismatch'}
    if((Get-CapHistoricalHash -LiteralPath $policy.runtimeClasspath.path).Hash -ine $policy.runtimeClasspath.sha256){throw 'CAP additional S2 classpath changed'}
    $receipt=Read-CapBoundFile $policy.runtimeReceipt
    if(@($receipt.inputs).Count -eq 0 -or @($receipt.runtime).Count -eq 0 -or -not $policy.sourceBinding){throw 'CAP additional S2 runtime/source missing'}
    if(Compare-Object @($receipt.inputs.path) @($policy.sourceBinding.PSObject.Properties.Name)){throw 'CAP additional S2 source set mismatch'}
    foreach($input in $receipt.inputs){
        $path=[IO.Path]::GetFullPath("$Repo/$($input.path)")
        if(-not $path.StartsWith(([IO.Path]::GetFullPath($Repo).TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase) -or
           $input.sha256 -ine $policy.sourceBinding.($input.path) -or (Get-CapHistoricalHash -LiteralPath $path).Hash -ine $input.sha256){throw 'CAP additional S2 current source changed'}
    }
    foreach($file in $receipt.runtime){if((Get-CapHistoricalHash -LiteralPath $file.path).Hash -ine $file.sha256){throw 'CAP additional S2 runtime changed'}}
    return @{Approval=$Registration.approval;Policy=$Registration.policy;S1Policy=$oldPolicy.s1Predecessor;S1ClosureSupplement=$supplement;ApprovalId=$a.authorizationId;
        ServiceMillis=$sum;RecordedServiceMillisFloor=$floor;HistoricalServiceTimeUncertain=$true;ResolvedFailures=@($history.ResolvedFailures)+@($attempt.SessionId)+@($continuationIds);SlotPath=$slotPath}
}
