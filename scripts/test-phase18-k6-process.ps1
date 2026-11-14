# 실제 k6의 script 오류와 출력 유실을 성공/threshold와 구분하는 무서비스 Windows 경계
param([Parameter(Mandatory=$true)][string]$Output,[Parameter(Mandatory=$true)][string]$K6)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/pixel-place-work.ps1"
. "$PSScriptRoot/phase18-cap-process.ps1"
. "$PSScriptRoot/phase17-c-process.ps1"
. "$PSScriptRoot/phase18-k6-process.ps1"
Assert-PixelPlaceExecutionEnvironment
if(Test-Path -LiteralPath $Output){throw 'Fresh boundary output required'}
New-Item -ItemType Directory -Path $Output|Out-Null
$root=$Output;$clock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+40000)
$captures=[Collections.Generic.List[object]]::new();$results=[Collections.Generic.List[object]]::new();$checks=@()
$env:K6_NO_USAGE_REPORT='true';$env:K6_SUMMARY_MODE='compact';$env:K6_WEB_DASHBOARD='false'
foreach($case in @(
    @{Name='script-error';Code='throw new Error("finite init script fixture"); export default function () {} export const options={vus:1,iterations:1};';Expected='k6 script/config/runtime exit'},
    @{Name='output-missing';Code='export default function () {} export const options={vus:1,iterations:1};';Expected='Required k6 output missing'},
    @{Name='stream-overflow';Code='export default function () { console.log("x".repeat(20000)); } export const options={vus:1,iterations:1};';Expected='Required k6 stream failed'}
)){
    $script="$root/$($case.Name).js";[IO.File]::WriteAllText($script,$case.Code)
    $item=Start-K6 $case.Name $script;$caught=$null
    try{Wait-K6 $item}catch{$caught=$_.Exception.Message}
    if(-not $caught -or (-not $caught.StartsWith($case.Expected) -and -not($case.Name -eq 'stream-overflow' -and $caught -eq 'required_output_failure'))){throw "Unexpected boundary result: $($case.Name): $caught"}
    if($case.Name -eq 'script-error' -and $item.Exit -in @(0,99)){throw 'Script error confused with threshold'}
    if($case.Name -eq 'output-missing' -and $item.Exit -ne 0){throw 'Missing output must reject real exit zero'}
    while(-not(Test-K6Joined $item) -and (Get-CapRemaining $clock) -gt 0){Start-Sleep -Milliseconds 25}
    if(-not(Test-K6Joined $item)){throw 'Fixture descendants unresolved'}
    if($null -eq $item.Exit){$item.Exit=$item.Capture.Process.ExitCode}
    $item.Capture.Release();$item.Released=$true
    $checks+=@{Case=$case.Name;ExpectedFailure=$caught;ActualExit=$item.Exit;Released=$true}
}
$inputData=[pscustomobject]@{limits=@{metricsBytes=10000000L};plan=@{}}
$total=0L;foreach($item in $captures){$item.MetricsLimit=10000000L;if(Test-Path -LiteralPath $item.Metrics){$total+=(Get-Item -LiteralPath $item.Metrics).Length}}
$inputData.limits.metricsBytes=$total
Assert-K6OutputBudget
$inputData.limits.metricsBytes=$total-1
$caught=$null;try{Assert-K6OutputBudget}catch{$caught=$_.Exception.Message}
if($caught -ne 'k6_aggregate_metrics_limit' -or @($captures|Where-Object {-not $_.Released}).Count){throw 'Released phase aggregate boundary not enforced'}
$checks+=@{Case='released-phase-aggregate';ExpectedFailure=$caught;Bytes=$total;EachFileWithinLimit=$true;AllReleased=$true}
$joined=[pscustomobject]@{Started=$true;Capture=[pscustomobject]@{Process=[pscustomobject]@{HasExited=$true};OutTask=[pscustomobject]@{IsCompleted=$true};ErrTask=[pscustomobject]@{IsCompleted=$true}}}
$joined.Capture|Add-Member -MemberType ScriptMethod -Name Members -Value {return @()}
$caught=$null;try{Wait-K6 $joined}catch{$caught=$_.Exception.Message}
if($caught -ne 'k6_aggregate_metrics_limit'){throw 'Final aggregate check bypassed by joined process'}
# 출력 한도 위반이 /stop 전달까지 막지 않도록 기존 bridge 호출을 무서비스 대역으로 대조
$script:stopCalled=$false
function Invoke-WebRequest {param($Uri);$script:stopCalled=$Uri.EndsWith('/stop');return @{Content='{"stopped":true}'}}
$inputData|Add-Member -NotePropertyName bridge -NotePropertyValue 'http://127.0.0.1:1'
try{$null=Invoke-Bridge '/stop';if(-not $script:stopCalled){throw 'Output failure blocked STOP'}}finally{Remove-Item Function:Invoke-WebRequest}
$checks+=@{Case='aggregate-final-and-stop';ExpectedFailure=$caught;StopDeliveredToControlledBoundary=$true;AllActualProcessesReleased=$true}
$inputData.limits.metricsBytes=$total
$runBytes=0L;foreach($file in Get-ChildItem -LiteralPath (Split-Path $root -Parent) -File -Recurse){$runBytes+=$file.Length}
$inputData.plan=@{phase='CAP';bounds=@{outputBytes=$runBytes}}
Assert-K6OutputBudget
$inputData.plan.bounds.outputBytes=$runBytes-1
$caught=$null;try{Assert-K6OutputBudget}catch{$caught=$_.Exception.Message}
if($caught -ne 'k6_total_output_limit'){throw 'Run aggregate boundary not enforced'}
$checks+=@{Case='run-output-boundary';ExpectedFailure=$caught;EqualityAccepted=$true;ActualRunBytes=$runBytes;AllActualProcessesReleased=$true}
$inputData=$null
# 종료코드/stream이 정상이어도 남은 Job 후손은 공동 완료가 아님
$fake=[pscustomobject]@{Started=$true;Capture=[pscustomobject]@{Process=[pscustomobject]@{HasExited=$true};OutTask=[pscustomobject]@{IsCompleted=$true};ErrTask=[pscustomobject]@{IsCompleted=$true}}}
$fake.Capture|Add-Member -MemberType ScriptMethod -Name Members -Value {return @(123)}
if(Test-K6Joined $fake){throw 'Unresolved descendant accepted'}
$checks+=@{Case='unresolved-members';Kind='controlled boundary';Rejected=$true}
# 이번 실행의 새 출력만 변형. 실제 종료/stream/후손 관측 뒤 내용 오류와 정리를 함께 확인
$boundaryRoot=$root
$inputData=[pscustomobject]@{plan=@{cases=@(@{workload=@{writeRate=1;schedule=@{kind='fixed'}}})}}
$env:PHASE18_K6_FORCE_THRESHOLD='0'
$contentCode=@'
import {Trend,Counter} from 'k6/metrics';
const accepted = new Trend('accepted_http_duration', true);
const absent = new Counter('target_not_sent');
export const options={vus:1,iterations:1,thresholds:{accepted_http_duration:['p(95)<=100','p(99)<=250']},summaryTrendStats:['count','avg','min','med','max','p(95)','p(99)']};
export default function(){}
export function handleSummary(data){return {[__ENV.PHASE18_K6_SUMMARY]:JSON.stringify({schema:'phase18-k6-summary-v1',phase:__ENV.PHASE18_K6_PHASE,k6Version:'2.3.0',data})};}
'@
foreach($case in @(
    @{Name='zero-samples';Expected=$null;Exit=0},
    @{Name='empty-metrics';Expected='metrics empty';Exit=0},
    @{Name='long-metrics-line';Expected='metrics line bound';Exit=0},
    @{Name='corrupt-metrics';Expected='metrics JSON';Exit=0},
    @{Name='truncated-metrics';Expected='metrics JSON';Exit=0},
    @{Name='missing-point-value';Expected='metrics point';Exit=0},
    @{Name='missing-metric-definition';Expected='metrics point';Exit=0},
    @{Name='summary-missing-data';Expected='summary structure';Exit=0},
    @{Name='summary-missing-metrics';Expected='summary structure';Exit=0},
    @{Name='summary-value-type';Expected='summary metric value';Exit=0},
    @{Name='summary-missing-thresholds';Expected='phase thresholds missing';Exit=0},
    @{Name='summary-threshold-type';Expected='summary threshold result';Exit=0},
    @{Name='threshold-exit-empty-metrics';Expected='metrics empty';Exit=99}
)){
    $root="$boundaryRoot/$($case.Name)"
    New-Item -ItemType Directory -Path $root | Out-Null
    $code=$contentCode
    if($case.Exit -eq 99){$code=$code.Replace('function(){}','function(){accepted.add(101);}')}
    $script="$root/fixture.js"
    [IO.File]::WriteAllText($script,$code)
    $item=Start-K6 'measurement' $script
    $caught=$null
    try {
        while(-not(Test-K6Joined $item) -and (Get-CapRemaining $clock) -gt 0){Start-Sleep -Milliseconds 25}
        if(-not(Test-K6Joined $item)){throw 'Content fixture descendants unresolved'}
        $summary=Get-Content -LiteralPath $item.Summary -Raw -Encoding UTF8 | ConvertFrom-Json
        switch($case.Name){
            'zero-samples' {
                if($summary.data.metrics.accepted_http_duration.values.count -ne 0 -or
                   $null -ne $summary.data.metrics.target_not_sent -or
                   (Select-String -LiteralPath $item.Metrics -Pattern 'accepted_http_duration' -Quiet)){
                    throw 'Expected actual zero samples and conditional metric omission'
                }
            }
            'empty-metrics' { [IO.File]::WriteAllText($item.Metrics,'') }
            'threshold-exit-empty-metrics' { [IO.File]::WriteAllText($item.Metrics,'') }
            'long-metrics-line' { [IO.File]::AppendAllText($item.Metrics,(' ' * 16385)+"`n") }
            'corrupt-metrics' { [IO.File]::AppendAllText($item.Metrics,"invalid-json`n") }
            'truncated-metrics' {
                $text=[IO.File]::ReadAllText($item.Metrics).TrimEnd()
                [IO.File]::WriteAllText($item.Metrics,$text.Substring(0,$text.Length-1))
            }
            'missing-point-value' {
                $lines=[IO.File]::ReadAllLines($item.Metrics)
                $point=$lines[1] | ConvertFrom-Json
                $point.data.PSObject.Properties.Remove('value')
                $lines[1]=$point | ConvertTo-Json -Depth 10 -Compress
                [IO.File]::WriteAllLines($item.Metrics,$lines)
            }
            'missing-metric-definition' {
                $lines=[IO.File]::ReadAllLines($item.Metrics)
                [IO.File]::WriteAllLines($item.Metrics,$lines[1..($lines.Length-1)])
            }
            'summary-missing-data' { $summary.PSObject.Properties.Remove('data') }
            'summary-missing-metrics' { $summary.data.PSObject.Properties.Remove('metrics') }
            'summary-value-type' { $summary.data.metrics.accepted_http_duration.values.count='0' }
            'summary-missing-thresholds' { $summary.data.metrics.accepted_http_duration.PSObject.Properties.Remove('thresholds') }
            'summary-threshold-type' { $summary.data.metrics.accepted_http_duration.thresholds.'p(95)<=100'.ok='true' }
        }
        if($case.Name.StartsWith('summary-')){
            [IO.File]::WriteAllText($item.Summary,($summary | ConvertTo-Json -Depth 20))
        }
        try { Wait-K6 $item } catch { $caught=$_.Exception.Message }
    } finally {
        # production wrapper와 같은 join/Release 순서. 성공한 정리가 최초 실패를 지우지 않음
        if(-not $item.Released -and (Test-K6Joined $item)){
            $item.Exit=$item.Capture.Process.ExitCode
            $item.Capture.Release()
            $item.Released=$true
        }
    }
    if($item.Exit -ne $case.Exit -or -not $item.Released){throw "Content fixture exit/cleanup: $($case.Name)"}
    if($case.Expected){
        if($caught -cne "Required k6 output content: $($case.Expected)" -or $item.Failure -cne 'required_output_content' -or
           $null -ne $item.Result -or (Test-Path -LiteralPath "$root/k6-measurement-exit.json")){
            throw "Unexpected content rejection: $($case.Name): $caught"
        }
    } elseif($caught -or -not $item.Result.RequiredOutputComplete){throw "Zero sample output rejected: $caught"}
    $checks+=@{Case=$case.Name;ActualExit=$item.Exit;FirstFailure=$item.Failure;ExpectedFailure=$caught;Released=$item.Released;RequiredOutputComplete=($null -ne $item.Result)}
}
$root=$boundaryRoot
# 결과 저장 실패는 Release 뒤에도 성공으로 복원하지 않음
$code=@'
export const options={vus:1,iterations:1,summaryTrendStats:['count','avg','min','med','max','p(95)','p(99)']};
export default function() {}
export function handleSummary(data) { return {[__ENV.PHASE18_K6_SUMMARY]: JSON.stringify({schema:'phase18-k6-summary-v1',phase:__ENV.PHASE18_K6_PHASE,k6Version:'2.3.0',data})}; }
'@
$script="$root/save-failure.js";[IO.File]::WriteAllText($script,$code)
New-Item -ItemType Directory -Path "$root/k6-save-failure-exit.json" | Out-Null
$item=Start-K6 'save-failure' $script;$caught=$null
try{Wait-K6 $item}catch{$caught=$_.Exception.Message}
if(-not $caught -or -not $item.Released -or $item.Exit -ne 0){throw 'Final save failure must preserve release and exit zero'}
$checks+=@{Case='final-save-failure';ActualExit=$item.Exit;Released=$item.Released;Rejected=$true}
# 실제 native 개별/합산 guard: 시험 전용 작은 한도, 자연 종료/join/Release, 원래 실패 보존.
foreach($kind in @('process','combined')){
    $script="$root/memory-$kind.js";[IO.File]::WriteAllText($script,'import {sleep} from "k6"; export const options={vus:1,iterations:1}; export default function(){sleep(0.5);}')
    $k6ProcessMemory=if($kind -eq 'process'){1L}else{1342177280L};$k6CombinedMemory=if($kind -eq 'combined'){1L}else{1879048192L}
    $item=Start-K6 "memory-$kind" $script;$caught=$null
    try{Wait-K6 $item}catch{$caught=$_.Exception.Message}
    $expected=if($kind -eq 'process'){'k6_memory_limit'}else{'k6_aggregate_memory_limit'}
    if($caught -cne $expected){throw "Native guard missed $kind : $caught"}
    while(-not(Test-K6Joined $item) -and (Get-CapRemaining $clock) -gt 0){Start-Sleep -Milliseconds 25}
    if(-not(Test-K6Joined $item)){throw 'Memory fixture owner unresolved'}
    $item.Exit=$item.Capture.Process.ExitCode;$item.Capture.Release();$item.Released=$true
    $checks+=@{Case="native-$kind-guard";FirstFailure=$caught;ActualExit=$item.Exit;Joined=$true;Released=$true;PeakPrivateBytes=$item.PeakPrivateBytes;PeakWorkingSet=$item.PeakWorkingSet;TestOnlyLimitBytes=1}
}
$k6ProcessMemory=1342177280L;$k6CombinedMemory=1879048192L
# 짧은 최초 만료 뒤 자연 종료 관측. 새 clock 발급이나 작업 재개 없이 실패 유지
$clock=New-CapClock ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+500)
$script="$root/expiry.js";[IO.File]::WriteAllText($script,'import {sleep} from "k6"; export const options={vus:1,iterations:1}; export default function(){sleep(1.5);}')
$item=Start-K6 'expiry' $script;$caught=$null
try{Wait-K6 $item}catch{$caught=$_.Exception.Message}
if($caught -ne 'original_expiry' -or $item.Failure -ne 'original_expiry'){throw 'Original expiry not preserved'}
$observed=$item.Capture.Process.WaitForExit(5000)
$item.Capture.OutTask.Wait(1000)|Out-Null;$item.Capture.ErrTask.Wait(1000)|Out-Null
if(-not $observed -or -not(Test-K6Joined $item)){throw 'Finite expired fixture ownership unresolved'}
$item.Capture.Release();$item.Released=$true
$checks+=@{Case='original-expiry';OriginalExpires=$clock.Expires;ActualLateExit=$item.Capture.Process.ExitCode;FirstFailure=$item.Failure;Released=$true;ExpectedFailure=$true}
Save-CapJson "$root/results.json" @{Complete=$true;Expires=$clock.Expires;Checks=$checks;Services=0;FinalClassification='NOT_EVALUATED'}
