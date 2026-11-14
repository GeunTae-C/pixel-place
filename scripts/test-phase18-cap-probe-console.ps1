# 실제 유한 Java exit/stream + 관측된 MySQL console topology의 identity 대역. DB 시작 없음
param([Parameter(Mandatory=$true)][string]$EvidenceRoot,[Parameter(Mandatory=$true)][string]$TempRoot)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/phase17-c-process.ps1"
$repo=Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory $EvidenceRoot -ErrorAction Stop|Out-Null
New-Item -ItemType Directory $TempRoot -ErrorAction Stop|Out-Null
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/mysql-test-instance.ps1",[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'MySQL helper parse failure'}
foreach($definition in $ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -in @('Invoke-DatabaseProbeProcess','Save-NewJson')},$false)){Invoke-Expression $definition.Extent.Text}
$fixture="$TempRoot/ProbeExitFixture.java"
[IO.File]::WriteAllText($fixture,'class ProbeExitFixture { public static void main(String[] a) { System.out.println("fixture-out"); System.err.println("fixture-err"); System.exit(Integer.parseInt(a[0])); } }')
$legacy=@();$actual=@()
foreach($code in @(0,7)){
 $argv=@("-Djava.io.tmpdir=$TempRoot",'--source','21',$fixture,[string]$code)
 $prefix="$EvidenceRoot/legacy-$code"
 $quoted=@($argv|ForEach-Object {[Phase18Cap.JobCapture]::Quote($_)}) -join ' '
 $probe=Start-Process -FilePath 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput "$prefix.stdout" -RedirectStandardError "$prefix.stderr"
 while(-not $probe.HasExited){$null=Get-CapOperationMillis;Start-Sleep -Milliseconds 50}
 $probe.Refresh()
 $legacy+=@{Expected=$code;Observed=$probe.ExitCode;HasExited=$probe.HasExited}
 $probe.Dispose()
 $prefix="$EvidenceRoot/native-$code"
 $returned=Invoke-DatabaseProbeProcess $argv $prefix
 $receipt=Get-Content "$prefix.exit.json" -Raw|ConvertFrom-Json
 if($returned -ne $code -or $receipt.ActualExit -ne $code -or -not $receipt.StreamsCompleted){throw 'Actual exit boundary incorrect'}
 if(([IO.File]::ReadAllText("$prefix.stdout")).Trim() -cne 'fixture-out' -or ([IO.File]::ReadAllText("$prefix.stderr")).Trim() -cne 'fixture-err'){throw 'Incomplete native streams'}
 $actual+=@{Expected=$code;Actual=$returned;StreamsVerified=$true}
}
# 같은 파일명만으로 인계하지 않는지 확인. OS 이름/부모/path/start를 각각 변조
$consolePath=[IO.Path]::GetFullPath("$env:WINDIR/System32/conhost.exe")
$confirmed=@([pscustomobject]@{Pid=9224;StartUtc='2026-10-11T10:13:09.4241809Z';Binary='C:/Program Files/MySQL/MySQL Server 8.4/bin/mysqld.exe'})
function Get-CimInstance($ClassName,$Filter,$OperationTimeoutSec){$simulation.Cim}
function Get-Process($Id,$ErrorAction){$simulation.Process}
$cases=@()
foreach($case in @('valid-submillisecond','wrong-name','wrong-path','wrong-parent','wrong-process-path','start-mismatch','before-parent','absent')){
 $stamp=[DateTime]::Parse('2026-10-11T10:13:09.4295000Z').ToUniversalTime()
 $simulation=@{Cim=[pscustomobject]@{Name='conhost.exe';ParentProcessId=9224;ExecutablePath=$consolePath;CreationDate=$stamp};Process=[pscustomobject]@{Path=$consolePath;StartTime=$stamp.AddTicks(9)}}
 switch($case){
  'wrong-name' {$simulation.Cim.Name='java.exe'}
  'wrong-path' {$simulation.Cim.ExecutablePath='C:/other/conhost.exe'}
  'wrong-parent' {$simulation.Cim.ParentProcessId=6112}
  'wrong-process-path' {$simulation.Process.Path='C:/other/conhost.exe'}
  'start-mismatch' {$simulation.Process.StartTime=$stamp.AddSeconds(1)}
  'before-parent' {$simulation.Cim.CreationDate=$stamp.AddSeconds(-1);$simulation.Process.StartTime=$stamp.AddSeconds(-1)}
  'absent' {$simulation.Cim=$null}
 }
 $rejected=$false;$message=$null;$found=@()
 try{$found=@(Get-CapMysqlConsoleServices $confirmed @(9224,30200))}catch{$rejected=$true;$message=$_.Exception.Message}
 if($case -eq 'valid-submillisecond'){
  if($rejected -or $found.Count -ne 1 -or $found[0].Pid -ne 30200 -or $found[0].Purpose -cne 'owned-mysql-console' -or $found[0].BinarySha256 -ine (Get-FileHash $consolePath).Hash){throw 'Confirmed console rejected or evidence incorrect'}
 }elseif(-not $rejected -or $message -notin @('Unconfirmed MySQL descendant','MySQL console identity changed')){throw "Identity violation not rejected: $case"}
 $cases+=@{Case=$case;Passed=$true;Rejected=$rejected;Failure=$message}
}
Save-CapJson "$EvidenceRoot/results.json" @{NativeCases=$actual;LegacyObservation=$legacy;ConsoleCases=$cases;Passed=($actual.Count+$cases.Count);DatabaseStarts=0;ConsoleObservations='Replay with individual identity mutations; no live MySQL integration'}
Write-Output "CAP_PROBE_CONSOLE_PASS=$($actual.Count+$cases.Count)"
