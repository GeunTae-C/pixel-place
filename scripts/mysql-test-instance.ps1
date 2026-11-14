# 현재 F: DB 소유권과 새 실행의 PID/start/hash를 연결. 초기화·DDL·강제 종료 책임 없음
param(
    [Parameter(Mandatory=$true)][ValidateSet('Inspect','Start','Verify','Environment','Stop')][string]$Action,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9][a-z0-9-]{5,79}$')][string]$RunId,
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,39}$')][string]$Receipt,
    [ValidateRange(2,15)][int]$RedisDatabase=2,
    [ValidateRange(1,268435456000)][long]$MySqlGrowthBytes=2GB,
    [string]$Cap1PolicyPath,
    [string]$Cap1LedgerRoot,
    [string]$DiagnosticDescriptor,
    [ValidateRange(1,214748364800)][long]$EvidenceBytes=64MB
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'pixel-place-work.ps1')
# 큰 증거 상한은 CAP-1 Start의 동결 profile에만 허용. 파일 선할당 없음
if($EvidenceBytes -gt 20GB){
    if($Action -ne 'Start' -or -not $Cap1PolicyPath){throw 'Evidence above legacy limit requires CAP1 Start policy'}
    $cap1Evidence=Get-Content -LiteralPath (Resolve-PixelPlaceHistoricalPath $Cap1PolicyPath) -Raw -Encoding UTF8|ConvertFrom-Json
    if($cap1Evidence.stage -cne 'CAP-1' -or $cap1Evidence.role -cnotin @('cap-target','cap-diagnostic-ab') -or $cap1Evidence.cap1Profile.limits.evidenceBytes -ne $EvidenceBytes){throw 'CAP1 evidence policy mismatch'}
}
if($MySqlGrowthBytes -gt 5GB){
    if($Action -ne 'Start' -or -not $Cap1PolicyPath){throw 'Growth above legacy limit requires CAP1 Start policy'}
    $cap1Storage=Get-Content -LiteralPath (Resolve-PixelPlaceHistoricalPath $Cap1PolicyPath) -Raw -Encoding UTF8|ConvertFrom-Json
    if($cap1Storage.stage -cne 'CAP-1' -or $cap1Storage.role -cnotin @('cap-target','cap-diagnostic-ab') -or $cap1Storage.cap1Profile.storage.limits.mysqlBytes -ne $MySqlGrowthBytes){throw 'CAP1 MySQL growth policy mismatch'}
}
$repo=Split-Path $PSScriptRoot -Parent
$descriptor=if($DiagnosticDescriptor){$DiagnosticDescriptor}else{Join-Path $PSScriptRoot 'mysql-test-instance.json'}
$instance=Get-Content -Raw -LiteralPath (Resolve-PixelPlaceHistoricalPath $descriptor) | ConvertFrom-Json
$root=$instance.root; $config=$instance.config; $binary=$instance.binary
. (Join-Path $PSScriptRoot 'pixel-place-work.ps1')
$run=Get-PixelPlaceEvidencePath $RunId
$large="F:/pixel-place-work/sessions/$RunId"
. (Join-Path $PSScriptRoot 'pixel-place-work.ps1')
. (Join-Path $PSScriptRoot 'phase18-cap-process.ps1')
$null=Get-CapOperationMillis
$minimumFree=10GB
$physicalReservation=$null
if(-not $Receipt){$Receipt=$Action.ToLowerInvariant()}

