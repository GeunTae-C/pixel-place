# 실제 lifecycle 분기와 guard를 실행하되 process/SQL/용량 관측만 대역. DB·서비스 변경 책임 없음
param([Parameter(Mandatory=$true)][string]$EvidenceRoot)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'phase18-cap-process.ps1')
$repo=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'pixel-place-work.ps1')
$descriptor=Join-Path $PSScriptRoot 'mysql-test-instance.json'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/mysql-test-instance.ps1",[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Lifecycle parser failure'}
foreach($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) {
    Invoke-Expression $definition.Extent.Text
}
$productionBudget=${function:Get-Budget};$productionSave=${function:Save-NewJson}
$productionLive=${function:Get-Live};$productionWait=${function:Wait-OwnedShutdown}
# 현재 실행부의 실제 분기 사용. OS 사용자 guard만 제외하고 mutex/Assert-Instance/receipt/dispatch는 그대로 검증
$entry=$ast.EndBlock.Statements[-1]
if($entry -isnot [Management.Automation.Language.TryStatementAst]){throw 'Lifecycle entry changed; review test adapter'}
$lifecycle=[scriptblock]::Create($entry.Extent.Text)

function Get-Bytes([string]$Path) {
    if($Path -eq $root){return [long]$simulation.DatabaseBytes}
    if($Path -eq $run){return [long]$simulation.EvidenceBytes}
    throw 'Unexpected accounting path'
}
function Get-Budget([long]$Baseline) {
    $simulation.BudgetCalls++
    if($simulation.PostBudget -and $simulation.BudgetCalls -gt 1){throw 'Injected post-shutdown capacity failure'}
    & $productionBudget $Baseline
}
function Get-Services {
    $simulation.ServiceCalls++
    foreach($service in $originalServices) {
        $changed=($service.Port -eq $simulation.ChangedPort -or ($simulation.PostService -and $simulation.ServiceCalls -gt 1))
        [pscustomobject]@{Port=$service.Port;Pid=($service.Pid+[int]$changed);StartUtc=$service.StartUtc}
    }
}
function Get-Process($Id,$ErrorAction) {
    if($simulation.ProcessObservationError -and $simulation.WaitCalls){throw 'Injected process inventory failed'}
    if(-not $simulation.Running){return}
    # 소유 launcher/server 두 프로세스의 존재도 관측. SQL 대역이 소멸시킨 뒤에만 빈 결과
    if($null -eq $Id){$Id=@(101,102)}
    foreach($processId in @($Id)) {
        [pscustomobject]@{Id=$processId;Path=$simulation.LauncherPath;StartTime=$simulation.LauncherStart}
    }
}
function Get-NetTCPConnection($State,$LocalPort,$ErrorAction) {
    if($simulation.ListenerObservationError -and $simulation.WaitCalls){throw 'Injected listener inventory failed'}
    if($LocalPort -and $LocalPort -ne 3307){throw 'Unexpected listener lookup'}
    if($simulation.Running){[pscustomobject]@{LocalPort=3307;LocalAddress=$simulation.Bind;OwningProcess=$simulation.ServerPid}}
}
function Get-CimInstance($ClassName,$Filter) {
    if($Filter -eq "Name='mysqld.exe'"){return}
    [pscustomobject]@{ProcessId=$simulation.ServerPid;ParentProcessId=$simulation.ParentPid;
        ExecutablePath=$simulation.ServerBinary;CommandLine=$simulation.CommandLine;CreationDate=$simulation.ServerStart}
}
function Get-Live($Start) {
    # F 파일을 바꾸지 않고 pid-file 입력만 로컬 fixture로 바꿔 실제 Get-Live의 모든 identity 분기 실행
    $root=$fixtureRoot
    & $productionLive $Start
}
function Invoke-Probe([string]$Mode,[string]$Prefix,$Live) {
    $simulation.ProbeCalls++
    if($simulation.OtherClient){throw 'Other database client present'}
    if($Mode -ne 'shutdown'){throw 'Unexpected probe: failure gate did not stop Verify/Environment'}
    $simulation.SqlCalls++
    if(-not $simulation.Incomplete){$simulation.Running=$false}
    if($simulation.ProbeErrorAfterExit){throw 'Injected probe failure after server exit'}
}
function Wait-OwnedShutdown($Start,$Live) {
    $simulation.WaitCalls++
    if($simulation.ObservationError){throw 'Injected process observation failure'}
    # 실패/timeout 대역에서만 실제 45초 대기를 생략. 정상 종료는 production 관측으로 판정
    if($simulation.Running){return $false}
    & $productionWait $Start $Live
}
function Save-NewJson([string]$Path,$Value) {
    if($simulation.ReceiptError -and $Path -eq "$run/mysql-stop.json"){throw [IO.IOException]::new('Injected receipt write failure')}
    & $productionSave $Path $Value
}
function Require([bool]$Condition,[string]$Message) {if(-not $Condition){throw $Message}}

