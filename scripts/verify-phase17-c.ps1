param(
    [Parameter(Mandatory=$true)][string]$Plan,
    [Parameter(Mandatory=$true)][ValidateSet('smoke','recover-drained','prepare-unflushed','recover-unflushed','test','redisIntegrationTest','mysqlIntegrationTest','bootJar','windowsWalIntegrationTest')][string]$Action,
    [Parameter(Mandatory=$true)][string]$Label,
    [string]$Case,
    [ValidateSet('C1','C2','C3')][string]$Scope='C3',
    [string[]]$Tests=@(),
    [string]$TaskFile,
    [string]$MysqlOwnership
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\phase17-c-process.ps1"
$planPath=Resolve-Phase17CPlannedPath $Plan $Plan
$p=Get-Content -Raw -LiteralPath $planPath | ConvertFrom-Json
$project=Resolve-Phase17CPlannedPath (Split-Path $PSScriptRoot -Parent) $p.sourceRoot
$large=Resolve-Phase17CPlannedPath $p.outputRoot $p.outputRoot
Assert-Phase17CPath $large 'E:\pixel-place-phase15'
Assert-Phase17CPath $p.walRoot "$project\data"
Assert-Phase17CPath $planPath $p.smallRoot
$runLock=[IO.File]::Open("$large\c-run.lock",[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
if($Label -notmatch '\A[a-z0-9][a-z0-9-]{0,63}\z'){throw 'Invalid fresh C label'}
$javaAction=$Action -in @('smoke','recover-drained','prepare-unflushed','recover-unflushed')
if($javaAction) {
    if($Scope -ne 'C2' -or $Tests.Count){throw 'Actual C app actions require explicit C2 scope'}
    if($Case -notin @('single-smoke','group-smoke','single-unflushed','group-unflushed')){throw 'Unknown C case'}
    if(($Case.EndsWith('-smoke') -and $Action -notin @('smoke','recover-drained')) -or ($Case.EndsWith('-unflushed') -and $Action -notin @('prepare-unflushed','recover-unflushed'))){throw 'Case/action mismatch'}
} elseif($Case){throw 'Task does not accept case'}
if($Scope -eq 'C1' -and ($Action -ne 'test' -or $Tests.Count -eq 0)){throw 'C1 only supports explicit selected related tests'}
if($Scope -ne 'C1' -and $Tests.Count){throw 'Final tasks cannot use selected filters'}
if(-not $TaskFile){$TaskFile=$p.taskFile.Path}
$taskPath=Resolve-Phase17CPlannedPath $TaskFile $TaskFile
Assert-Phase17CPath $taskPath "$project\명령문"
$mysqlProof=$null
if($javaAction -or $Action -eq 'mysqlIntegrationTest') {
    if(-not $MysqlOwnership){throw 'Explicit fresh MySQL session ownership evidence required'}
    $mysqlProof=Resolve-Phase17CPlannedPath $MysqlOwnership $MysqlOwnership
    Assert-Phase17CPath $mysqlProof $p.smallRoot
    if(-not(Test-Path -LiteralPath $mysqlProof -PathType Leaf)){throw 'Current MySQL evidence missing'}
}
$run="$large\runs\$Label";$app="$large\app\$Label";$temp="$large\temp\$Label"
foreach($path in @($run,$app,$temp)){Assert-Phase17CPath $path $large;if(Test-Path -LiteralPath $path){throw 'Label/output already exists'}}
function Get-C17Size([string]$Path){[long](Get-ChildItem -LiteralPath $Path -Recurse -File -Force | Measure-Object Length -Sum).Sum}
function Test-C17Budget {
    if((Get-C17Size $large) -gt $p.budget.outputBytes -or ((Get-C17Size $p.mysqlRoot)-$p.budget.databaseBaselineBytes) -gt $p.budget.databaseGrowthBytes -or
        (Get-C17Size $p.walRoot) -gt $p.budget.walTotalBytes -or ([IO.DriveInfo]::new('E:')).AvailableFreeSpace -lt $p.budget.minimumEFreeBytes -or
        ([IO.DriveInfo]::new('C:')).AvailableFreeSpace -lt $p.budget.minimumCFreeBytes){throw 'C cumulative budget exceeded'}
}
function Get-C17Inputs {
    $paths=@(Get-ChildItem -LiteralPath "$project\src","$project\scripts" -Recurse -File -Force | Where-Object {$_.Extension -notin '.zip','.pyc'} | Select-Object -ExpandProperty FullName)
    $paths+=@("$project\build.gradle","$project\settings.gradle","$project\gradlew.bat","$project\pixel_place.sql",$planPath,$taskPath)
    @(foreach($path in $paths | Sort-Object -Unique){[pscustomobject]@{Path=$path;Sha256=(Get-FileHash -LiteralPath $path).Hash}})
}
function Get-C17Classes {
    @(foreach($root in @("$large\build\classes","$large\build\resources")) {
        if(Test-Path -LiteralPath $root){Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {[pscustomobject]@{Path=$_.FullName;Sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}}}
    })
}
function Save-C17Xml([string]$Destination,[switch]$Validate) {
    New-Item -ItemType Directory -Path $Destination -ErrorAction Stop | Out-Null
    $suites=@()
    $xmlRoot="$large\build\test-results\$Action"
    if(Test-Path -LiteralPath $xmlRoot) {
        foreach($file in Get-ChildItem -LiteralPath $xmlRoot -Filter '*.xml' -File) {
            if($file.LastWriteTimeUtc -lt $started){if($Validate){throw 'Stale XML mixed into current task'};continue}
            [xml]$xml=[IO.File]::ReadAllText($file.FullName)
            $suite=$xml.testsuite;$cases=@($xml.SelectNodes('/testsuite/testcase'))
            if(-not $suite){throw 'Invalid test suite XML'}
            $suites+=[pscustomobject]@{Name=$suite.name;Tests=[int]$suite.tests;Testcases=$cases.Count;Failures=[int]$suite.failures;Errors=[int]$suite.errors;Skipped=[int]$suite.skipped;ProblemNodes=$xml.SelectNodes('//failure|//error|//skipped').Count;RawSha256=(Get-FileHash -LiteralPath $file.FullName).Hash;GeneratedUtc=$file.LastWriteTimeUtc.ToString('o');CaseNames=@($cases | ForEach-Object {$_.classname+'.'+$_.name})}
            foreach($node in $xml.SelectNodes('//system-out|//system-err|//failure|//error')) {
                $node.InnerText=(($node.InnerText -split '\r?\n' | ForEach-Object {if($_ -match '(?i)jdbc:|password|authorization|cookie|WalRecord\[|"eventSeq"|"createdAt"'){ '[sensitive or payload diagnostic omitted]' }else{$_}})-join "`n")
                if($node.HasAttribute('message') -and $node.GetAttribute('message') -match '(?i)jdbc:|password|authorization|cookie|WalRecord\[|"eventSeq"|"createdAt"'){$node.SetAttribute('message','[sensitive or payload diagnostic omitted]')}
            }
            $xml.Save("$Destination\$($file.Name)")
        }
    }
    Write-Phase17CJson "$Destination\suites.json" $suites
    if($Validate -and ($suites.Count -eq 0 -or ($suites | Measure-Object Tests -Sum).Sum -le 0 -or @($suites | Where-Object {$_.Tests -ne $_.Testcases -or $_.Failures -or $_.Errors -or $_.Skipped -or $_.ProblemNodes}).Count)){throw 'Current XML failure/error/skip, missing suite or count mismatch'}
    $suites
}
Test-C17Budget
# 한 호출이 실패하면 자동 다음 task 없음. 후속 최종 task는 현재 성공 receipt를 명시 확인
$previous=@{redisIntegrationTest='test';mysqlIntegrationTest='redisIntegrationTest';bootJar='mysqlIntegrationTest'}
if($Scope -eq 'C3' -and $previous.ContainsKey($Action)) {
    $latest=Get-Content -Raw -LiteralPath "$large\latest-final-attempt.json" | ConvertFrom-Json
    if(-not $latest.Success -or $latest.Action -ne $previous[$Action]){throw 'Latest final attempt failed, unfinished or out of order'}
    $receipt="$large\$($previous[$Action])-receipt.json"
    $prior=Get-Content -Raw -LiteralPath $receipt | ConvertFrom-Json
    if(-not $prior.Success -or $prior.Scope -ne 'C3' -or $prior.PlanHash -ne (Get-FileHash -LiteralPath $planPath).Hash){throw 'Previous final task not verified'}
    $current=Get-C17Inputs
    $old=Get-Content -Raw -LiteralPath $prior.Inputs | ConvertFrom-Json
    if(Compare-Object $old $current -Property Path,Sha256){throw 'Inputs changed since previous final task'}
}
$before=Get-C17Inputs
New-Item -ItemType Directory -Path $run,$temp | Out-Null
Write-Phase17CJson "$run\inputs-before.json" $before
Write-Phase17CJson "$run\command.json" @{RunId=$p.runId;Plan=$planPath;PlanHash=(Get-FileHash -LiteralPath $planPath).Hash;Action=$Action;Case=$Case;Scope=$Scope;Label=$Label;TaskFile=$taskPath;TaskFileHash=(Get-FileHash -LiteralPath $taskPath).Hash;MysqlOwnership=$mysqlProof;MysqlOwnershipHash=$(if($mysqlProof){(Get-FileHash -LiteralPath $mysqlProof).Hash}else{$null});PowerShell=(Get-Process -Id $PID).Path;Account=[Security.Principal.WindowsIdentity]::GetCurrent().Name;Elevated=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator);Secrets='harness-local-secrets process environment; values omitted'}
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
if($identity.Name -ne 'DESKTOP-PM8E0QV\cgt' -or ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Verification process must be cgt non-elevated'}
$names=@('JAVA_HOME','GRADLE_USER_HOME','TEMP','TMP','JAVA_TOOL_OPTIONS','DB_PASSWORD','PIXEL_PLACE_TEST_REDIS_DATABASE','PIXEL_PLACE_BENCH_DB_HOST','PIXEL_PLACE_BENCH_DB_PORT','PIXEL_PLACE_BENCH_DB_USERNAME','PIXEL_PLACE_BENCH_DB_PASSWORD','PIXEL_PLACE_BENCH_REDIS_HOST','PIXEL_PLACE_BENCH_REDIS_PORT','PIXEL_PLACE_BENCH_REDIS_DATABASE','PIXEL_PLACE_BENCH_PRODUCTION_REDIS_DATABASE')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$failure=$null
try {
    $env:JAVA_HOME=Split-Path (Split-Path $p.java -Parent) -Parent;$env:GRADLE_USER_HOME="$large\gradle-home";$env:TEMP=$temp;$env:TMP=$temp
    $env:JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$temp -Djna.tmpdir=$temp"
    $task=if($javaAction){'phase17CVerification'}else{$Action}
    $arguments=@($task,'--rerun-tasks','--no-build-cache','--no-daemon','--console=plain','--project-cache-dir',"$large\project-cache","-Pphase15BuildRoot=$large\build","-Pphase15TempRoot=$temp")
    if(-not $javaAction -and $Action -ne 'bootJar'){$arguments+="-Pphase17CEvidence=$run"}
    if($Action -eq 'test'){$arguments+="-Pphase17SymlinkFixture=$($p.symlinkFixture)"}
    foreach($filter in $Tests){if($filter -notmatch '\A[A-Za-z0-9_.*]+\z'){throw 'Invalid test filter'};$arguments+=@('--tests',$filter)}
    if($Action -eq 'redisIntegrationTest'){$env:PIXEL_PLACE_TEST_REDIS_DATABASE='1'}
    if($javaAction -or $Action -eq 'mysqlIntegrationTest') {
        . "$PSScriptRoot\harness-local-secrets.ps1";Import-HarnessLocalSecrets -Root $project
        if(-not $env:DB_PASSWORD){throw 'Existing secret injection unavailable'}
        $live=Get-Content -Raw -LiteralPath $mysqlProof | ConvertFrom-Json
        $process=Get-Process -Id $live.Pid
        if($process.StartTime.ToUniversalTime().ToString('o') -ne $live.StartUtc -or $live.Uuid -ne $p.mysqlUuid){throw 'Current MySQL ownership required'}
        if($Action -eq 'mysqlIntegrationTest'){$arguments+='-Pphase15MySqlRegression=3307'}
    }
    if($javaAction) {
        $env:PIXEL_PLACE_BENCH_DB_HOST=$p.mysqlHost;$env:PIXEL_PLACE_BENCH_DB_PORT=[string]$p.mysqlPort;$env:PIXEL_PLACE_BENCH_DB_USERNAME='root';$env:PIXEL_PLACE_BENCH_DB_PASSWORD=$env:DB_PASSWORD
        $env:PIXEL_PLACE_BENCH_REDIS_HOST=$p.redisHost;$env:PIXEL_PLACE_BENCH_REDIS_PORT=[string]$p.redisPort;$env:PIXEL_PLACE_BENCH_REDIS_DATABASE=[string]$p.redisDatabase;$env:PIXEL_PLACE_BENCH_PRODUCTION_REDIS_DATABASE=[string]$p.productionRedisDatabase
        $arguments+=@("-Pphase17CPlan=$planPath","-Pphase17CCase=$Case","-Pphase17CAction=$Action","-Pphase17COutput=$app","-Pphase17CTemp=$temp")
        if($Action.StartsWith('recover-')) {
            $initial=Get-Content -Raw -LiteralPath "$large\$Case-initial.json" | ConvertFrom-Json
            $arguments+="-Pphase17CInitial=$($initial.Manifest)"
        } elseif(Test-Path -LiteralPath "$large\$Case-initial.json") {
            throw 'Initial receipt exists; new initial requires a preserved plan revision and unused resources'
        }
    }
    if($Action -eq 'windowsWalIntegrationTest') {
        $nativePlan="$($p.smallRoot)\native-plan-$Label.properties";$nativeRoot="$project\data\$($p.runId)-native-$Label"
        if(-not(Test-Path -LiteralPath $nativePlan)){throw 'New audited native root AND native plan required'}
        $arguments+=@("-Pphase17WalNativeRoot=$nativeRoot","-Pphase17WalNativePlan=$nativePlan")
    }
    $command=ConvertTo-Phase17CCmdArguments (@("$project\gradlew.bat")+$arguments)
    Write-Phase17CJson "$run\invocation.json" @{Executable=$p.cmd;Arguments=$command;Java=$p.java;Task=$task;StartedUtc=[DateTime]::UtcNow.ToString('o');Temp=$temp;Scope=$Scope}
    $started=[DateTime]::UtcNow
    if($Scope -eq 'C3' -and -not $javaAction){Write-Phase17CJson "$large\latest-final-attempt.json" @{Action=$Action;Success=$false;Evidence=$run;StartedUtc=$started.ToString('o')}}
    $state=Invoke-Phase17CProcess -Executable $p.cmd -Arguments $command -WorkingDirectory $project -Evidence "$run\process" -Seconds $p.deadlines.$Action -LogLimit $p.budget.logBytesPerStream
    $stdout=[IO.File]::ReadAllText("$run\process\stdout.log")
    if($stdout -notmatch ('(?m)^> Task :'+[regex]::Escape($task)+'\s*$') -or $stdout -match ('(?m)^> Task :'+[regex]::Escape($task)+' (UP-TO-DATE|FROM-CACHE|NO-SOURCE)')){throw 'Task was not actually executed'}
    if($javaAction) {
        $manifest="$app\manifest.json";$m=Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
        if(-not $m.complete -or -not $m.normalShutdown -or $m.case -ne $Case -or $m.action -ne $Action -or $m.planHash -ne (Get-FileHash -LiteralPath $planPath).Hash.ToLowerInvariant()){throw 'C app result mismatch'}
        if(Get-Process -Id $m.pid -ErrorAction SilentlyContinue){throw 'Application JVM remains alive'}
    } elseif($Action -eq 'bootJar') {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $jars=@(Get-ChildItem -LiteralPath "$large\build\libs" -Filter '*.jar' -File | Where-Object {$_.LastWriteTimeUtc -ge $started -and $_.Name -notlike '*-plain.jar'})
        if($jars.Count -ne 1){throw 'Fresh bootJar missing/ambiguous'}
        $archive=[IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
        try {
            $entries=@($archive.Entries | Select-Object -ExpandProperty FullName)
            foreach($needed in @('BOOT-INF/classes/dev/cgt/pixelplace/wal/infra/FileWalStoragePreparation.class','BOOT-INF/classes/dev/cgt/pixelplace/wal/infra/WindowsWalFileDurability.class','BOOT-INF/classes/dev/cgt/pixelplace/wal/infra/WindowsWalNativeBridge.class')){if($entries -notcontains $needed){throw 'Missing production artifact entry'}}
            if(-not($entries -match 'BOOT-INF/lib/jna-5\.18\.1\.jar') -or ($entries -match '(?i)(dev/cgt/benchmark/|Phase17C|devtools|junit|mockito|/test/)')){throw 'Production jar dependency/contamination gate failed'}
            Write-Phase17CJson "$run\jar.json" @{Path=$jars[0].FullName;Length=$jars[0].Length;Sha256=(Get-FileHash -LiteralPath $jars[0].FullName).Hash;Entries=$entries}
        } finally {$archive.Dispose()}
    } else {
        $suites=@(Save-C17Xml "$run\xml" -Validate)
        Write-Phase17CJson "$run\suites.json" $suites
        $jvms=@(Get-ChildItem -LiteralPath $run -Filter 'test-jvm-*.json' -File)
        if($jvms.Count -eq 0){throw 'Actual test JVM evidence missing'}
        foreach($jvm in $jvms){
            $runtime=Get-Content -Raw -LiteralPath $jvm.FullName | ConvertFrom-Json
            if($runtime.user -ne 'cgt' -or $runtime.java -ne $p.java -or $runtime.javaHash -ne (Get-FileHash -LiteralPath $p.java).Hash.ToLowerInvariant() -or $runtime.temp -ne $temp){throw 'Actual test JVM environment mismatch'}
            if(Get-Process -Id $runtime.pid -ErrorAction SilentlyContinue){throw 'Test JVM remains alive'}
        }
    }
    $after=Get-C17Inputs;Write-Phase17CJson "$run\inputs-after.json" $after
    if(Compare-Object $before $after -Property Path,Sha256){throw 'Execution inputs changed'}
    $classRows=Get-C17Classes
    Write-Phase17CJson "$run\classes.json" $classRows
    Test-C17Budget
    $result=@{Success=$true;Scope=$Scope;Action=$Action;PlanHash=(Get-FileHash -LiteralPath $planPath).Hash;Exit=$state.Exit;Inputs="$run\inputs-after.json";Evidence=$run}
    Write-Phase17CJson "$run\result.json" $result
    # 현재 exit·산출물·입력·예산의 마지막 gate 뒤에만 복구/다음 task용 receipt 게시
    if($javaAction) {
        Write-Phase17CJson "$app\runner-result.json" @{success=$true;exit=$state.Exit;manifestHash=(Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant();process="$run\process\exit.json";result="$run\result.json"}
        if($Action -in @('smoke','prepare-unflushed')){Write-Phase17CJson "$large\$Case-initial.json" @{Manifest=$manifest}}
    }
    if($Scope -eq 'C3' -and -not $javaAction){Write-Phase17CJson "$large\$Action-receipt.json" $result;Write-Phase17CJson "$large\latest-final-attempt.json" $result}
    Write-Output "C_VERIFIED scope=$Scope action=$Action exit=$($state.Exit) evidence=$run"
} catch {
    $failure=$_
    $secondary=@()
    if($started -and -not $javaAction -and $Action -ne 'bootJar') {
        try {Save-C17Xml "$run\failure-xml" | Out-Null}catch{$secondary+=('xml-save-'+$_.Exception.GetType().Name)}
    }
    try {Write-Phase17CJson "$run\failure-inputs-after.json" (Get-C17Inputs)}catch{$secondary+=('input-save-'+$_.Exception.GetType().Name)}
    try {Write-Phase17CJson "$run\failure-classes.json" (Get-C17Classes)}catch{$secondary+=('class-save-'+$_.Exception.GetType().Name)}
    try{Write-Phase17CJson "$run\failure.json" @{Success=$false;Kind=$failure.Exception.GetType().Name;Message=$failure.Exception.Message;Action=$Action;Scope=$Scope;NextTaskAllowed=$false}}catch{Write-Error 'C evidence save failed; original failure retained' -ErrorAction Continue}
    if($secondary.Count){try{Write-Phase17CJson "$run\secondary-failures.json" $secondary}catch{Write-Error 'C secondary failures could not be saved' -ErrorAction Continue}}
} finally {foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
if($failure){throw $failure}
} finally {$runLock.Dispose()}
