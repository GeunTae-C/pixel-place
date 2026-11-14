param(
    [string]$SpecPath = '명령문/015-A_통합_측정입력.json',
    [string]$SessionId = 'a20260917',
    [string]$StorageRoot = 'E:\pixel-place-phase15\phase15-a-20260918',
    [int[]]$CaseIndex,
    [int[]]$Repetition,
    [switch]$Smoke,
    [switch]$Calibration,
    [switch]$LegacyStorageLedger,
    [ValidateSet('on','off')][string]$Instrumentation = 'on',
    [ValidateSet('A','C-3','C-4')][string]$Stage = 'A',
    [ValidateSet('single','group')][string]$WriteMode = 'single',
    [string]$MatrixEntry,
    [string]$MatrixPath = 'scripts/phase15-c-matrix.json'
)
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$storage = [IO.Path]::GetFullPath($StorageRoot)
if ($storage -cnotmatch '^E:\\pixel-place-phase15\\[a-z0-9_-]+$' -or -not (Test-Path -LiteralPath (Join-Path $storage 'storage-root.json'))) { throw 'Prepared E: storage root required' }
$env:PIXEL_PLACE_BENCH_STORAGE_ROOT = $storage
$env:PIXEL_PLACE_BENCH_DB_HOST = '127.0.0.1'
$env:PIXEL_PLACE_BENCH_DB_PORT = '3307'
$env:TEMP = Join-Path $storage 'tmp'; $env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $env:TEMP.Replace('\','/')
if ((git rev-parse --is-inside-work-tree) -ne 'true') { throw 'Original Git worktree required' }
if ($SessionId -cnotmatch '^[a-z0-9_]{1,46}$') { throw 'Invalid measurement session id' }
if ($Smoke -and $Calibration) { throw 'Choose smoke or calibration' }
if ($Stage -ne 'A') {
    if ($LegacyStorageLedger -or $Calibration) { throw 'C does not reuse A calibration/legacy mutation paths' }
    if (-not $PSBoundParameters.ContainsKey('SpecPath')) { $SpecPath = 'scripts/phase15-c4-input.json' }
    $budget = Get-Content -Raw -Encoding UTF8 -LiteralPath 'scripts/phase15-c-budget.json' | ConvertFrom-Json
    . (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
    Assert-Phase15CBudget $budget
    $charge = (Save-Phase15CCharge).chargedBytes
    $reserve = if ($Smoke) { 320MB } else { 2534044672L }
    if ($charge + $reserve -gt $budget.maximumBytes -or ([IO.DriveInfo]::new('E:')).AvailableFreeSpace - $reserve -lt 5GB) { throw 'C budget insufficient; approval required before this execution' }
    $matrix = Get-Content -Raw -Encoding UTF8 -LiteralPath $MatrixPath | ConvertFrom-Json
    if ($Stage -eq 'C-3') {
        if (-not $Smoke -or $Instrumentation -ne 'on') { throw 'C-3 permits only the two fixed on smokes' }
        $MatrixEntry = 'smoke-' + $WriteMode
    } else {
        if ($Smoke -or -not $MatrixEntry) { throw 'C-4 requires an explicit fixed matrix entry' }
        $entry = @($matrix.entries | Where-Object id -CEQ $MatrixEntry)
        if ($entry.Count -ne 1) { throw 'Missing or duplicate matrix entry' }
        $WriteMode = $entry[0].mode; $CaseIndex = @($entry[0].caseIndex); $Repetition = @($entry[0].repetition)
        $Instrumentation = if ($entry[0].instrumentation) { 'on' } else { 'off' }
    }
} elseif ($WriteMode -ne 'single') { throw 'A baseline is single only' }

# 기존 비밀 파서만 재사용. 자동 작업 하네스 실행이나 설정 변경은 하지 않음
. (Join-Path $PSScriptRoot 'harness-local-secrets.ps1')
Import-HarnessLocalSecrets -Root $root
if (-not $env:PIXEL_PLACE_BENCH_DB_USERNAME) {
    $application = Get-Content -LiteralPath (Join-Path $root 'src/main/resources/application.yml') -Raw -Encoding UTF8
    $matches = [regex]::Matches($application, '(?m)^    username: ([A-Za-z0-9_]+)\s*$')
    if ($matches.Count -ne 1) { throw 'Explicit benchmark DB username required; source configuration could not be mapped safely' }
    $env:PIXEL_PLACE_BENCH_DB_USERNAME = $matches[0].Groups[1].Value
}
if (-not $env:PIXEL_PLACE_BENCH_DB_PASSWORD) { $env:PIXEL_PLACE_BENCH_DB_PASSWORD = $env:DB_PASSWORD }
if (-not $env:PIXEL_PLACE_BENCH_DB_PASSWORD) { throw 'Safe DB credential injection missing' }
# 사용자 지정 운영/회귀/benchmark index. 실제 standalone/기존 흔적은 Java preflight에서 재검증
$env:PIXEL_PLACE_BENCH_PRODUCTION_REDIS_DATABASE = '0'
$env:PIXEL_PLACE_BENCH_REDIS_DATABASE = '2'
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
$env:GRADLE_USER_HOME = Join-Path $storage 'gradle-home'
$gradlePaths = @('--project-cache-dir',(Join-Path $storage 'project-cache'),('-Pphase15BuildRoot='+(Join-Path $storage 'build')),('-Pphase15TempRoot='+$env:TEMP))
$inputSpec = Get-Content -LiteralPath $SpecPath -Raw -Encoding UTF8 | ConvertFrom-Json
$invocationId = 'invoke_' + [DateTime]::UtcNow.ToString('yyyyMMdd_HHmmss_fff')
$invocation = Join-Path $storage ('agent-runs/phase15-' + $Stage + '-' + $invocationId)
if (Test-Path -LiteralPath $invocation) { throw 'Existing invocation evidence cannot be reused' }
New-Item -ItemType Directory -Path $invocation | Out-Null

if ($Smoke -or $Calibration) {
    $inputSpec.purpose = 'exploratory'
    $inputSpec.targetAcceptedWritesPerSecond = $null
    $inputSpec.targetP95Millis = $null
    $inputSpec.targetP99Millis = $null
    $inputSpec.maxSystemErrorRate = $null
    $inputSpec.repetitions = 1
    if ($Smoke) {
        $inputSpec.warmupSeconds = 2; $inputSpec.measurementSeconds = 5
        $inputSpec.cases = @([pscustomobject]@{role='probe';offeredRate=2;webSocketSessions=2;pattern='spread-tiles'})
    } else {
        $inputSpec.warmupSeconds = 5; $inputSpec.measurementSeconds = 15
        $inputSpec.cases = @([pscustomobject]@{role='probe';offeredRate=25;webSocketSessions=0;pattern='spread-tiles'})
    }
    $SpecPath = Join-Path $invocation 'input.json'
    [IO.File]::WriteAllText($SpecPath, ($inputSpec | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
}
if ($null -eq $CaseIndex -or $CaseIndex.Count -eq 0) { $CaseIndex = @(0..($inputSpec.cases.Count - 1)) }
if ($null -eq $Repetition -or $Repetition.Count -eq 0) { $Repetition = @(0..($inputSpec.repetitions - 1)) }
if ($Stage -eq 'C-3' -and ($CaseIndex.Count -ne 1 -or $CaseIndex[0] -ne 0 -or $Repetition.Count -ne 1 -or $Repetition[0] -ne 0)) { throw 'C-3 smoke is exactly one case/repetition per mode' }
$records = @()
$counters = @('\LogicalDisk(E:)\Avg. Disk sec/Transfer','\LogicalDisk(E:)\Current Disk Queue Length','\LogicalDisk(E:)\Disk Transfers/sec','\LogicalDisk(E:)\Disk Write Bytes/sec')
$diskAvailable = $false
try { Get-Counter -Counter $counters -MaxSamples 1 -ErrorAction Stop | Out-Null; $diskAvailable = $true } catch { }
[pscustomobject]@{Session=$SessionId;Stage=$Stage;WriteMode=$WriteMode;MatrixEntry=$MatrixEntry;Spec=$SpecPath;Cases=$CaseIndex;Repetitions=$Repetition;Instrumentation=$Instrumentation;StorageRoot=$storage;DatabasePort=3307;DiskAvailable=$diskAvailable;DiskCounters=$counters;DiskScope='E: volume, host-wide; other host work can contribute'} |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $invocation 'invocation.json') -Encoding UTF8
if ($LegacyStorageLedger) {
    $ledgerArgs = @('phase15Benchmark','--args=--mode legacy-storage-ledger','--offline','--no-daemon','--console=plain') + $gradlePaths
    & (Join-Path $root 'gradlew.bat') @ledgerArgs | Tee-Object -FilePath (Join-Path $invocation 'ledger.log')
    $ledgerExit = $LASTEXITCODE
    $ledgerExit | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $invocation 'ledger-exit.txt')
    exit $ledgerExit
}

foreach ($case in $CaseIndex) {
    foreach ($repeat in $Repetition) {
        $runId = 'r' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff') + '_' + [Guid]::NewGuid().ToString('N').Substring(0,6)
        $trialDir = Join-Path $storage ('agent-runs/phase15-' + $Stage + '-' + $runId)
        $diskJob = $null
        if ($diskAvailable) {
            $diskJob = Start-Job -ArgumentList (,$counters) -ScriptBlock {
                param($paths)
                Get-Counter -Counter $paths -SampleInterval 1 -MaxSamples 10000 -ErrorAction Stop | ForEach-Object {
                    [pscustomobject]@{Utc=$_.Timestamp.ToUniversalTime().ToString('o');Samples=@($_.CounterSamples | ForEach-Object {[pscustomobject]@{Counter=$_.Path;Value=$_.CookedValue;Status=$_.Status}})}
                }
            }
        }
        $selection = ' --stage ' + $Stage + ' --write-mode ' + $WriteMode
        if ($Stage -ne 'A') { $selection += ' --matrix "' + $MatrixPath + '" --matrix-entry ' + $MatrixEntry + ' --trial-kind ' + $(if ($Smoke) { 'smoke' } else { 'comparison' }) }
        $arguments = @('phase15Benchmark',('--args=--spec "' + $SpecPath + '" --case-index ' + $case + ' --repetition ' + $repeat + ' --mode trial --run-id ' + $runId + ' --session ' + $SessionId + ' --instrumentation ' + $Instrumentation + $selection), '--offline','--no-daemon','--console=plain') + $gradlePaths
        $arguments | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $invocation ($runId + '-command.json')) -Encoding UTF8
        [DateTime]::UtcNow.ToString('o') | Set-Content -LiteralPath (Join-Path $invocation ($runId + '-started.txt')) -Encoding UTF8
        $ErrorActionPreference = 'Continue'
        & (Join-Path $root 'gradlew.bat') @arguments 2>&1 | ForEach-Object {
            $line = [string]$_
            if ($line -match '(?i)jdbc:|password|authorization:|set-cookie:|"eventSeq"|"createdAt"') { '[sensitive or payload line omitted]' } else { $line }
        } | Tee-Object -FilePath (Join-Path $invocation ($runId + '-trial.log'))
        $trialExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        $trialExit | Set-Content -LiteralPath (Join-Path $invocation ($runId + '-trial-exit.txt')) -Encoding UTF8
        if ($diskJob) {
            $diskState = [string]$diskJob.State
            Stop-Job $diskJob
            $diskSamples = @(Receive-Job $diskJob -ErrorAction SilentlyContinue)
            Remove-Job $diskJob
            $diskSamples | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $invocation ($runId + '-disk.json')) -Encoding UTF8
            [pscustomobject]@{StateBeforeStop=$diskState;SampleCount=$diskSamples.Count;Incomplete=($diskState -eq 'Failed' -or $diskSamples.Count -eq 0)} |
                ConvertTo-Json | Set-Content -LiteralPath (Join-Path $invocation ($runId + '-disk-status.json')) -Encoding UTF8
        }
        if ($Stage -ne 'A') { Save-Phase15CCharge | Out-Null }
        if ($trialExit -ne 0) { throw "Trial incomplete: $runId exit=$trialExit; evidence retained; next trial not started" }
        $recovery = @('phase15Benchmark',('--args=--mode recovery-check --manifest "' + (Join-Path $trialDir 'manifest.json') + '"'), '--offline','--no-daemon','--console=plain') + $gradlePaths
        $recovery | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $invocation ($runId + '-recovery-command.json')) -Encoding UTF8
        $ErrorActionPreference = 'Continue'
        & (Join-Path $root 'gradlew.bat') @recovery 2>&1 | ForEach-Object {
            $line = [string]$_
            if ($line -match '(?i)jdbc:|password|authorization:|set-cookie:|"eventSeq"|"createdAt"') { '[sensitive or payload line omitted]' } else { $line }
        } | Tee-Object -FilePath (Join-Path $invocation ($runId + '-recovery.log'))
        $recoveryExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        $recoveryExit | Set-Content -LiteralPath (Join-Path $invocation ($runId + '-recovery-exit.txt')) -Encoding UTF8
        if ($Stage -ne 'A') { Save-Phase15CCharge | Out-Null }
        if ($recoveryExit -ne 0) { throw "Recovery incomplete: $runId exit=$recoveryExit; evidence retained; next trial not started" }
        if ($Stage -ne 'A') {
            $afterCharge = (Save-Phase15CCharge).chargedBytes
            $afterFree = ([IO.DriveInfo]::new('E:')).AvailableFreeSpace
            [pscustomobject]@{ChargedBytes=$afterCharge;MaximumBytes=$budget.maximumBytes;FreeBytes=$afterFree;MinimumFreeBytes=5GB} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $invocation ($runId + '-budget-after.json'))
            if ($afterCharge -gt $budget.maximumBytes -or $afterFree -lt 5GB) { throw 'C post-execution budget/free-space violation' }
        }
        $records += [pscustomobject]@{Trial=$runId;Case=$case;Repetition=$repeat;TrialExit=$trialExit;RecoveryExit=$recoveryExit;Evidence=$trialDir}
        Write-Output "PHASE15_CASE_COMPLETE case=$case repetition=$repeat trial=$runId"
    }
}
$records | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $invocation 'completed-trials.json') -Encoding UTF8
Write-Output "PHASE15_INVOCATION_COMPLETE evidence=$invocation"
