# No MySQL, Redis, HTTP, WS or application startup. Windows PowerShell 5.1 only.
param([Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5){throw 'Use Windows PowerShell 5.1'}
. "$PSScriptRoot/pixel-place-work.ps1"
Assert-PixelPlaceExecutionEnvironment
if($env:TEMP -notmatch '^F:[\\/]pixel-place-work[\\/]sessions[\\/]' -or -not $env:PIXEL_PLACE_WORK_SESSION){throw 'Set-PixelPlaceWorkEnvironment must select a fresh F temp session first'}
Assert-PixelPlaceWorkPath $Output
if(Test-Path -LiteralPath $Output){throw 'Fresh fixture output required'}
New-Item -ItemType Directory -Path $Output|Out-Null
foreach($name in @('phase18-cap-process.ps1','phase18-cap-worker.ps1','run-phase18-cap-smoke.ps1','observe-phase18-host.ps1')){
    $tokens=$null;$errors=$null
    $null=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/$name",[ref]$tokens,[ref]$errors)
    if($errors.Count){throw "PowerShell parse failed: $name"}
}
. "$PSScriptRoot/phase18-cap-process.ps1"
$registry=[Collections.Generic.List[object]]::new();$results=@()
$ps=(Get-Process -Id $PID).Path
foreach($case in @(
    @{Name='stderr-exit0';Code="[Console]::Error.WriteLine('fixture warning'); exit 0";Exit=0},
    @{Name='stderr-exit7';Code="[Console]::Error.WriteLine('fixture warning'); exit 7";Exit=7}
)){
    $clock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+30000)
    $request=@{Kind='native';Repo=(Split-Path $PSScriptRoot -Parent);ExpiresEpochMillis=$clock.Expires;OriginalExpiresEpochMillis=$clock.Expires;File=$ps;Arguments=@('-NoProfile','-NonInteractive','-Command',$case.Code);Output="$Output/$($case.Name).exit.json"}
    $owned=Start-CapWorker $request "$Output/$($case.Name).request.json" $clock $registry
    $failed=$false;try{Wait-CapWorker $owned $clock|Out-Null}catch{$failed=$true}
    if($failed -ne ($case.Exit -ne 0) -or $owned.Result.Exit -ne $case.Exit -or -not $owned.Result.Joined){throw "Exit fixture failed: $($case.Name)"}
    if(-not([IO.File]::ReadAllText("$($owned.RequestPath).stderr.log").Contains('fixture warning'))){throw 'stderr evidence missing'}
    $results+=@{Name=$case.Name;Result=$owned.Result}
}
# Named script parameters containing spaces and literal metacharacters must remain data.
$fixture="$Output/parameter fixture.ps1";$parameterResult="$Output/parameter-result.json"
[IO.File]::WriteAllText($fixture,'param([string]$Output,[string]$Value); [IO.File]::WriteAllText($Output,$Value)')
$literal='space '' quote $dollar & semicolon;'+[char]0xD55C+[char]0xAE00
$clock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+30000)
$request=@{Kind='script';Repo=(Split-Path $PSScriptRoot -Parent);ExpiresEpochMillis=$clock.Expires;OriginalExpiresEpochMillis=$clock.Expires;File=$fixture;Parameters=@{Output=$parameterResult;Value=$literal};Output="$Output/parameters.exit.json"}
$owned=Start-CapWorker $request "$Output/parameters.request.json" $clock $registry
$resetRejected=$false
try{Wait-CapWorker $owned (New-CapClock $clock.Expires)|Out-Null}catch{$resetRejected=$true}
if(-not $resetRejected){throw 'A new monotonic clock must not replace the owning clock'}
Wait-CapWorker $owned $clock|Out-Null
if([IO.File]::ReadAllText($parameterResult) -cne $literal){throw 'Script parameter corruption'}
$results+=@{Name='script-parameters';Result=$owned.Result}

# Finite delayed child: observe the expiry, retain ownership, then observe its natural exit.
$ready="$Output/late-ready.txt"
$code="[IO.File]::WriteAllText('"+$ready.Replace("'","''")+"','ready'); Start-Sleep -Seconds 7; [IO.File]::WriteAllText('"+($Output+'/late-finished.txt').Replace("'","''")+"','native exit 0'); exit 0"
$clock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+5000)
$request=@{Kind='native';Repo=(Split-Path $PSScriptRoot -Parent);ExpiresEpochMillis=$clock.Expires;OriginalExpiresEpochMillis=$clock.Expires;File=$ps;Arguments=@('-NoProfile','-NonInteractive','-Command',$code);Output="$Output/late.exit.json"}
$owned=Start-CapWorker $request "$Output/late.request.json" $clock $registry
$failed=$false;try{Wait-CapWorker $owned $clock|Out-Null;[IO.File]::WriteAllText("$Output/next.marker",'wrong')}catch{$failed=$true}
if(-not $failed -or -not(Test-Path $ready) -or (Test-Path "$Output/next.marker") -or $owned.Result.Joined){throw 'Late child must fail while owned'}
$first=$owned.Failure
$short=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+100)
$remaining=@(Wait-CapCleanup $registry $short)
if(@($remaining|Where-Object {-not $_.Joined}).Count -ne 1){throw 'Unresolved cleanup fixture did not retain ownership'}
$cleanup=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+15000)
$remaining=@(Wait-CapCleanup $registry $cleanup)
if(@($remaining|Where-Object {-not $_.Joined}).Count -ne 0 -or $owned.Failure -cne $first -or $owned.Capture.Process.ExitCode -ne 1 -or -not(Test-Path "$Output/late-finished.txt")){throw 'Late native completion must preserve worker timeout and close naturally'}
$results+=@{Name='late-natural-exit';Result=$owned.Result;FinalOwned=$remaining;FirstFailure=$first}

$count=$registry.Count;$failed=$false
try{New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-1)|Out-Null}catch{$failed=$true}
if(-not $failed -or $registry.Count -ne $count){throw 'Expired pre-spawn must fail without creating a process'}
Save-CapJson "$Output/results.json" @{Complete=$true;Cases=$results;ExpiredPreSpawn=$true;NoForceKill=$true;ServiceRuns=0}
Write-Output 'CAP process fixtures passed; no services started'
