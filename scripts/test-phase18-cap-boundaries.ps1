# 서비스·네트워크 없이 실제 Job/argv/저장/인계 경계 검증. 모든 대역은 최대 15초 자연 종료
param([Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/phase18-cap-process.ps1"
if(Test-Path $Output){throw 'Fresh output required'}
New-Item -ItemType Directory -Path $Output|Out-Null
$repo=Split-Path $PSScriptRoot -Parent
$python='C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
$servicePython=& $python -c 'import sys;print(sys._base_executable)'
$registry=[Collections.Generic.List[object]]::new();$results=@()
function Start-Fixture($Name,$Extra,[int]$Millis=30000) {
    $clock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+$Millis)
    $r=@{Kind='native';Repo=$repo;ExpiresEpochMillis=$clock.Expires;OriginalExpiresEpochMillis=$clock.Expires;Output="$Output/$Name.exit.json"}
    foreach($key in $Extra.Keys){$r[$key]=$Extra[$key]}
    Start-CapWorker $r "$Output/$Name.request.json" $clock $registry
}
function Assert-Failure($Owned,[string]$Pattern) {
    $failed=$false;try{Wait-CapWorker $Owned $Owned.Clock|Out-Null}catch{$failed=$true}
    if(-not $failed -or $Owned.Failure -notmatch $Pattern -or $Owned.Result.NextTaskAllowed){throw "Expected failure not enforced: $Pattern; $($Owned.Failure)"}
}
try {
    $argvScript="$Output/argv.py"
    [IO.File]::WriteAllText($argvScript,"import sys,json,pathlib`npathlib.Path(sys.argv[1]).write_text(json.dumps(sys.argv[2:],ensure_ascii=False),encoding='utf-8')`n")
    $literal=@('has space',('한글'),'','single''quote','literal "double"','tail\','space '' quote "double" $dollar & semicolon;한글')
    $batch="$Output/relay.cmd"
    [IO.File]::WriteAllText($batch,'@echo off'+"`r`n"+'"'+$python+'" %*'+"`r`n"+'exit /b %errorlevel%'+"`r`n",[Text.Encoding]::ASCII)
    foreach($kind in @('direct','batch')){
        $file=if($kind -eq 'direct'){$python}else{$batch}
        $path="$Output/argv-$kind.json"
        $o=Start-Fixture "argv-$kind" @{File=$file;Arguments=@($argvScript,$path)+$literal}
        Wait-CapWorker $o $o.Clock|Out-Null
        $actual=Get-Content $path -Raw -Encoding UTF8|ConvertFrom-Json
        if($actual.Count -ne $literal.Count){throw 'argv count differs'}
        for($i=0;$i -lt $literal.Count;$i++){if($actual[$i] -cne $literal[$i]){throw "argv corruption $kind index=$i"}}
        $results+=@{Name="argv-$kind";Passed=$true;Actual=$actual}
    }
    # 수정하지 않은 실제 gradlew.bat -> JVM argv 경계. Java 실행만 유한 기록 대역으로 대체
    $spyHome=Join-Path $env:TEMP 'argv-jdk';New-Item -ItemType Directory -Path "$spyHome/bin"|Out-Null
    $spySource='using System;using System.IO;using System.Text;public class ArgvProbe {public static int Main(string[] a){string[] b=new string[a.Length];for(int i=0;i<a.Length;i++)b[i]=Convert.ToBase64String(Encoding.UTF8.GetBytes(a[i]));File.WriteAllLines(Environment.GetEnvironmentVariable("CAP_ARGV_RECORD"),b,new UTF8Encoding(false));return 0;}}'
    Add-Type -TypeDefinition $spySource -OutputAssembly "$spyHome/bin/java.exe" -OutputType ConsoleApplication
    $savedJava=$env:JAVA_HOME;$env:JAVA_HOME=$spyHome;$env:CAP_ARGV_RECORD="$Output/gradlew-argv.base64"
    try{
        $o=Start-Fixture 'actual-gradlew-argv' @{File="$repo/gradlew.bat";Arguments=$literal}
        Wait-CapWorker $o $o.Clock|Out-Null
        $actual=@([IO.File]::ReadAllLines($env:CAP_ARGV_RECORD)|ForEach-Object {[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_))})
        $tail=@($actual|Select-Object -Last $literal.Count)
        if($tail.Count -ne $literal.Count){throw 'Gradlew argv count differs'}
        for($i=0;$i -lt $literal.Count;$i++){if($tail[$i] -cne $literal[$i]){throw "Actual gradlew argv corruption index=$i"}}
        $results+=@{Name='actual-gradlew-argv';Passed=$true;GradlewSha256=(Get-FileHash "$repo/gradlew.bat").Hash;JvmArguments=$actual}
    }finally{$env:JAVA_HOME=$savedJava;$env:CAP_ARGV_RECORD=$null}
    $finite="$Output/finite.py"
    [IO.File]::WriteAllText($finite,@'
import os,pathlib,sys,time
stop,ready,done=map(pathlib.Path,sys.argv[1:4])
ready.write_text(str(os.getpid()))
until=time.monotonic()+15
while not stop.exists() and time.monotonic()<until:time.sleep(.02)
done.write_text('normal finite exit')
'@)
    $starter="$Output/start-fixture.ps1"
    [IO.File]::WriteAllText($starter,@'
param($Python,$Finite,$Stop,$Ready,$Done,[bool]$Offer)
. "$PSScriptRoot/../../candidate/scripts/phase18-cap-process.ps1"
$args='"'+$Finite+'" "'+$Stop+'" "'+$Ready+'" "'+$Done+'"'
$p=[Phase18Cap.JobCapture]::StartService($Python,$args,(Get-Location).Path,"$Ready.out","$Ready.err")
while(-not(Test-Path $Ready)){$null=Get-CapOperationMillis;Start-Sleep -Milliseconds 20}
if($Offer){
 $p.Refresh()
 Save-CapJson "$env:PIXEL_PLACE_CAP_REQUEST.service.json" @{Role=$env:PIXEL_PLACE_CAP_SERVICE_ROLE;Ready=$true;Request=$env:PIXEL_PLACE_CAP_REQUEST;ReadinessReceipt=$Ready;ReadinessHash=(Get-FileHash $Ready).Hash;Processes=@(@{Pid=$p.Id;StartUtc=$p.StartTime.ToUniversalTime().ToString('o');Binary=$p.Path;BinarySha256=(Get-FileHash $p.Path).Hash})}
}
'@,[Text.UTF8Encoding]::new($true))
    # fixture 위치와 무관하게 후보 helper를 실제 경로로 연결
    $body=[IO.File]::ReadAllText($starter).Replace('$PSScriptRoot/../../candidate/scripts/phase18-cap-process.ps1',"$PSScriptRoot/phase18-cap-process.ps1")
    [IO.File]::WriteAllText($starter,$body,[Text.UTF8Encoding]::new($true))
    foreach($case in @('service-normal','unregistered-descendant','handoff-save-failure','handoff-missing')){
        $stop="$Output/$case.stop";$ready="$Output/$case.ready";$done="$Output/$case.done"
        $extra=@{Kind='script';File=$starter;Parameters=@{Python=$servicePython;Finite=$finite;Stop=$stop;Ready=$ready;Done=$done;Offer=($case -ne 'handoff-missing' -and $case -ne 'unregistered-descendant')}}
        if($case -ne 'unregistered-descendant'){$extra.ServiceRole='fixture-service'}
        if($case -eq 'handoff-save-failure'){New-Item -ItemType Directory "$Output/$case.request.json.handoff.json"|Out-Null}
        $o=Start-Fixture $case $extra 5000
        if($case -eq 'service-normal'){
            Wait-CapWorker $o $o.Clock|Out-Null
            if(-not $o.Result.NextTaskAllowed -or (Test-CapJoined $o -Final) -or @($o.Services).Count -ne 1){throw 'Normal service handoff did not distinguish lifetime'}
            $next=Start-Fixture 'normal-service-next' @{File=$python;Arguments=@('-c','import sys;sys.exit(0)')}
            Wait-CapWorker $next $next.Clock|Out-Null
            if(Test-Path $done){throw 'Service was not alive during next stage'}
        }else{
            Assert-Failure $o 'deadline|handoff'
            if(Test-CapJoined $o){throw 'Unconfirmed descendant must block DB stop'}
        }
        $first=$o.Failure
        [IO.File]::WriteAllText($stop,'owned normal shutdown request')
        $closed=@(Wait-CapCleanup $registry (New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+10000)) -Final)
        if(@($closed|Where-Object {-not $_.Joined}).Count -or -not(Test-Path $done) -or $o.Failure -cne $first){throw 'Service cleanup erased failure or missed actual exit'}
        $results+=@{Name=$case;Passed=$true;FirstFailure=$first;Final=$closed}
    }
    foreach($case in @('output-open-failure','result-save-failure','identity-save-failure')){
        $suffix=switch($case){'output-open-failure'{'stdout.log'} 'result-save-failure'{'result.json'} 'identity-save-failure'{'identity.json'}}
        New-Item -ItemType Directory "$Output/$case.request.json.$suffix"|Out-Null
        $o=$null;$failed=$false
        try{$o=Start-Fixture $case @{File=$python;Arguments=@('-c','import time;time.sleep(.3)')};Wait-CapWorker $o $o.Clock|Out-Null}catch{$failed=$true}
        $o=$registry[$registry.Count-1]
        if(-not $failed -or -not $o.Started -or -not $o.Failure -or ($o.Result -and $o.Result.NextTaskAllowed)){throw 'Storage failure lost ownership/failure'}
        $closed=@(Wait-CapCleanup $registry (New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+10000)) -Final)
        if(@($closed|Where-Object {-not $_.Joined}).Count){throw 'Storage failure process leak'}
        $results+=@{Name=$case;Passed=$true;FirstFailure=$o.Failure}
    }
    $o=Start-Fixture 'final-stream-failure' @{File=$python;Arguments=@('-c','pass')}
    $originalJoined=${function:Test-CapJoined}
    function Test-CapJoined($Owned,[switch]$Final){$joined=& $originalJoined $Owned -Final:$Final;if($joined -and $Owned.RequestPath -like '*final-stream-failure*'){$Owned.Capture.Out.Failure='injected final Dispose failure'};return $joined}
    try{Assert-Failure $o 'log collection'}finally{Set-Item Function:Test-CapJoined $originalJoined}
    $results+=@{Name='final-stream-failure';Passed=$true;Result=$o.Result;Injection='deterministic final-join boundary; no actual disk failure claimed'}
    $o=Start-Fixture 'final-task-fault' @{File=$python;Arguments=@('-c','pass')}
    $originalJoined=${function:Test-CapJoined}
    function Test-CapJoined($Owned,[switch]$Final){
        $joined=& $originalJoined $Owned -Final:$Final
        if($joined -and $Owned.RequestPath -like '*final-task-fault*'){$fault=[Threading.Tasks.TaskCompletionSource[object]]::new();$fault.SetException([IO.IOException]::new('injected final task fault'));$Owned.Capture.ErrTask=$fault.Task}
        return $joined
    }
    try{Assert-Failure $o 'stream task failed'}finally{Set-Item Function:Test-CapJoined $originalJoined}
    $results+=@{Name='final-task-fault';Passed=$true;Result=$o.Result;Injection='completed faulted pump task at final join'}
    Save-CapJson "$Output/results.json" @{Complete=$true;Cases=$results;ServiceRuns=0;ForceKills=0}
}finally{
    $final=@(Wait-CapCleanup $registry (New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+25000)) -Final)
    Save-CapJson "$Output/final-ownership.json" $final
    if(@($final|Where-Object {-not $_.Joined}).Count){throw 'Fixture ownership unresolved'}
}
Write-Output "CAP extended boundaries passed: $($results.Count) cases"