$cases=@(
    @{Name='normal';Sql=1;Normal=$true},
    @{Name='e-environment';Sql=1;Normal=$true;Phases=@('pre-budget','post-budget');Error='Historical E'},
    @{Name='database-capacity';Sql=1;Normal=$true;Phases=@('pre-budget','post-budget');Error='capacity'},
    @{Name='evidence-capacity';Sql=1;Normal=$true;Phases=@('pre-budget','post-budget');Error='capacity'},
    @{Name='minimum-free';Sql=1;Normal=$true;Phases=@('pre-budget','post-budget');Error='free-space'},
    @{Name='mysql3306-changed';Sql=1;Normal=$true;Phases=@('pre-services','post-services');Error='identity changed'},
    @{Name='redis-changed';Sql=1;Normal=$true;Phases=@('pre-services','post-services');Error='identity changed'},
    @{Name='capacity-and-service';Sql=1;Normal=$true;Phases=@('pre-budget','pre-services','post-budget','post-services');Error='capacity'},
    @{Name='post-budget';Sql=1;Normal=$true;Phases=@('post-budget');Error='capacity'},
    @{Name='post-service';Sql=1;Normal=$true;Phases=@('post-services');Error='identity changed'},
    @{Name='receipt-write';Sql=1;Normal=$true;Phases=@('receipt-write');Error='receipt write'},
    @{Name='violations-and-receipt';Sql=1;Normal=$true;Phases=@('pre-budget','pre-services','post-budget','post-services','receipt-write');Error='capacity'},
    @{Name='probe-error-after-exit';Sql=1;Normal=$false;Phases=@('shutdown-probe');Error='probe failure'},
    @{Name='shutdown-incomplete';Sql=1;Normal=$false;Phases=@('shutdown-observation');Error='not fully observed'},
    @{Name='observation-error';Sql=1;Normal=$false;Phases=@('shutdown-observation');Error='observation failure'},
    @{Name='process-observation-error';Sql=1;Normal=$false;Phases=@('shutdown-observation');Error='process inventory failed'},
    @{Name='listener-observation-error';Sql=1;Normal=$false;Phases=@('shutdown-observation');Error='listener inventory failed'},
    @{Name='other-client';Sql=0;Normal=$false;Phases=@('shutdown-probe','shutdown-observation');Error='Other database client'},
    @{Name='wrong-uuid';Sql=0;Error='UUID'},
    @{Name='wrong-config-hash';Sql=0;Error='hash'},
    @{Name='wrong-binary-hash';Sql=0;Error='hash'},
    @{Name='wrong-root';Sql=0;Error='descriptor'},
    @{Name='wrong-session';Sql=0;Phases=@('ownership');Error='descriptor'},
    @{Name='wrong-launcher-start';Sql=0;Phases=@('ownership');Error='Launcher PID/start'},
    @{Name='wrong-launcher-binary';Sql=0;Phases=@('ownership');Error='Launcher PID/start'},
    @{Name='wrong-pid-file';Sql=0;Phases=@('ownership');Error='Listener PID/file/config/parent'},
    @{Name='wrong-parent';Sql=0;Phases=@('ownership');Error='Listener PID/file/config/parent'},
    @{Name='wrong-server-binary';Sql=0;Phases=@('ownership');Error='Listener PID/file/config/parent'},
    @{Name='wrong-server-config';Sql=0;Phases=@('ownership');Error='Listener PID/file/config/parent'},
    @{Name='wrong-server-start';Sql=0;Phases=@('ownership');Error='Server PID/start'},
    @{Name='wrong-live-pid';Sql=0;Phases=@('ownership');Error='Server PID/start'},
    @{Name='wrong-bind';Sql=0;Phases=@('ownership');Error='loopback'},
    @{Name='capacity-with-wrong-identity';Sql=0;Phases=@('pre-budget','ownership','post-budget');Error='Launcher PID/start'},
    @{Name='partial-start-stop';Sql=1;Normal=$true},
    @{Name='verify-capacity';Action='Verify';Sql=0;Error='capacity'},
    @{Name='verify-service';Action='Verify';Sql=0;Error='identity changed'},
    @{Name='environment-capacity';Action='Environment';Sql=0;Error='capacity'},
    @{Name='environment-service';Action='Environment';Sql=0;Error='identity changed'},
    @{Name='start-capacity';Action='Start';Sql=0;Error='capacity'},
    @{Name='start-free';Action='Start';Sql=0;Error='free-space'},
    @{Name='verify-partial-start';Action='Verify';Sql=0;Error='Startup live verification incomplete'}
)
$results=[Collections.Generic.List[object]]::new()
foreach($case in $cases) {
    $instance=Get-Content -Raw -LiteralPath $descriptor | ConvertFrom-Json
    $root=$instance.root;$config=$instance.config;$binary=$instance.binary
    $RunId='test-stop-'+$case.Name;$run=Join-Path $EvidenceRoot $case.Name
    $large='F:/pixel-place-work/sessions/test-stop';$Action=if($case.Action){$case.Action}else{'Stop'};$Receipt=$Action.ToLowerInvariant()
    $minimumFree=10GB;$MySqlGrowthBytes=2GB;$EvidenceBytes=64MB
    New-Item -ItemType Directory -Path $run | Out-Null
    $fixtureRoot=Join-Path $run 'pid-fixture';New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    [IO.File]::WriteAllText("$fixtureRoot/mysql.pid",'102')
    $instant=[DateTime]::Parse('2026-10-03T01:00:00Z').ToUniversalTime()
    $originalServices=@([pscustomobject]@{Port=3306;Pid=201;StartUtc='original-mysql'},[pscustomobject]@{Port=6379;Pid=202;StartUtc='original-redis'})
    $simulation=@{Running=($Action -ne 'Start');DatabaseBytes=1000L;EvidenceBytes=0L;BudgetCalls=0;ServiceCalls=0;
        ChangedPort=0;ProbeCalls=0;SqlCalls=0;WaitCalls=0;LauncherStart=$instant;LauncherPath=[IO.Path]::GetFullPath($binary);
        ServerPid=102;ParentPid=101;ServerStart=$instant;ServerBinary=[IO.Path]::GetFullPath($binary);Bind='127.0.0.1';CommandLine="--defaults-file=$config"}
    $start=@{RunId=$RunId;Pid=101;StartUtc=$instant.ToString('o');DescriptorHash=(Get-FileHash $descriptor).Hash;
        Budget=@{DatabaseBaselineBytes=1000L;GrowthLimitBytes=$MySqlGrowthBytes;EvidenceLimitBytes=$EvidenceBytes};OriginalServices=$originalServices}
    $initial=@{Live=@{Pid=102;StartUtc=$instant.ToString('o')}}
    switch($case.Name) {
        'e-environment' {$env:CLASSPATH='E:/old/runtime.jar'}
        'database-capacity' {$simulation.DatabaseBytes=3GB}
        'evidence-capacity' {$simulation.EvidenceBytes=65MB}
        'minimum-free' {$minimumFree=[long]::MaxValue}
        'mysql3306-changed' {$simulation.ChangedPort=3306}
        'redis-changed' {$simulation.ChangedPort=6379}
        'capacity-and-service' {$simulation.DatabaseBytes=3GB;$simulation.ChangedPort=3306}
        'post-budget' {$simulation.PostBudget=$true}
        'post-service' {$simulation.PostService=$true}
        'receipt-write' {$simulation.ReceiptError=$true}
        'violations-and-receipt' {$simulation.DatabaseBytes=3GB;$simulation.ChangedPort=6379;$simulation.ReceiptError=$true}
        'probe-error-after-exit' {$simulation.ProbeErrorAfterExit=$true}
        'shutdown-incomplete' {$simulation.Incomplete=$true}
        'observation-error' {$simulation.ObservationError=$true}
        'process-observation-error' {$simulation.ProcessObservationError=$true}
        'listener-observation-error' {$simulation.ListenerObservationError=$true}
        'other-client' {$simulation.OtherClient=$true}
        'wrong-uuid' {$instance.uuid='00000000-0000-0000-0000-000000000000'}
        'wrong-config-hash' {$instance.configSha256='0'*64}
        'wrong-binary-hash' {$instance.binarySha256='0'*64}
        'wrong-root' {$root='E:/pixel-place-phase15'}
        'wrong-session' {$start.DescriptorHash='0'*64}
        'wrong-launcher-start' {$simulation.LauncherStart=$instant.AddSeconds(1)}
        'wrong-launcher-binary' {$simulation.LauncherPath='C:\wrong.exe'}
        'wrong-pid-file' {[IO.File]::WriteAllText("$fixtureRoot/mysql.pid",'999')}
        'wrong-parent' {$simulation.ParentPid=999}
        'wrong-server-binary' {$simulation.ServerBinary='C:\wrong.exe'}
        'wrong-server-config' {$simulation.CommandLine='--defaults-file=F:/MySQL/main/my.ini'}
        'wrong-server-start' {$simulation.ServerStart=$instant.AddSeconds(1)}
        'wrong-live-pid' {$initial.Live.Pid=999}
        'wrong-bind' {$simulation.Bind='0.0.0.0'}
        'capacity-with-wrong-identity' {$simulation.DatabaseBytes=3GB;$simulation.LauncherStart=$instant.AddSeconds(1)}
        'verify-capacity' {$simulation.DatabaseBytes=3GB}
        'verify-service' {$simulation.ChangedPort=3306}
        'environment-capacity' {$simulation.DatabaseBytes=3GB}
        'environment-service' {$simulation.ChangedPort=6379}
        'start-capacity' {$simulation.EvidenceBytes=65MB}
        'start-free' {$minimumFree=[long]::MaxValue}
    }
    if($Action -ne 'Start') {
        & $productionSave "$run/mysql-start.json" $start
        if($case.Name -notin @('partial-start-stop','verify-partial-start')){& $productionSave "$run/mysql-start-live.json" $initial}
    }
    $identity=@{Name='test-double'};$mutex=[Threading.Mutex]::new($false);$locked=$false
    $captured=[Collections.Generic.List[string]]::new();$caught=$null
    try {& $lifecycle | ForEach-Object {$captured.Add([string]$_)}} catch {$caught=$_}
    finally {if($case.Name -eq 'e-environment'){Remove-Item Env:CLASSPATH}}
    Require ($simulation.SqlCalls -eq $case.Sql) "SQL call mismatch: $($case.Name)"
    if($case.Error) {
        Require ($null -ne $caught) "Failure hidden: $($case.Name)"
        Require (-not @($captured|Where-Object {$_ -match '^MYSQL_TEST_\w+_OK'}).Count) 'Failed operation reported OK'
    } else {Require ($null -eq $caught) "Unexpected error: $($case.Name) $caught"}
    $lines=@($captured|Where-Object {$_ -like 'MYSQL_TEST_STOP_RESULT *'})
    $report=$null
    if($lines.Count) {
        Require ($lines.Count -eq 1) 'Duplicate Stop result'
        $report=$lines[0].Substring('MYSQL_TEST_STOP_RESULT '.Length)|ConvertFrom-Json
        Require ($report.NormalShutdown -eq [bool]$case.Normal) "Wrong normal shutdown result: $($case.Name)"
        $expectedPhases=@($case.Phases|Where-Object {$_})
        Require (($report.Failures.Phase -join ',') -eq ($expectedPhases -join ',')) "Failure phases lost: $($case.Name)"
        if($case.Error){Require (($report.Failures.Message -join ';') -like "*$($case.Error)*") 'Original failure lost'}
        if($case.Sql -eq 1 -and -not ($simulation.Incomplete -or $simulation.ObservationError -or $simulation.ProcessObservationError -or $simulation.ListenerObservationError)) {
            Require ($report.ShutdownObserved -and -not $simulation.Running -and $simulation.WaitCalls -eq 1) 'Server exit not independently observed'
        }
        if($simulation.ReceiptError) {
            Require ($report.ReceiptWrite -eq 'failed' -and $caught.Exception.Data['StopResult'].NormalShutdown) 'Receipt failure hid shutdown'
        } else {
            $saved=Get-Content -Raw -LiteralPath "$run/mysql-stop.json"|ConvertFrom-Json
            Require ($saved.NormalShutdown -eq $report.NormalShutdown -and ($saved.Failures.Phase -join ',') -eq ($report.Failures.Phase -join ',')) 'Receipt disagrees with output'
        }
    } elseif($case.Error) {Require ($caught.Exception.Message -like "*$($case.Error)*") "Wrong rejection: $($case.Name) $caught"}
    if($case.Phases -or $case.Normal){Require ($null -ne $report) 'Stop report missing'}
    if($case.Sql -eq 0 -and $case.Name -ne 'other-client'){Require ($simulation.ProbeCalls -eq 0 -and $simulation.WaitCalls -eq 0) 'Unsafe probe after identity/gate rejection'}
    $results.Add(@{Case=$case.Name;Passed=$true;FailedAsExpected=($null -ne $caught);ProbeCalls=$simulation.ProbeCalls;
        SqlCalls=$simulation.SqlCalls;ServerRunning=$simulation.Running;Report=$report})
}
& $productionSave (Join-Path $EvidenceRoot 'stop-tests.json') @{Passed=$results.Count;Cases=@($results);ActualDatabaseMutation=$false}
Write-Output "MYSQL_STOP_TESTS_PASS count=$($results.Count)"
