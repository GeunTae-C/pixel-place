param([Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\phase17-c-process.ps1"
if(Test-Path -LiteralPath $Output){throw 'Fresh fixture output required'}
New-Item -ItemType Directory -Path $Output | Out-Null
$cmd='C:\Windows\System32\cmd.exe';$ps=(Get-Process -Id $PID).Path
[IO.File]::WriteAllText("$Output\not-executable.bin",'fixture')
$cases=@(
    @{Name='missing';Exe="$Output\absent.exe";Args='';Exit=$null;Success=$false;Seconds=5},
    @{Name='unstartable';Exe="$Output\not-executable.bin";Args='';Exit=$null;Success=$false;Seconds=5},
    @{Name='exit7';Exe=$cmd;Args='/d /c exit 7';Exit=7;Success=$false;Seconds=5},
    @{Name='exit0';Exe=$cmd;Args='/d /c exit 0';Exit=0;Success=$true;Seconds=5},
    @{Name='log-failure';Exe=$cmd;Args='/d /c echo fixture';Exit=0;Success=$false;Seconds=5},
    @{Name='late-exit0';Exe=$ps;Args='-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 3; exit 0"';Exit=0;Success=$false;Seconds=1}
)
$results=@()
foreach($case in $cases){
    $caught=$false
    try {
        $invoke=@{Executable=$case.Exe;Arguments=$case.Args;WorkingDirectory=$Output;Evidence="$Output\$($case.Name)";Seconds=$case.Seconds;LogFailureFixture=($case.Name -eq 'log-failure')}
        Invoke-Phase17CProcess @invoke | Out-Null
        [IO.File]::WriteAllText("$Output\$($case.Name)\next-task.marker",'allowed')
    } catch {$caught=$true}
    $state=Get-Content -Raw -LiteralPath "$Output\$($case.Name)\exit.json" | ConvertFrom-Json
    $marker=Test-Path -LiteralPath "$Output\$($case.Name)\next-task.marker"
    if($state.Exit -ne $case.Exit -or $state.Success -ne $case.Success -or $marker -ne $case.Success -or $caught -eq $case.Success){throw "Fixture assertion failed: $($case.Name)"}
    if($case.Name -eq 'late-exit0' -and (-not $state.TimedOut -or $state.Resources -ne 'exited-streams-joined')){throw 'Timeout overwritten or process unresolved'}
    $results+=@{Name=$case.Name;ExpectedExit=$case.Exit;ActualExit=$state.Exit;TopLevelFailure=$caught;NextMarker=$marker;Resources=$state.Resources}
}
foreach($bad in @('C:temp','\temp','/temp','temp','\\server\share','\\?\C:\temp')){
    $rejected=$false;try {Resolve-Phase17CPlannedPath $bad $bad | Out-Null}catch{$rejected=$true}
    if(-not $rejected){throw 'Incomplete path accepted'}
}
$mismatch=$false;try {Resolve-Phase17CPlannedPath 'C:\one' 'C:\two' | Out-Null}catch{$mismatch=$true}
if(-not $mismatch){throw 'Plan mismatch accepted'}
# 실제 wrapper의 XML 수집 함수만 로드하여 합성 입력으로 실패 증거 보존·판정 경계 확인
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\verify-phase17-c.ps1",[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Wrapper parser errors'}
$definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Save-C17Xml'},$true)
if(-not $definition){throw 'XML collector missing'}
. ([scriptblock]::Create($definition.Extent.Text))
$large="$Output\xml-fixtures";$Action='test';$started=[DateTime]::UtcNow.AddMinutes(-1)
$xmlRoot="$large\build\test-results\test"
New-Item -ItemType Directory -Path $xmlRoot | Out-Null
$xmlCases=@(
    @{Name='valid';Body='<testcase classname="C" name="ok"/>';Tests=1;Pass=$true},
    @{Name='hidden-failure';Body='<testcase classname="C" name="bad"><failure message="password=fixture">password=fixture</failure></testcase>';Tests=1;Pass=$false},
    @{Name='hidden-skip';Body='<testcase classname="C" name="skip"><skipped/></testcase>';Tests=1;Pass=$false},
    @{Name='empty';Body='';Tests=0;Pass=$false},
    @{Name='count';Body='<testcase classname="C" name="ok"/>';Tests=2;Pass=$false}
)
foreach($fixture in $xmlCases){
    [IO.File]::WriteAllText("$xmlRoot\TEST-C.xml",('<testsuite name="C" tests="'+$fixture.Tests+'" failures="0" errors="0" skipped="0">'+$fixture.Body+'</testsuite>'))
    $failed=$false;try{Save-C17Xml "$large\$($fixture.Name)" -Validate | Out-Null}catch{$failed=$true}
    if($failed -eq $fixture.Pass){throw ('XML verdict mismatch: '+$fixture.Name)}
    if(-not(Test-Path "$large\$($fixture.Name)\TEST-C.xml")){throw 'Failed XML was not preserved'}
    if($fixture.Name -eq 'hidden-failure' -and [IO.File]::ReadAllText("$large\hidden-failure\TEST-C.xml").Contains('password=fixture')){throw 'Diagnostic redaction failed'}
}
(Get-Item -LiteralPath "$xmlRoot\TEST-C.xml").LastWriteTimeUtc=$started.AddMinutes(-1)
$staleRejected=$false;try{Save-C17Xml "$large\stale" -Validate | Out-Null}catch{$staleRejected=$true}
if(-not $staleRejected){throw 'Stale XML accepted'}
Write-Phase17CJson "$Output\results.json" @{Complete=$true;Cases=$results;PathRejections=7;XmlCases=6;KillInvoked=$false}
Write-Output 'C-V02 process fixtures passed: 6 process cases, 7 path rejections, 6 XML evidence cases'