function Assert-LocalPath([string]$Path) {
    if($Path -notmatch '^[A-Za-z]:[\\/]' -or $Path.Substring(2).Contains(':')){throw 'Local absolute path required'}
    $full=[IO.Path]::GetFullPath($Path)
    if($full -ne $Path.Replace('/','\').TrimEnd('\')){throw 'Normalized path required'}
    foreach($part in $full.Substring(3).Split('\')){
        if($part -match '[. ]$|^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)'){throw 'Windows path alias forbidden'}
    }
    for($p=$full;$p;$p=Split-Path $p -Parent){
        if(Test-Path -LiteralPath $p){if((Get-Item -Force -LiteralPath $p).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse path forbidden'}}
    }
}
function Save-NewJson([string]$Path,$Value) {
    if(Test-Path -LiteralPath $Path){throw 'Evidence already exists'}
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Value|ConvertTo-Json -Depth 12)+"`n");$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
}
function Get-Bytes([string]$Path) {
    if(-not(Test-Path -LiteralPath $Path)){return 0L}
    $items=@(Get-ChildItem -LiteralPath $Path -Recurse -Force)
    if(@($items|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count){throw 'Reparse descendant forbidden'}
    return [long](($items|Where-Object {-not $_.PSIsContainer}|Measure-Object Length -Sum).Sum)
}
function Get-Services {
    @(foreach($port in @(3306,6379)){
        $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        if($listeners.Count -ne 1){throw 'Original service listener missing or ambiguous'}
        $p=Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0].OwningProcess)"
        [pscustomobject]@{Port=$port;Pid=$p.ProcessId;StartUtc=$p.CreationDate.ToUniversalTime().ToString('o')}
    })
}
function Assert-Services($Before) {
    $after=@(Get-Services)
    if(Compare-Object @($Before) $after -Property Port,Pid,StartUtc){throw 'Original service identity changed'}
    $after
}
function Get-Budget([long]$Baseline) {
    Assert-PixelPlaceExecutionEnvironment
    $db=Get-Bytes $root; $out=Get-Bytes $run
    $volumes=@(foreach($drive in @('C:','F:')){
        $v=[IO.DriveInfo]::new($drive)
        if($v.DriveType -ne 'Fixed' -or $v.DriveFormat -ne 'NTFS' -or $v.AvailableFreeSpace -lt $minimumFree){throw 'Volume format/free-space gate failed'}
        [pscustomobject]@{Drive=$drive;FreeBytes=$v.AvailableFreeSpace;Format=$v.DriveFormat}
    })
    if($db-$Baseline -gt $MySqlGrowthBytes -or $out -gt $EvidenceBytes){throw 'Database/evidence capacity gate failed'}
    $future=Get-FuturePhysicalBytes ([Math]::Max(0L,$db-$Baseline)) $out
    if([IO.DriveInfo]::new('F:').AvailableFreeSpace-$future -lt $minimumFree){throw 'Shared F database/evidence future peak failed'}
    [pscustomobject]@{MysqlRoot=$root;DatabaseBytes=$db;DatabaseBaselineBytes=$Baseline;GrowthLimitBytes=$MySqlGrowthBytes;EvidenceBytes=$out;EvidenceLimitBytes=$EvidenceBytes;MinimumFreeBytes=$minimumFree;Volumes=$volumes;PhysicalReservation=$physicalReservation;RemainingPhysicalBytes=$future}
}
# 단독 큰 인자만으로 U 예외 진입 금지. 현재 등록·발급·예약의 같은 profile만 연결
function Read-Cap1PhysicalReservation {
    if($Action -ne 'Start' -or -not $Cap1PolicyPath -or -not $Cap1LedgerRoot -or
       -not $env:PIXEL_PLACE_CAP_REQUEST -or $env:PIXEL_PLACE_CAP_SERVICE_ROLE -cne 'mysql3307') {throw 'CAP1 physical reservation requires owned Start'}
    $prefix="$run/mysql-cap1-profile"
    $arguments=@('-B',"$PSScriptRoot/phase18_cap1.py",'--verify-storage-start',$Cap1PolicyPath,'--repo',$repo,'--ledger-root',$Cap1LedgerRoot,'--session',$RunId)
    $exitCode=Invoke-CapNative 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe' $arguments $prefix
    if($exitCode -ne 0){throw 'CAP1 physical profile verification failed'}
    $value=Get-Content -LiteralPath "$prefix.native.stdout" -Raw -Encoding UTF8|ConvertFrom-Json
    if($value.kind -cne 'cap1-physical-usage-v1' -or $value.SessionId -cne $RunId -or
       $value.MysqlLimitBytes -ne $MySqlGrowthBytes -or $value.EvidenceLimitBytes -ne $EvidenceBytes -or
       $value.ExpiresEpochMillis -ne [long]$env:PIXEL_PLACE_CAP_ORIGINAL_EXPIRES) {throw 'CAP1 physical caller binding mismatch'}
    foreach($field in @('MysqlUsageBytes','EvidenceUsageBytes','WorkspaceUsageBytes','WalUsageBytes','NextReservationBytes')) {
        if($value.$field -isnot [long] -and $value.$field -isnot [int] -or $value.$field -le 0){throw 'CAP1 finite physical usage required'}
    }
    return $value
}
function Get-FuturePhysicalBytes([long]$DatabaseGrowth,[long]$CurrentEvidence) {
    if(-not $physicalReservation){return [Math]::Max(0L,$MySqlGrowthBytes-$DatabaseGrowth)+[Math]::Max(0L,$EvidenceBytes-$CurrentEvidence)+5GB+64MB}
    $workspaceNow=Get-Bytes $large
    $walNow=Get-Bytes "F:/pixel-place-wal/phase18/$RunId"
    return [Math]::Max(0L,[long]$physicalReservation.MysqlUsageBytes-$DatabaseGrowth)+
        [Math]::Max(0L,[long]$physicalReservation.EvidenceUsageBytes-$CurrentEvidence)+
        [Math]::Max(0L,[long]$physicalReservation.WorkspaceUsageBytes-$workspaceNow)+
        [Math]::Max(0L,[long]$physicalReservation.WalUsageBytes-$walNow)+[long]$physicalReservation.NextReservationBytes
}
function Assert-Instance {
    $expectedRoot='F:/MySQL/test'
    if($DiagnosticDescriptor){
        # 실패 원본의 cold-copy 증거가 연결된 동일 session 사본만 허용. 원본 경로 채택 금지
        $copySession=if($instance.diagnosticSession){[string]$instance.diagnosticSession}else{$RunId}
        if($copySession -notmatch '^cap1-ws-diagnostic-[a-z0-9-]+$'){throw 'Diagnostic session namespace required'}
        $expectedRoot="F:/pixel-place-work/sessions/$copySession/mysql"
        if($copySession -cne $RunId){
            # 사본의 앞선 읽기 진단이 정상 SQL 종료된 뒤에만 새 lifecycle 소유자 허용
            $prior=$instance.previousNormalStop
            $priorPaths=@("F:/pixel-place-work/evidence/$copySession/mysql-stop.json","$($repo.Replace('\','/'))/build/agent-runs/$copySession/mysql-stop.json")
            if(-not $prior -or $priorPaths -cnotcontains $prior.path.Replace('\','/') -or (Get-FileHash -LiteralPath (Resolve-PixelPlaceHistoricalPath $prior.path)).Hash -ne $prior.sha256){throw 'Prior clone shutdown binding required'}
            $stopped=Get-Content -Raw -LiteralPath (Resolve-PixelPlaceHistoricalPath $prior.path)|ConvertFrom-Json
            if(-not $stopped.NormalShutdown -or -not $stopped.ShutdownObserved -or $stopped.SqlExit -ne 0 -or $stopped.Failures.Count -ne 0){throw 'Prior clone shutdown incomplete'}
        }
        if($Action -eq 'Environment'){throw 'Diagnostic clone is not a new CAP environment'}
        # 현재 F 증거와 보존된 과거 C identity만 허용. 임의 사본 proof 경로 허용 금지
        $copyProof=$instance.diagnosticCopy.Replace('\','/')
        $expectedProof=@("F:/pixel-place-work/evidence/$copySession/mysql-cold-copy.json",
            "$($repo.Replace('\','/'))/build/agent-runs/$copySession/mysql-cold-copy.json")
        if($instance.diagnosticSource.Replace('\','/') -cne 'F:/MySQL/test' -or $copyProof -cnotin $expectedProof){throw 'Diagnostic copy identity mismatch'}
        Assert-LocalPath $instance.diagnosticCopy
        if((Get-FileHash -LiteralPath (Resolve-PixelPlaceHistoricalPath $instance.diagnosticCopy)).Hash -ne $instance.diagnosticCopySha256){throw 'Diagnostic copy proof changed'}
        $copy=Get-Content -Raw -LiteralPath (Resolve-PixelPlaceHistoricalPath $instance.diagnosticCopy)|ConvertFrom-Json
        if(-not $copy.complete -or $copy.source.Replace('\','/') -cne 'F:/MySQL/test' -or $copy.destination.Replace("\","/") -cne $expectedRoot -or $copy.files.Count -lt 1){throw 'Complete cold copy required'}
        if((Get-Content -Raw -LiteralPath $config).Replace('\','/').Contains('F:/MySQL/test') -or (Get-Content -Raw -LiteralPath "$root/logs/binlog.index").Replace('\','/').Contains('F:/MySQL/test')){throw 'Clone points to original storage'}
    }
    if($instance.schemaVersion -ne 1 -or $root -cne $expectedRoot -or $config -cne "$root/runtime.ini" -or $instance.port -ne 3307 -or $instance.bind -ne '127.0.0.1'){throw 'Current instance descriptor mismatch'}
    foreach($p in @($root,$config,$binary,$descriptor,$run,$large)){Assert-LocalPath $p}
    if((Get-FileHash -LiteralPath $config).Hash -ne $instance.configSha256 -or (Get-FileHash -LiteralPath $binary).Hash -ne $instance.binarySha256){throw 'Configuration/binary hash mismatch'}
    $uuid=[regex]::Match([IO.File]::ReadAllText("$root/data/auto.cnf"),'(?m)^server-uuid=([a-z0-9-]+)\s*$').Groups[1].Value
    $owner=[regex]::Match([IO.File]::ReadAllText("$root/regression-ownership.properties"),'(?m)^server_uuid=([a-z0-9-]+)\s*$').Groups[1].Value
    if(-not $uuid -or $uuid -ne $instance.uuid -or $uuid -ne $owner){throw 'Persisted UUID mismatch'}
    $lines=Get-Content -LiteralPath $config
    foreach($line in @('port=3307','bind-address=127.0.0.1','mysqlx=OFF','innodb-flush-log-at-trx-commit=1','sync-binlog=1','binlog-row-image=FULL')){
        if($lines -notcontains $line){throw 'Runtime durability/loopback contract mismatch'}
    }
}
function Get-Live($Start) {
    if($Start.DescriptorHash -ne (Get-FileHash -LiteralPath (Resolve-PixelPlaceHistoricalPath $descriptor)).Hash -or $Start.RunId -ne $RunId){throw 'Session descriptor mismatch'}
    $launcher=Get-Process -Id $Start.Pid
    if($launcher.Path -ne [IO.Path]::GetFullPath($binary) -or $launcher.StartTime.ToUniversalTime().ToString('o') -ne $Start.StartUtc){throw 'Launcher PID/start identity mismatch'}
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue)
    if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1'){throw 'Owned loopback listener required'}
    $server=Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0].OwningProcess)"
    $pidFile=[int]([IO.File]::ReadAllText("$root/mysql.pid").Trim())
    if($server.ProcessId -ne $pidFile -or ($server.ProcessId -ne $Start.Pid -and $server.ParentProcessId -ne $Start.Pid) -or
        $server.ExecutablePath -ne [IO.Path]::GetFullPath($binary) -or $server.CommandLine -notmatch ('(?:^|\s|")--defaults-file='+[regex]::Escape($config)+'(?:\s|"|$)')){throw 'Listener PID/file/config/parent identity mismatch'}
    [pscustomobject]@{Pid=[long]$server.ProcessId;StartUtc=$server.CreationDate.ToUniversalTime().ToString('o');Binary=$server.ExecutablePath}
}
function Get-ProbeDriver {
    $drivers=@(Get-ChildItem -LiteralPath 'F:/pixel-place-work/gradle-home/caches/modules-2/files-2.1/com.mysql/mysql-connector-j' -Recurse -File -Filter '*.jar' |
        Where-Object {$_.Name -match '^mysql-connector-j-[0-9.]+\.jar$'})
    if($drivers.Count -ne 1){throw 'Exact existing JDBC runtime driver required'}
    Assert-PixelPlaceWorkPath $drivers[0].FullName
    $drivers[0].FullName
}
# 기존 native collector로 실제 exit와 양쪽 stream 완료를 함께 확보. Start-Process의 지연 handle 조회 회피
function Invoke-DatabaseProbeProcess([string[]]$JavaArguments,[string]$Prefix) {
    if(-not ('Phase18Cap.JobCapture' -as [type])){. "$PSScriptRoot/phase17-c-process.ps1"}
    $capture=[Phase17C.ProcessCapture]::new()
    $argv=@($JavaArguments|ForEach-Object {[Phase18Cap.JobCapture]::Quote($_)}) -join ' '
    $null=Get-CapOperationMillis
    $capture.Start('C:/Program Files/Java/jdk-21.0.10/bin/java.exe',$argv,$repo,"$Prefix.stdout","$Prefix.stderr",1048576)
    try {
        while(-not($capture.Process.HasExited -and $capture.OutTask.IsCompleted -and $capture.ErrTask.IsCompleted)){
            $null=Get-CapOperationMillis;Start-Sleep -Milliseconds ([Math]::Min(50,(Get-CapOperationMillis)))
        }
        foreach($stream in @($capture.Out,$capture.Err)){if($stream.Failure -or $stream.Overflow){throw 'Database probe stream collection failed'}}
        foreach($task in @($capture.OutTask,$capture.ErrTask)){if($task.IsFaulted -or $task.IsCanceled){throw 'Database probe stream task failed'}}
        $code=$capture.Process.ExitCode
        Save-NewJson "$Prefix.exit.json" @{ActualExit=$code;Pid=$capture.Process.Id;StreamsCompleted=$true}
        return $code
    } finally {if($capture.Process.HasExited -and $capture.OutTask.IsCompleted -and $capture.ErrTask.IsCompleted){$capture.Process.Dispose()}}
}

function Invoke-Probe([string]$Mode,[string]$Prefix,$Live) {
    . (Join-Path $PSScriptRoot 'harness-local-secrets.ps1')
    Import-HarnessLocalSecrets -Root $repo
    $application=[IO.File]::ReadAllText("$repo/src/main/resources/application.yml")
    $users=[regex]::Matches($application,'(?m)^    username: ([A-Za-z0-9_]+)\s*$')
    if($users.Count -ne 1 -or -not $env:DB_PASSWORD){throw 'Existing credential injection required'}
    $env:PIXEL_PLACE_BENCH_DB_USERNAME=$users[0].Groups[1].Value
    $driver=Get-ProbeDriver
    foreach($suffix in @('stdout','stderr')){if(Test-Path "$Prefix.$suffix"){throw 'Probe evidence already exists'}}
    # 실제 Process의 종료 코드 사용. native stderr/이전 LASTEXITCODE/함수 scope에 의존하지 않음
    $arguments=@("-Djava.io.tmpdir=$large/temp","-Djna.tmpdir=$large/jna",'--source','21','-cp',$driver,("$PSScriptRoot/TestDatabaseProbe.java"),$Mode,$root,$instance.uuid,([string]$Live.Pid),$Live.StartUtc)
    # Stop의 환경 위반은 Get-Budget에 보존. 종료 probe는 알려진 C/F 환경으로 격리해 원래 위반이 cleanup을 차단하지 않도록 제한
    $saved=@{};$clean=@{JAVA_HOME='C:/Program Files/Java/jdk-21.0.10';GRADLE_USER_HOME='F:/pixel-place-work/gradle-home';TEMP="$large/temp";TMP="$large/temp";
        PATH='C:/Windows/System32;C:/Windows';JAVA_TOOL_OPTIONS=$null;JDK_JAVA_OPTIONS=$null;_JAVA_OPTIONS=$null;JAVA_OPTS=$null;GRADLE_OPTS=$null;CLASSPATH=$null;PIXEL_PLACE_WAL_ACTIVE_FILE=$null}
    try {
        foreach($name in $clean.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$clean[$name],'Process')}
        $null=Get-CapOperationMillis
        $probeExit=Invoke-DatabaseProbeProcess $arguments $Prefix
    } finally {foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
    if($probeExit -ne 0){throw 'Read-only database probe or owned SQL shutdown failed; inspect redacted evidence'}
}

# 새 launcher와 SQL 검증으로 partial startup의 Stop만 허용. 기존 live proof가 있으면 exact identity 유지
function Get-SessionLive($Start) {
    $live=Get-Live $Start
    if(Test-Path "$run/mysql-start-live.json") {
        $initial=Get-Content -Raw -LiteralPath "$run/mysql-start-live.json" | ConvertFrom-Json
        if($live.Pid -ne $initial.Live.Pid -or $live.StartUtc -ne $initial.Live.StartUtc){throw 'Server PID/start identity changed'}
    } elseif($Action -ne 'Stop') {
        throw 'Startup live verification incomplete; only guarded Stop permitted'
    }
    $live
}

# SQL 응답과 자원 소멸은 별도 사실. probe 예외 뒤에도 실제 종료 여부 관측, 강제 kill 없음
function Wait-OwnedShutdown($Start,$Live) {
    $deadline=[DateTime]::UtcNow.AddMilliseconds((Get-CapOperationMillis 45000))
    do {
        # 개별 PID/port 부재 예외를 숨기지 않고 전체 관측 후 선택. 관측 API 실패를 종료 완료로 오인 방지
        $alive=@(Get-Process -ErrorAction Stop | Where-Object {$_.Id -in @($Start.Pid,$Live.Pid)})
        $listeners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 3307)
        if(-not $alive.Count -and -not $listeners.Count){return $true}
        Start-Sleep -Milliseconds ([Math]::Min(250,(Get-CapOperationMillis)))
    } while([DateTime]::UtcNow -lt $deadline)
    return $false
}

# 원래 위반과 종료/후속 검사 실패를 독립 보존. JDBC 비밀정보는 Invoke-Probe에서 이미 차단
function Add-StopFailure($Report,[string]$Phase,$Failure) {
    $Report.Failures+=@{Phase=$Phase;Type=$Failure.Exception.GetType().Name;Message=$Failure.Exception.Message}
}

# 환경 제한 위반은 실패로 남기되 소유 인스턴스의 정상 종료를 막지 않는 cleanup 경계
function Invoke-OwnedStop($Start,[string]$Result) {
    $report=[ordered]@{RunId=$RunId;Action='Stop';Live=$null;ProbeInvoked=$false;SqlExit=$null;
        ShutdownObserved=$null;NormalShutdown=$false;BudgetBefore=$null;Budget=$null;
        OriginalServicesBefore=$null;OriginalServices=$null;Failures=@()}
    try {$report.BudgetBefore=Get-Budget $Start.Budget.DatabaseBaselineBytes}
    catch {Add-StopFailure $report 'pre-budget' $_}
    try {$report.OriginalServicesBefore=@(Assert-Services $Start.OriginalServices)}
    catch {Add-StopFailure $report 'pre-services' $_}

    try {
        # 환경 오류를 잡는 분기와 분리된 hard gate. UUID/hash/path/owner와 PID/start 검증 실패 시 SQL 호출 금지
        Assert-Instance
        $live=Get-SessionLive $Start
        $report.Live=$live
    } catch {Add-StopFailure $report 'ownership' $_}
    if($null -ne $report.Live) {
        try {
            $report.ProbeInvoked=$true
            # Java가 live UUID/경로/소유권/다른 client 부재 및 PID/start를 재검증한 뒤에만 SHUTDOWN
            Invoke-Probe shutdown "$run/mysql-$Receipt-probe" $live
            $report.SqlExit=0
        } catch {Add-StopFailure $report 'shutdown-probe' $_}
        try {
            $report.ShutdownObserved=Wait-OwnedShutdown $Start $live
            if(-not $report.ShutdownObserved){throw 'SQL shutdown not fully observed; no force kill'}
        } catch {Add-StopFailure $report 'shutdown-observation' $_}
        $report.NormalShutdown=($report.SqlExit -eq 0 -and $report.ShutdownObserved -eq $true)
    }

    # 종료 사실 확정 이후 검사 오류가 앞선 SQL/프로세스 결과를 덮어쓰지 않도록 별도 수집
    try {$report.Budget=Get-Budget $Start.Budget.DatabaseBaselineBytes}
    catch {Add-StopFailure $report 'post-budget' $_}
    try {$report.OriginalServices=@(Assert-Services $Start.OriginalServices)}
    catch {Add-StopFailure $report 'post-services' $_}
    $report.Utc=[DateTime]::UtcNow.ToString('o')
    try {
        Save-NewJson $Result $report
        $report['ReceiptWrite']='succeeded'
    } catch {
        $report['ReceiptWrite']='failed'
        Add-StopFailure $report 'receipt-write' $_
    }
    # 디스크 증거 실패 때도 호출자의 출력/예외에서 원래 실패와 실제 종료 상태 복구 가능
    Write-Output ('MYSQL_TEST_STOP_RESULT '+($report|ConvertTo-Json -Depth 12 -Compress))
    if($report.Failures.Count) {
        $failure=[InvalidOperationException]::new("MySQL Stop failed: shutdownObserved=$($report.ShutdownObserved); normalShutdown=$($report.NormalShutdown); failures=$($report.Failures.Phase -join ',')")
        $failure.Data['StopResult']=$report
        throw $failure
    }
}

# 준비 성공 인계와 실패 후 정상 Stop 전용 소유 증거 구분
function Publish-CapMysqlService($Start,$Live,[switch]$CleanupOnly) {
    if(-not $env:PIXEL_PLACE_CAP_REQUEST){return}
    if($env:PIXEL_PLACE_CAP_SERVICE_ROLE -cne 'mysql3307'){throw 'MySQL service role required'}
    $identities=@(foreach($id in @($Start.Pid,$Live.Pid)|Select-Object -Unique){
        $ownedProcess=Get-Process -Id $id -ErrorAction Stop
        @{Pid=$id;StartUtc=$ownedProcess.StartTime.ToUniversalTime().ToString('o');Binary=$ownedProcess.Path;BinarySha256=(Get-FileHash -LiteralPath $ownedProcess.Path).Hash}
    })
    $receipt=if($CleanupOnly){"$run/mysql-start.json"}else{"$run/mysql-start-live.json"}
    $suffix=if($CleanupOnly){'cleanup-service'}else{'service'}
    Save-CapJson "$env:PIXEL_PLACE_CAP_REQUEST.$suffix.json" @{Role='mysql3307';Ready=(-not $CleanupOnly);Purpose=if($CleanupOnly){'normal-stop-only'}else{'ready'};Request=$env:PIXEL_PLACE_CAP_REQUEST;Processes=$identities;ReadinessReceipt=$receipt;ReadinessHash=(Get-FileHash -LiteralPath $receipt).Hash}
}

$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
if([Environment]::UserName -ne $instance.owner -or ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Non-elevated cgt required'}
$mutex=[Threading.Mutex]::new($false,'Local\PixelPlaceMySql3307Lifecycle');$locked=$false
try {
    $locked=$mutex.WaitOne(0);if(-not $locked){throw 'Concurrent MySQL lifecycle operation forbidden'}
    Assert-Instance
    if(-not(Test-Path -LiteralPath $run)){New-Item -ItemType Directory -Path $run | Out-Null}
    $result="$run/mysql-$Receipt.json"
    if(Test-Path -LiteralPath $result){throw 'Fresh receipt required; completed evidence preserved'}
    if($Action -in @('Inspect','Start')) {
        if(Test-Path "$run/mysql-start.json"){throw 'This session already owns a startup; use Verify/Stop'}
        if(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue){throw '3307 already listening; adoption forbidden'}
        if(@(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" | Where-Object {$_.CommandLine -match [regex]::Escape($config)}).Count){throw 'Owned process still starting/stopping; inspect before retry'}
        if($Cap1PolicyPath){$physicalReservation=Read-Cap1PhysicalReservation}
        $services=@(Get-Services);$baseline=Get-Bytes $root;$budget=Get-Budget $baseline
        if(([IO.DriveInfo]::new('F:')).AvailableFreeSpace -lt $minimumFree+$(if($physicalReservation){[long]$physicalReservation.MysqlUsageBytes}else{$MySqlGrowthBytes})){throw 'F database reservation unavailable'}
        if($Action -eq 'Inspect') {
            Save-NewJson $result @{RunId=$RunId;User=$identity.Name;Elevated=$false;Utc=[DateTime]::UtcNow.ToString('o');DescriptorHash=(Get-FileHash (Resolve-PixelPlaceHistoricalPath $descriptor)).Hash;Budget=$budget;OriginalServices=$services}
        } else {
            if($Receipt -ne 'start'){throw 'Startup receipt name is fixed'}
            if($env:PIXEL_PLACE_CAP_REQUEST -and $env:PIXEL_PLACE_CAP_SERVICE_ROLE -cne 'mysql3307'){throw 'MySQL service role required before start'}
            $null=Get-ProbeDriver
            if(Test-Path "$run/mysql-start-intent.json"){throw 'Previous launch intent exists; inspect before retry'}
            $null=Set-PixelPlaceWorkEnvironment $RunId
            Save-NewJson "$run/mysql-start-intent.json" @{RunId=$RunId;Utc=[DateTime]::UtcNow.ToString('o');DescriptorHash=(Get-FileHash (Resolve-PixelPlaceHistoricalPath $descriptor)).Hash;Budget=$budget;OriginalServices=$services}
            $env:TEMP="$large/temp";$env:TMP=$env:TEMP
            $null=Get-CapOperationMillis
            if(-not ('Phase18Cap.JobCapture' -as [type])){. "$PSScriptRoot/phase17-c-process.ps1"}
            $p=[Phase18Cap.JobCapture]::StartService($binary,([Phase18Cap.JobCapture]::Quote("--defaults-file=$config")),$repo,"$run/mysql.stdout","$run/mysql.stderr")
            $start=@{RunId=$RunId;Pid=$p.Id;StartUtc=$p.StartTime.ToUniversalTime().ToString('o');DescriptorHash=(Get-FileHash (Resolve-PixelPlaceHistoricalPath $descriptor)).Hash;Root=$root;Config=$config;Uuid=$instance.uuid;Binary=$binary;User=$identity.Name;Elevated=$false;Budget=$budget;OriginalServices=$services}
            Save-NewJson $result $start
            $deadline=[DateTime]::UtcNow.AddMilliseconds((Get-CapOperationMillis 45000))
            while(-not(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue)){
                if($p.HasExited -or [DateTime]::UtcNow -gt $deadline){throw 'Startup incomplete; preserve identity and inspect, no force kill'}
                Start-Sleep -Milliseconds ([Math]::Min(250,(Get-CapOperationMillis)))
            }
            try {
                $live=Get-Live $start
                Invoke-Probe verify "$run/mysql-start-probe" $live
                Save-NewJson "$run/mysql-start-live.json" @{Live=$live;Budget=(Get-Budget $baseline);OriginalServices=@(Assert-Services $services);Utc=[DateTime]::UtcNow.ToString('o')}
                Publish-CapMysqlService $start $live
            } catch {
                $startupFailure=$_
                # DB 사용 작업 종료 뒤 남은 서비스의 identity만 제시. probe/인계 실패는 그대로 전파
                try {$cleanupLive=Get-Live $start;Publish-CapMysqlService $start $cleanupLive -CleanupOnly}
                catch {Write-Output ('MYSQL_CLEANUP_OFFER_UNAVAILABLE type='+$_.Exception.GetType().Name)}
                throw $startupFailure
            }
        }
    } elseif($Action -eq 'Stop') {
        $start=Get-Content -Raw -LiteralPath "$run/mysql-start.json" | ConvertFrom-Json
        $MySqlGrowthBytes=[long]$start.Budget.GrowthLimitBytes;$EvidenceBytes=[long]$start.Budget.EvidenceLimitBytes;$physicalReservation=$start.Budget.PhysicalReservation
        Invoke-OwnedStop $start $result
    } else {
        $start=Get-Content -Raw -LiteralPath "$run/mysql-start.json" | ConvertFrom-Json
        $live=Get-SessionLive $start
        $MySqlGrowthBytes=[long]$start.Budget.GrowthLimitBytes;$EvidenceBytes=[long]$start.Budget.EvidenceLimitBytes;$physicalReservation=$start.Budget.PhysicalReservation
        $budget=Get-Budget $start.Budget.DatabaseBaselineBytes
        $services=@(Assert-Services $start.OriginalServices)
        Invoke-Probe verify "$run/mysql-$Receipt-probe" $live
        if($Action -eq 'Environment') {
            . (Join-Path $PSScriptRoot 'mysql-test-redis.ps1')
            $redis=Read-TestRedisIdentity $RedisDatabase
            $envFile="$run/environment-$Receipt.json"
            Save-NewJson $envFile ([ordered]@{schemaVersion=1;sessionId=$RunId;owner=$instance.owner;mysqlRoot=$root;mysqlUuid=$instance.uuid;mysqlPid=$live.Pid;mysqlStartUtc=$live.StartUtc;mysqlBinary=$binary;mysqlBinaryHash=$instance.binarySha256;mysqlConfigHash=$instance.configSha256;redisPid=$redis.Pid;redisStartUtc=$redis.StartUtc;redisRunId=$redis.RunId;catalogMode='create-new';precreatedOwner='';mysqlBaselineBytes=$start.Budget.DatabaseBaselineBytes;mysqlGrowthBytes=$MySqlGrowthBytes;evidenceBytes=$EvidenceBytes;minimumFreeBytes=$minimumFree;observedUtc=[DateTime]::UtcNow.ToString('o')})
            Save-NewJson "$run/redis-$Receipt.json" $redis
        }
        Save-NewJson $result @{RunId=$RunId;Action=$Action;Live=$live;SqlExit=0;NormalShutdown=($Action -eq 'Stop');Budget=(Get-Budget $start.Budget.DatabaseBaselineBytes);OriginalServices=@(Assert-Services $services);Utc=[DateTime]::UtcNow.ToString('o')}
    }
    Write-Output "MYSQL_TEST_$($Action.ToUpperInvariant())_OK run=$RunId root=$root"
} finally {if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()}
