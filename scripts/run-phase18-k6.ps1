# k6 전용 실제 exit/summary 경계. 공통 Wait-CapWorker의 실패 차단은 변경하지 않음
param([Parameter(Mandatory=$true)][string]$InputPath,[Parameter(Mandatory=$true)][string]$K6)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/pixel-place-work.ps1"
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/phase17-c-process.ps1"
[Phase18Cap.JobCapture]::ProtectStandardHandles()
Assert-PixelPlaceExecutionEnvironment
Assert-PixelPlaceWorkPath $InputPath
Assert-PixelPlaceWorkPath $K6
$inputData=Get-Content -LiteralPath $InputPath -Raw -Encoding UTF8 | ConvertFrom-Json
if($inputData.schema -cne 'phase18-k6-v2' -or $inputData.bridge -notmatch '^http://127\.0\.0\.1:[0-9]{1,5}$'){throw 'Explicit k6 input required'}
if(-not $env:PIXEL_PLACE_BENCH_JWT_KEY){throw 'Private bridge credential environment required'}
$clock=New-CapClock $inputData.expiresEpochMillis
$root=Split-Path $InputPath -Parent
$captures=[Collections.Generic.List[object]]::new()
$results=[Collections.Generic.List[object]]::new()
$failure=$null;$cleanupClock=$null
$version=& $K6 version
if($LASTEXITCODE -ne 0 -or $version -notmatch '^k6(?:\.exe)? v2\.3\.0 '){throw 'Pinned k6 2.3.0 required'}
# 환경은 이번 wrapper와 자식에만 적용. HTTP debug/Cloud/HTML의 비밀 출력 경로 차단
foreach($name in @(Get-ChildItem Env: | Where-Object Name -Like 'K6_*' | Select-Object -ExpandProperty Name)){Remove-Item "Env:$name"}
$env:K6_NO_USAGE_REPORT='true'
$env:K6_NO_COLOR='true'
$env:K6_WEB_DASHBOARD='false'
$env:K6_SUMMARY_MODE='compact'
$env:GOMEMLIMIT='512MiB'
$revised=$false
$k6ProcessMemory=536870912L;$k6CombinedMemory=536870912L
if($inputData.limits.combinedMemoryBytes){
    $cap1=$inputData.plan.stage -ceq 'CAP-1' -and $inputData.plan.bounds.samples -gt 10000
    $revised=$cap1 -and $inputData.plan.schemaVersion -eq 4
    $expectedProcess=if($revised){1342177280L}elseif($cap1){805306368L}else{536870912L};$expectedCombined=if($revised){1879048192L}elseif($cap1){1342177280L}else{536870912L}
    if($inputData.limits.processMemoryBytes -ne $expectedProcess -or $inputData.limits.combinedMemoryBytes -ne $expectedCombined){throw 'Frozen k6 process/combined reservation mismatch'}
    $k6ProcessMemory=$expectedProcess;$k6CombinedMemory=$expectedCombined
}
if($revised -and ($inputData.limits.goRuntime.gcPercent -ne 100 -or $inputData.limits.goRuntime.memoryBytes -ne 536870912L)){throw 'Frozen k6 Go runtime reservation mismatch'}
$env:GOGC='100'
$env:GOMEMLIMIT='512MiB'
$env:PHASE18_K6_INPUT=$InputPath
. "$PSScriptRoot/phase18-k6-process.ps1"
try {
    if($inputData.plan.schemaVersion -eq 4){
        $script:k6NativeWriter=[IO.StreamWriter]::new([IO.File]::Open("$root/k6-native-memory.jsonl",'CreateNew','Write','Read'),[Text.UTF8Encoding]::new($false))
        $script:k6NativeRows=0;$script:k6NativeBytes=0;$script:k6NativeLast=0;$script:k6NativeMaxRows=[Math]::Ceiling((Get-CapRemaining $clock)/250)+32
    }
    Save-CapJson "$root/k6-runtime.json" @{Version=$version;BinarySha256=(Get-FileHash -LiteralPath $K6).Hash;ScriptSha256=(Get-FileHash -LiteralPath "$PSScriptRoot/phase18-k6.js").Hash;Expires=$clock.Expires;MaxProcessMemory=$k6ProcessMemory;MaxCombinedMemory=$k6CombinedMemory;GoMemoryLimit=$env:GOMEMLIMIT;GoGcPercent=[int]$env:GOGC;MaxMetricsBytes=$inputData.limits.metricsBytes;MaxConcurrentProcesses=2}
    $w=$inputData.plan.cases[0].workload
    if($w.bootstrap){$item=Start-K6 'bootstrap';Wait-K6 $item;$null=Invoke-Bridge '/finish' @{phase='bootstrap'}}
    $ws=$null
    if($w.wsConnections -gt 0){
        $ws=Start-K6 'ws'
        $readyUntil=[Math]::Min($clock.Expires,[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+$w.readyMillis)
        do {
            if([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $readyUntil -or $ws.Capture.Process.HasExited){throw 'WS readiness incomplete'}
            $state=Invoke-Bridge '/state'
            if($state.stop){throw 'WS preparation stopped'}
            Start-Sleep -Milliseconds 25
        } while($state.readyConnections -ne $w.wsConnections)
    }
    $phases=@();if($w.schedule.kind -eq 'users'){$phases+='initial-read'};$phases+=@('warmup','measurement')
    foreach($phase in $phases){
        if($phase -eq 'warmup' -and $w.warmupSeconds -eq 0){$null=Invoke-Bridge '/begin' @{phase=$phase}}
        else{$item=Start-K6 $phase;Wait-K6 $item}
        $null=Invoke-Bridge '/finish' @{phase=$phase}
    }
    $receiveUntil=[Math]::Min($clock.Expires,[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+$w.drainMillis)
    do {
        $receipts=Invoke-Bridge '/close-ws'
        if(-not $receipts.closing){
            if([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $receiveUntil){throw 'WS receive drain incomplete'}
            Start-Sleep -Milliseconds 25
        }
    } while(-not $receipts.closing)
    if($ws){Wait-K6 $ws}
    if($inputData.plan.schemaVersion -eq 4){foreach($completed in $captures){Complete-K6Output $completed}}
}catch{
    $failure=$_.Exception.Message
    # 실패 소유 마감도 원래 만료 잔여와 180초 중 작은 구간으로 고정
    $cleanupClock=$clock
    if((Get-CapRemaining $clock) -gt 0){$cleanupClock=New-CapClock ([Math]::Min($clock.Expires,[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+180000))}
    try{$null=Invoke-Bridge '/stop' @{reason=$failure}}catch{}
}
finally {
    if(-not $cleanupClock){
        $cleanupClock=$clock
        if((Get-CapRemaining $clock) -gt 0){$cleanupClock=New-CapClock ([Math]::Min($clock.Expires,[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+180000))}
    }
    foreach($item in $captures){
        if($item.Released){continue}
        if(-not $item.Started){
            try{if(@($item.Capture.Members()).Count -eq 0){$item.Capture.Release();$item.Released=$true}}catch{}
            continue
        }
        while($item.Started -and -not(Test-K6Joined $item) -and (Get-CapRemaining $cleanupClock) -gt 0){Start-Sleep -Milliseconds 50}
        if(-not(Test-K6Joined $item)){
            if(-not $failure){$failure='owned_k6_descendants_unresolved'}
            try{Save-CapJson "$root/k6-$($item.Phase)-unresolved.json" @{OriginalExpires=$clock.Expires;CleanupExpires=$cleanupClock.Expires;FirstFailure=$failure;Pid=$item.Capture.Process.Id;Members=@($item.Capture.Members());NextTaskAllowed=$false}}
            catch{[Console]::Error.WriteLine('K6_UNRESOLVED_EVIDENCE_FAILED')}
            # 관측 기한 실패 뒤에는 새 실행 없이 실제 소유만 유지. wrapper 종료로 살아 있는 Job을 버리지 않음
            while(-not(Test-K6Joined $item)){Start-Sleep -Milliseconds 50}
        }
        if($item.Started -and (Test-K6Joined $item)){
            $item.Exit=$item.Capture.Process.ExitCode
            try{$item.Capture.Release();$item.Released=$true}catch{if(-not $failure){$failure=$_.Exception.Message}}
        }elseif(-not $failure){$failure='owned_k6_descendants_unresolved'}
    }
    if($script:k6NativeWriter){try{$script:k6NativeWriter.Dispose()}catch{if(-not $failure){$failure='native_observation_close'}};$script:k6NativeWriter=$null}
    if($script:k6BridgeClient){$script:k6BridgeClient.Dispose();$script:k6BridgeClient=$null}
    try{Assert-K6OutputBudget}catch{if(-not $failure){$failure=$_.Exception.Message}}
    try{Save-CapJson "$root/k6-runner.json" @{Schema='phase18-k6-runner-v1';Complete=($null -eq $failure);FirstFailure=$failure;Expires=$clock.Expires;CleanupExpires=$cleanupClock.Expires;PeakCombinedResident=$script:k6PeakResident;PeakCombinedPrivate=$script:k6PeakPrivate;Results=$results;Processes=@($captures|ForEach-Object {@{Phase=$_.Phase;ActualExit=$_.Exit;Released=$_.Released;Failure=$_.Failure;PeakPrivateBytes=$_.PeakPrivateBytes;PeakWorkingSet=$_.PeakWorkingSet}});FinalClassification='NOT_EVALUATED'}}
    catch{[Console]::Error.WriteLine("K6_FINAL_SAVE_FAILED firstFailure=$failure");throw}
}
if($failure){throw $failure}
Assert-K6OutputBudget
