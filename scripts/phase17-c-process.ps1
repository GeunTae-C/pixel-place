# C 전용 실행 경계. 현재 exit와 유한 출력 수집의 공동 성공만 반환
if (-not ('Phase17C.ProcessCapture' -as [type])) {
    $captureSource=@'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
namespace Phase17C {
 public sealed class StreamCapture {
  public string Failure; public long Bytes; public bool Overflow;
  public void Pump(StreamReader input,string path,long limit) {
   StreamWriter output=null;
   try { output=new StreamWriter(new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.Read),new UTF8Encoding(false)); }
   catch(Exception e) { Failure=e.GetType().Name; }
   var line=new StringBuilder(); var buffer=new char[2048]; bool clipped=false;
   try { int n; while((n=input.Read(buffer,0,buffer.Length))>0) {
    for(int i=0;i<n;i++) { char c=buffer[i];
     if(c=='\n') { Emit(output,line.ToString(),limit,clipped); line.Clear(); clipped=false; }
     else if(line.Length<8192) line.Append(c); else { clipped=true; Overflow=true; }
    }
   } if(line.Length>0 || clipped) Emit(output,line.ToString(),limit,clipped); }
   catch(Exception e) { if(Failure==null) Failure=e.GetType().Name; }
   finally { if(output!=null) try { output.Dispose(); } catch(Exception e) { if(Failure==null) Failure=e.GetType().Name; } }
  }
  void Emit(StreamWriter output,string line,long limit,bool clipped) {
   string lower=line.ToLowerInvariant();
   if(clipped || lower.Contains("password") || lower.Contains("authorization") || lower.Contains("cookie") || lower.Contains("jdbc:") || lower.Contains("\"eventseq\"") || lower.Contains("\"createdat\"")) line="[sensitive or payload diagnostic omitted]";
   int length=Encoding.UTF8.GetByteCount(line)+1;
   if(Bytes+length>limit) { Overflow=true; return; } Bytes+=length;
   if(output!=null && Failure==null) try { output.WriteLine(line); output.Flush(); } catch(Exception e) { Failure=e.GetType().Name; }
  }
 }
 public sealed class ProcessCapture {
  public Process Process; public StreamCapture Out=new StreamCapture(),Err=new StreamCapture(); public Task OutTask,ErrTask;
  public void Start(string executable,string arguments,string cwd,string stdout,string stderr,long limit) {
   var info=new ProcessStartInfo(executable,arguments); info.UseShellExecute=false; info.CreateNoWindow=true;
   info.RedirectStandardOutput=true; info.RedirectStandardError=true; info.WorkingDirectory=cwd;
   Process=new Process(); Process.StartInfo=info;
   if(!Process.Start()) throw new InvalidOperationException("Process did not start");
   OutTask=Task.Run(()=>Out.Pump(Process.StandardOutput,stdout,limit));
   ErrTask=Task.Run(()=>Err.Pump(Process.StandardError,stderr,limit));
  }
  public void Finish() { Process.WaitForExit(); Task.WaitAll(OutTask,ErrTask); }
 }
}
'@
    Add-Type -TypeDefinition ($captureSource + [IO.File]::ReadAllText("$PSScriptRoot/phase18-cap-job.cs"))
}
function Write-Phase17CJson {
    param([string]$Path, $Value)
    $text=$Value | ConvertTo-Json -Depth 18
    [IO.File]::WriteAllText($Path,$text,[Text.UTF8Encoding]::new($false))
}
function Resolve-Phase17CPlannedPath {
    param([string]$Value,[string]$Planned)
    $normalized=foreach($candidate in @($Value,$Planned)) {
        if([string]::IsNullOrWhiteSpace($candidate) -or $candidate -notmatch '\A[A-Za-z]:[\\/]'){throw 'Fully qualified local drive path required'}
        $full=[IO.Path]::GetFullPath($candidate)
        if($full.Length -gt 3){$full=$full.TrimEnd([char[]]'\/')}
        $full
    }
    if(-not [string]::Equals($normalized[0],$normalized[1],[StringComparison]::OrdinalIgnoreCase)){throw 'Current path differs from recorded C plan'}
    $normalized[0]
}
function Assert-Phase17CPath {
    param([string]$Path,[string]$Root)
    $actual=Resolve-Phase17CPlannedPath $Path $Path
    $owner=Resolve-Phase17CPlannedPath $Root $Root
    if($actual -ne $owner -and -not $actual.StartsWith($owner+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Path outside exact owner root'}
    $node=$actual
    while($node) {
        if(Test-Path -LiteralPath $node){if((Get-Item -LiteralPath $node -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse ancestor forbidden'}}
        $node=[IO.Path]::GetDirectoryName($node)
    }
}
function ConvertTo-Phase17CCmdArguments {
    param([string[]]$Arguments)
    $quoted=@(foreach($arg in $Arguments){
        # cmd 환경 치환·메타 문자·quote 삽입을 호출 전에 차단
        if($arg -match '["\r\n&|<>^%!]'){throw 'Unsafe command argument'}
        '"'+$arg+'"'
    })
    '/d /s /c "'+($quoted -join ' ')+'"'
}
function Invoke-Phase17CProcess {
    param([string]$Executable,[string]$Arguments,[string]$WorkingDirectory,[string]$Evidence,[int]$Seconds,[long]$LogLimit=16777216,[switch]$LogFailureFixture)
    if($Seconds -le 0 -or $LogLimit -le 0){throw 'Positive process observation budget required'}
    if(Test-Path -LiteralPath $Evidence){throw 'New process evidence required'}
    New-Item -ItemType Directory -Path $Evidence -ErrorAction Stop | Out-Null
    $stdout=Join-Path $Evidence 'stdout.log'; $stderr=Join-Path $Evidence 'stderr.log'
    if($LogFailureFixture){New-Item -ItemType Directory -Path $stdout | Out-Null}
    $state=[ordered]@{Executable=$Executable;Arguments=$Arguments;WorkingDirectory=$WorkingDirectory;Started=$false;Pid=$null;StartUtc=$null;EndUtc=$null;Exit=$null;TimedOut=$false;DeadlineSeconds=$Seconds;Success=$false;Failures=@();Stdout=$stdout;Stderr=$stderr;Resources='not-started'}
    $capture=[Phase17C.ProcessCapture]::new()
    try {
        $item=Get-Item -LiteralPath $Executable -ErrorAction Stop
        if($item.PSIsContainer){throw 'Executable must be a file'}
        $capture.Start($item.FullName,$Arguments,$WorkingDirectory,$stdout,$stderr,$LogLimit)
        $state.Started=$true; $state.Pid=$capture.Process.Id; $state.StartUtc=$capture.Process.StartTime.ToUniversalTime().ToString('o'); $state.Resources='running'
        Write-Phase17CJson "$Evidence\process.json" $state
        if(-not $capture.Process.WaitForExit($Seconds*1000)) {
            $state.TimedOut=$true; $state.Failures+= 'observation-deadline'; $state.Resources='running-after-deadline'
            Write-Phase17CJson "$Evidence\timeout.json" $state
        }
        # 기한 초과는 실패로 고정. 프로세스를 kill하거나 정상 종료를 가정하지 않고 소유권 유지
        $capture.Finish(); $state.Exit=$capture.Process.ExitCode; $state.EndUtc=$capture.Process.ExitTime.ToUniversalTime().ToString('o'); $state.Resources='exited-streams-joined'
        if($state.Exit -ne 0){$state.Failures+=('process-exit-'+$state.Exit)}
        foreach($stream in @($capture.Out,$capture.Err)) {
            if($stream.Failure){$state.Failures+=('log-save-'+$stream.Failure)}
            if($stream.Overflow){$state.Failures+='log-budget-exceeded'}
        }
        $state.Success=$state.Failures.Count -eq 0
    } catch {
        $state.Failures+=('runner-'+$_.Exception.GetType().Name)
        $state.Success=$false
        if($state.Started){
            # 증거 저장 실패도 최초 실패를 보존하며 이미 시작한 자식은 정상 소진까지 계속 소유
            try {$capture.Finish();$state.Exit=$capture.Process.ExitCode;$state.EndUtc=$capture.Process.ExitTime.ToUniversalTime().ToString('o');$state.Resources='exited-streams-joined'}
            catch {$state.Failures+=('cleanup-'+$_.Exception.GetType().Name);$state.Resources='unresolved'}
        }
    }
    # 최초 실행/증거 실패가 있어도 뒤 stream 저장 실패를 함께 보존
    if($state.Started -and $state.Resources -eq 'exited-streams-joined') {
        foreach($stream in @($capture.Out,$capture.Err)) {
            if($stream.Failure -and ('log-save-'+$stream.Failure) -notin $state.Failures){$state.Failures+=('log-save-'+$stream.Failure)}
            if($stream.Overflow -and 'log-budget-exceeded' -notin $state.Failures){$state.Failures+='log-budget-exceeded'}
        }
        if($state.Failures.Count){$state.Success=$false}
    }
    try {Write-Phase17CJson "$Evidence\exit.json" $state}
    catch {$state.Success=$false;$state.Failures+=('evidence-save-'+$_.Exception.GetType().Name)}
    if($capture.Process -and $state.Resources -eq 'exited-streams-joined'){$capture.Process.Dispose()}
    if(-not $state.Success){throw ('C process failed: '+($state.Failures -join ',')+'; evidence='+$Evidence)}
    [pscustomobject]$state
}
