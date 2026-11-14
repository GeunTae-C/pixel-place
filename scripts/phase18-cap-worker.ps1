param([Parameter(Mandatory=$true)][string]$RequestPath)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5){throw 'CAP worker requires Windows PowerShell 5.1'}
Import-Module "$PSHOME/Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1" -ErrorAction Stop
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/pixel-place-work.ps1"
. "$PSScriptRoot/phase17-c-process.ps1"
[Phase18Cap.JobCapture]::ProtectStandardHandles()
$request=Get-Content -Encoding UTF8 -LiteralPath $RequestPath -Raw|ConvertFrom-Json
Set-Location -LiteralPath $request.Repo
$env:PIXEL_PLACE_CAP_EXPIRES=[string]$request.ExpiresEpochMillis
$env:PIXEL_PLACE_CAP_ORIGINAL_EXPIRES=[string]$request.OriginalExpiresEpochMillis
$env:PIXEL_PLACE_CAP_STOP="$RequestPath.stop.json"
$env:PIXEL_PLACE_CAP_REQUEST=$RequestPath
$env:PIXEL_PLACE_CAP_SERVICE_ROLE=[string]$request.ServiceRole
if([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $request.ExpiresEpochMillis){throw 'CAP worker deadline exhausted before work'}
try{
    switch($request.Kind){
        'native' {
            $nativeExit=Invoke-CapNative $request.File @($request.Arguments) $RequestPath
            Save-CapJson $request.Output @{Exit=$nativeExit}
            exit $nativeExit
        }
        'script' {
            $parameters=@{};foreach($property in $request.Parameters.PSObject.Properties){$parameters[$property.Name]=$property.Value}
            $null=Get-CapOperationMillis
            & $request.File @parameters
            $null=Get-CapOperationMillis
            Save-CapJson $request.Output @{Exit=0}
        }
        'devices' { Save-CapJson $request.Output @{ObservedUtc=[DateTime]::UtcNow.ToString('o');Disks=@(Get-Partition -DriveLetter C,F|Get-Disk|Select-Object Number,FriendlyName,BusType);Scope='F WAL, MySQL and Redis share physical device; host counters are not WAL-only'} }
        'redis' {
            . "$PSScriptRoot/mysql-test-redis.ps1"
            $redis=$null;$checks=@()
            foreach($index in 2..15){
                if([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $request.ExpiresEpochMillis){throw 'CAP Redis selection deadline'}
                try{$redis=Read-TestRedisIdentity $index;$checks+=@{Index=$index;Eligible=$true};break}catch{$checks+=@{Index=$index;Eligible=$false;FailureType=$_.Exception.GetType().Name}}
            }
            Save-CapJson $request.Output @{Checks=$checks;Identity=$redis}
            if(-not $redis){throw 'No currently empty owned Redis index in 2..15'}
        }
        'closure-probe' {
            $listeners=@(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue)
            $java=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -OperationTimeoutSec 5|Where-Object {$_.CommandLine -and $_.CommandLine.Contains($request.SessionId)}|Select-Object ProcessId,ParentProcessId,CreationDate)
            Save-CapJson $request.Output @{Remaining3307=$listeners.Count;OwnedJava=$java;RemainingRunLock=(Test-Path "$(Get-PixelPlaceEvidencePath $request.SessionId)/a2.lock")}
        }
        default {throw 'Unknown CAP worker kind'}
    }
    exit 0
}catch{Write-Error $_ -ErrorAction Continue;exit 1}
