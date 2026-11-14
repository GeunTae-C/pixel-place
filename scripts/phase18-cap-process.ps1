# CAP 최초 만료, Job 후손, 사전 서비스 인계, 증거 실패의 공동 경계
Import-Module "$PSHOME/Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1" -Global -ErrorAction Stop
function Save-CapJson([string]$Path,$Value) {
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    try{$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Value|ConvertTo-Json -Depth 24)+"`n");$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
}
function New-CapClock([long]$Expires) {
    $remaining=$Expires-[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if($remaining -le 0){throw 'CAP original deadline exhausted'}
    [pscustomobject]@{Expires=$Expires;Millis=$remaining;Watch=[Diagnostics.Stopwatch]::StartNew()}
}
function Get-CapRemaining($Clock) {[long][Math]::Max(0,[Math]::Min($Clock.Millis-$Clock.Watch.ElapsedMilliseconds,$Clock.Expires-[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()))}
function Get-CapOperationMillis([int]$Maximum=45000) {
    if(-not $env:PIXEL_PLACE_CAP_EXPIRES){return $Maximum}
    $left=[long]$env:PIXEL_PLACE_CAP_EXPIRES-[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if($left -le 0 -or ($env:PIXEL_PLACE_CAP_STOP -and (Test-Path -LiteralPath $env:PIXEL_PLACE_CAP_STOP))){throw 'CAP original deadline/STOP before operation'}
    return [int][Math]::Min($Maximum,$left)
}
function Set-CapFailure($Owned,[string]$Message) {if(-not $Owned.Failure){$Owned.Failure=$Message}}
function Test-CapStreams($Owned) {
    $c=$Owned.Capture
    foreach($stream in @($c.Out,$c.Err)){if($stream.Overflow -or $stream.Failure){Set-CapFailure $Owned 'CAP process log collection failed'}}
    foreach($task in @($c.OutTask,$c.ErrTask)){if($null -ne $task -and ($task.IsFaulted -or $task.IsCanceled)){Set-CapFailure $Owned 'CAP process stream task failed'}}
}
function Test-CapWorkerExit($Owned) {
    $c=$Owned.Capture
    return $c.Process.HasExited -and $null -ne $c.OutTask -and $c.OutTask.IsCompleted -and $null -ne $c.ErrTask -and $c.ErrTask.IsCompleted
}
function Test-CapJoined($Owned,[switch]$Final) {
    if(-not $Owned.Started){return $true}
    if(-not(Test-CapWorkerExit $Owned)){return $false}
    try {
        $members=@($Owned.Capture.Members())
        if($Final){return $members.Count -eq 0}
        foreach($id in $members){if($id -notin @($Owned.Services.Pid)){return $false}}
        foreach($service in $Owned.Services){if($service.Pid -in $members){
            $p=Get-Process -Id $service.Pid -ErrorAction Stop
            if($p.StartTime.ToUniversalTime().ToString('o') -cne $service.StartUtc -or $p.Path -ine $service.Binary){throw 'Transferred service identity changed'}
        }}
        return $true
    }catch{Set-CapFailure $Owned ('CAP ownership observation failed: '+$_.Exception.Message);return $false}
}
# MySQL가 만든 Windows 콘솔도 Job 소유 후손임. 이름만으로 허용하지 않고 경로·부모·시작 identity 대조
function Get-CapMysqlConsoleServices($Confirmed,$Members) {
    $consoles=@()
    foreach($id in $Members){
        if($id -in @($Confirmed.Pid)){continue}
        $c=Get-CimInstance Win32_Process -Filter "ProcessId=$id" -OperationTimeoutSec 2
        $parent=@($Confirmed|Where-Object Pid -eq $c.ParentProcessId)
        $expected=[IO.Path]::GetFullPath("$env:WINDIR/System32/conhost.exe")
        if($null -eq $c -or $c.Name -ine 'conhost.exe' -or $c.ExecutablePath -ine $expected -or $parent.Count -ne 1 -or [IO.Path]::GetFileName($parent[0].Binary) -ine 'mysqld.exe'){throw 'Unconfirmed MySQL descendant'}
        $p=Get-Process -Id $id -ErrorAction Stop
        if($p.Path -ine $expected -or ([DateTimeOffset]$p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() -ne ([DateTimeOffset]$c.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds() -or $p.StartTime.ToUniversalTime() -lt [DateTime]::Parse($parent[0].StartUtc).ToUniversalTime()){throw 'MySQL console identity changed'}
        $consoles+=@{Pid=$id;StartUtc=$p.StartTime.ToUniversalTime().ToString('o');Binary=$p.Path;BinarySha256=(Get-FileHash -LiteralPath $p.Path).Hash;Purpose='owned-mysql-console'}
    }
    return $consoles
}
function Receive-CapService($Owned,[switch]$CleanupOnly,$Clock) {
    if(-not $Owned.Started -or -not $Owned.ServiceRole -or $Owned.Services.Count -or ($CleanupOnly -and ($Owned.CleanupServices.Count -or $Owned.CleanupFailure))){return}
    if($CleanupOnly -and $Clock -and (Get-CapRemaining $Clock) -le 0){return}
    if(-not(Test-CapWorkerExit $Owned)){return}
    if(-not $CleanupOnly -and $Owned.Capture.Process.ExitCode -ne 0){return}
    Test-CapStreams $Owned
    if(-not $CleanupOnly -and $Owned.Failure){return}
    if($CleanupOnly -and $Owned.ServiceRole -cne 'mysql3307'){return}
    try {
        $offerPath="$($Owned.RequestPath).service.json"
        if($CleanupOnly -and (Test-Path "$($Owned.RequestPath).cleanup-service.json")){$offerPath="$($Owned.RequestPath).cleanup-service.json"}
        $offer=Get-Content -LiteralPath $offerPath -Raw -Encoding UTF8|ConvertFrom-Json
        $cleanupOffer=$offerPath.EndsWith('.cleanup-service.json')
        if($offer.Role -cne $Owned.ServiceRole -or $offer.Request -cne $Owned.RequestPath -or -not @($offer.Processes).Count){throw 'Service offer contract mismatch'}
        if($cleanupOffer){if($offer.Ready -ne $false -or $offer.Purpose -cne 'normal-stop-only'){throw 'Cleanup-only contract mismatch'}}
        elseif($offer.Ready -ne $true){throw 'Service ready contract mismatch'}
        if(-not $offer.ReadinessReceipt -or (Get-FileHash -LiteralPath $offer.ReadinessReceipt -ErrorAction Stop).Hash -ine $offer.ReadinessHash){throw 'Service readiness evidence missing or changed'}
        $members=@($Owned.Capture.Members());$confirmed=@()
        foreach($identity in $offer.Processes){
            if($identity.Pid -notin $members -or $identity.Pid -in @($confirmed.Pid)){throw 'Service is not a unique owned descendant'}
            $p=Get-Process -Id $identity.Pid -ErrorAction Stop
            if($p.StartTime.ToUniversalTime().ToString('o') -cne $identity.StartUtc -or $p.Path -ine $identity.Binary){throw 'Service actual identity mismatch'}
            if((Get-FileHash -LiteralPath $p.Path).Hash -ine $identity.BinarySha256){throw 'Service executable hash mismatch'}
            $confirmed+= $identity
        }
        if($Owned.ServiceRole -ceq 'mysql3307'){$confirmed+=@(Get-CapMysqlConsoleServices $confirmed $members)}
        # durable 인계 증거 전에는 서비스 예외 미적용. Job handle은 controller가 계속 소유
        if($CleanupOnly){
            if($Clock -and (Get-CapRemaining $Clock) -le 0){throw 'Cleanup handoff deadline exhausted'}
            # 실패한 준비를 READY로 바꾸지 않음. 실제 소유 후손의 정상 Stop 전용 증거
            Save-CapJson "$($Owned.RequestPath).cleanup-handoff.json" @{Role=$Owned.ServiceRole;Ready=$false;Purpose='normal-stop-only';OwnerPid=$PID;Processes=$confirmed;OfferHash=(Get-FileHash -LiteralPath $offerPath).Hash}
            $Owned.CleanupServices=$confirmed
            return
        }
        Save-CapJson "$($Owned.RequestPath).handoff.json" @{Role=$Owned.ServiceRole;Ready=$true;OwnerPid=$PID;Processes=$confirmed;OfferHash=(Get-FileHash -LiteralPath "$($Owned.RequestPath).service.json").Hash}
        $Owned.Services=$confirmed
    }catch{
        $message='CAP service handoff failed: '+$_.Exception.Message
        # 완료된 worker의 잘못된 인계/저장 실패는 재시도하지 않음. 최초 실행 실패와 별도 보존
        if($CleanupOnly -and -not $Owned.CleanupFailure){$Owned.CleanupFailure=$message}
        Set-CapFailure $Owned $message
    }
}
function Start-CapWorker($Request,[string]$Path,$Clock,$Registry) {
    if(-not ('Phase18Cap.JobCapture' -as [type])){. "$PSScriptRoot/phase17-c-process.ps1"}
    if($Request.ExpiresEpochMillis -ne $Clock.Expires){throw 'Worker expiry must match its owning clock'}
    if((Get-CapRemaining $Clock) -le 0){throw 'CAP original deadline exhausted before spawn'}
    Save-CapJson $Path $Request
    $owned=[pscustomobject]@{RequestPath=$Path;Capture=[Phase18Cap.JobCapture]::new();Started=$false;Failure=$null;Pid=$null;StartUtc=$null;Result=$null;Clock=$Clock;Services=@();CleanupServices=@();CleanupFailure=$null;ServiceRole=$Request.ServiceRole}
    $Registry.Add($owned)
    $worker="$PSScriptRoot/phase18-cap-worker.ps1"
    $command="& '"+$worker.Replace("'","''")+"' -RequestPath '"+$Path.Replace("'","''")+"'; exit `$LASTEXITCODE"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    try{
        $owned.Capture.Start("$env:WINDIR/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile -NonInteractive -EncodedCommand $encoded",$Request.Repo,"$Path.stdout.log","$Path.stderr.log",1048576)
        $owned.Started=$true;$owned.Pid=$owned.Capture.Process.Id;$owned.StartUtc=$owned.Capture.Process.StartTime.ToUniversalTime().ToString('o')
        Save-CapJson "$Path.identity.json" @{Pid=$owned.Pid;StartUtc=$owned.StartUtc;Request=$Path;ExpiresEpochMillis=$Request.ExpiresEpochMillis;OriginalExpiresEpochMillis=$Request.OriginalExpiresEpochMillis;Ownership='atomic Windows Job membership at process creation'}
        return $owned
    }catch{Set-CapFailure $owned $_.Exception.Message;try{$owned.Pid=$owned.Capture.Process.Id;$owned.Started=$true}catch{};throw}
}
function Wait-CapWorker($Owned,$Clock) {
    if(-not [object]::ReferenceEquals($Owned.Clock,$Clock)){throw 'Worker cannot receive a fresh execution clock'}
    while($true){
        if((Get-CapRemaining $Clock) -le 0){Set-CapFailure $Owned 'CAP original deadline exhausted';break}
        Test-CapStreams $Owned;Receive-CapService $Owned
        # 살아 있는 서비스 후손이 있어도 최초 worker nonzero는 즉시 실패로 보존
        if((Test-CapWorkerExit $Owned) -and $Owned.Capture.Process.ExitCode -ne 0){Set-CapFailure $Owned "CAP child exit $($Owned.Capture.Process.ExitCode)"}
        if($Owned.Failure -or (Test-CapJoined $Owned)){break}
        Start-Sleep -Milliseconds ([int][Math]::Min(50,(Get-CapRemaining $Clock)))
    }
    $joined=Test-CapJoined $Owned
    # 완료 경계 뒤 최종 검사. Dispose/마지막 task fault도 다음 단계 차단
    Test-CapStreams $Owned
    $exit=if(Test-CapWorkerExit $Owned){$Owned.Capture.Process.ExitCode}else{$null}
    if($joined -and $null -eq $exit){Set-CapFailure $Owned 'CAP child exit unavailable'}
    if($null -ne $exit -and $exit -ne 0){Set-CapFailure $Owned "CAP child exit $exit"}
    if(-not $joined){Set-CapFailure $Owned 'CAP owned descendants unresolved'}
    if($Owned.Failure -and -not(Test-Path "$($Owned.RequestPath).stop.json")){try{Save-CapJson "$($Owned.RequestPath).stop.json" @{Reason=$Owned.Failure}}catch{}}
    $Owned.Result=@{Pid=$Owned.Pid;StartUtc=$Owned.StartUtc;Exit=$exit;Joined=$joined;FirstFailure=$Owned.Failure;Services=$Owned.Services;NextTaskAllowed=($joined -and -not $Owned.Failure)}
    $Owned.Result.WorkerExited=$Owned.Capture.Process.HasExited;$Owned.Result.OutCompleted=$Owned.Capture.OutTask.IsCompleted;$Owned.Result.ErrCompleted=$Owned.Capture.ErrTask.IsCompleted;$Owned.Result.Members=@($Owned.Capture.Members())
    $Owned.Result.MemberIdentities=@(foreach($member in $Owned.Result.Members){Get-CimInstance Win32_Process -Filter "ProcessId=$member" -OperationTimeoutSec 2|Select-Object Name,ProcessId,ParentProcessId,ExecutablePath,CreationDate})
    try{Save-CapJson "$($Owned.RequestPath).result.json" $Owned.Result}catch{Set-CapFailure $Owned ('CAP result save failed: '+$_.Exception.Message);$Owned.Result.FirstFailure=$Owned.Failure;$Owned.Result.NextTaskAllowed=$false;throw}
    if($Owned.Failure){throw $Owned.Failure}
    return $Owned.Result
}
# DB 사용 worker/stream 종료와 준비 성공은 별도 경계. 승인된 서비스만 남은 경우 Stop 허용
function Test-CapDbUsersClosed($Owned) {
    if($Owned.CleanupFailure){return $false}
    if(Test-CapJoined $Owned){return $true}
    if(-not(Test-CapWorkerExit $Owned) -or -not $Owned.CleanupServices.Count){return $false}
    try {
        $members=@($Owned.Capture.Members())
        foreach($id in $members){
            $identity=@($Owned.CleanupServices|Where-Object Pid -eq $id)
            if($identity.Count -ne 1){throw 'Cleanup descendant not transferred'}
            $p=Get-Process -Id $id -ErrorAction Stop
            if($p.StartTime.ToUniversalTime().ToString('o') -cne $identity[0].StartUtc -or $p.Path -ine $identity[0].Binary){throw 'Cleanup transferred identity changed'}
        }
        return $true
    }catch{$message='CAP cleanup ownership observation failed: '+$_.Exception.Message;if(-not $Owned.CleanupFailure){$Owned.CleanupFailure=$message};Set-CapFailure $Owned $message;return $false}
}
function Wait-CapCleanup($Registry,$Clock,[switch]$Final,[switch]$ForStop) {
    if($Final -and $ForStop){throw 'Final closure cannot use cleanup-only service exception'}
    while((Get-CapRemaining $Clock) -gt 0){
        if($ForStop){
            # 이른 정리 진입 뒤 늦게 끝난 worker/stream을 같은 cleanup clock 안에서 재관측
            foreach($owner in $Registry){
                if((Get-CapRemaining $Clock) -le 0){break}
                if($owner.Started -and $owner.ServiceRole -ceq 'mysql3307' -and -not $owner.Services.Count -and -not $owner.CleanupServices.Count -and -not $owner.CleanupFailure){
                    Receive-CapService $owner -CleanupOnly -Clock $Clock
                }
            }
        }
        if(@($Registry|Where-Object {if($ForStop){-not(Test-CapDbUsersClosed $_)}else{-not(Test-CapJoined $_ -Final:$Final)}}).Count -eq 0){break}
        Start-Sleep -Milliseconds ([int][Math]::Min(50,(Get-CapRemaining $Clock)))
    }
    @($Registry|ForEach-Object {Test-CapStreams $_;@{Pid=$_.Pid;StartUtc=$_.StartUtc;Request=$_.RequestPath;Joined=(Test-CapJoined $_ -Final:$Final);FirstFailure=$_.Failure;Services=$_.Services;DbUsersClosed=(Test-CapDbUsersClosed $_);CleanupServices=$_.CleanupServices;CleanupFailure=$_.CleanupFailure;ActualExit=if($_.Started -and $_.Capture.Process.HasExited){$_.Capture.Process.ExitCode}else{$null}}})
}
# controller와 검증이 공유하는 Stop 진입. 준비 성공과 구분하고 같은 cleanup/실행 만료 재확인
function Invoke-CapMysqlStopWhenReady($Registry,$CleanupClock,$StopClock,[string]$StartReceipt,[scriptblock]$StopAction) {
    $owners=@(Wait-CapCleanup $Registry $CleanupClock -ForStop)
    if(@($owners|Where-Object {-not $_.DbUsersClosed}).Count -or -not(Test-Path -LiteralPath $StartReceipt)){return $false}
    if((Get-CapRemaining $CleanupClock) -le 0 -or (Get-CapRemaining $StopClock) -le 0){return $false}
    & $StopAction $StopClock | Out-Null
    return $true
}
function Invoke-CapNative([string]$File,[string[]]$Arguments,[string]$Evidence) {
    if(-not ('Phase18Cap.JobCapture' -as [type])){. "$PSScriptRoot/phase17-c-process.ps1"}
    $null=Get-CapOperationMillis
    $argv=@($Arguments|ForEach-Object {[Phase18Cap.JobCapture]::Quote($_)}) -join ' '
    if([IO.Path]::GetExtension($File) -in @('.bat','.cmd')) {
        if(@($Arguments|Where-Object {$_ -match '[%!\r\n]'}).Count){throw 'Batch environment expansion argument forbidden'}
        $argv='/d /v:off /s /c "'+[Phase18Cap.JobCapture]::Quote([IO.Path]::GetFullPath($File))+' '+$argv+'"';$File="$env:WINDIR\System32\cmd.exe"
    }
    $capture=[Phase17C.ProcessCapture]::new()
    $capture.Start($File,$argv,(Get-Location).Path,"$Evidence.native.stdout","$Evidence.native.stderr",1048576)
    while(-not($capture.Process.HasExited -and $capture.OutTask.IsCompleted -and $capture.ErrTask.IsCompleted)){
        $null=Get-CapOperationMillis;Start-Sleep -Milliseconds ([Math]::Min(50,(Get-CapOperationMillis)))
    }
    foreach($stream in @($capture.Out,$capture.Err)){if($stream.Failure -or $stream.Overflow){throw 'CAP native log failure'}}
    foreach($task in @($capture.OutTask,$capture.ErrTask)){if($task.IsFaulted -or $task.IsCanceled){throw 'CAP native stream task failure'}}
    [Console]::Out.Write([IO.File]::ReadAllText("$Evidence.native.stdout"));[Console]::Error.Write([IO.File]::ReadAllText("$Evidence.native.stderr"))
    return $capture.Process.ExitCode
}
