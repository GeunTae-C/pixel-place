function Observe-K6Native {
    if($null -eq $script:k6PeakResident){$script:k6PeakResident=0L;$script:k6PeakPrivate=0L}
    $resident=0L;$private=0L;$series=@();$breach=$null
    foreach($owned in $captures){
        if($owned.Released){continue}
        foreach($stream in @($owned.Capture.Out,$owned.Capture.Err)){if($stream.Overflow -or $stream.Failure){$owned.Failure='required_output_failure';throw $owned.Failure}}
        foreach($task in @($owned.Capture.OutTask,$owned.Capture.ErrTask)){if($task.IsFaulted -or $task.IsCanceled){$owned.Failure='required_stream_failure';throw $owned.Failure}}
        if(-not $owned.Capture.Process.HasExited){
            $owned.Capture.Process.Refresh();$working=$owned.Capture.Process.WorkingSet64;$committed=$owned.Capture.Process.PrivateMemorySize64
            $resident+=$working;$private+=$committed;$owned.PeakWorkingSet=[Math]::Max($owned.PeakWorkingSet,$working);$owned.PeakPrivateBytes=[Math]::Max($owned.PeakPrivateBytes,$committed)
            $series+=@{Phase=$owned.Phase;Pid=$owned.Capture.Process.Id;WorkingSetBytes=$working;PrivateBytes=$committed;CpuMillis=$owned.Capture.Process.TotalProcessorTime.TotalMilliseconds}
            if([Math]::Max($working,$committed) -gt $(if($k6ProcessMemory){$k6ProcessMemory}else{536870912L})){$owned.Failure='k6_memory_limit';$breach=$owned.Failure}
        }
        if((Test-Path -LiteralPath $owned.Metrics) -and (Get-Item -LiteralPath $owned.Metrics).Length -gt $owned.MetricsLimit){$owned.Failure='metrics_output_limit';throw $owned.Failure}
    }
    $script:k6PeakResident=[Math]::Max($script:k6PeakResident,$resident);$script:k6PeakPrivate=[Math]::Max($script:k6PeakPrivate,$private)
    if([Math]::Max($resident,$private) -gt $(if($k6CombinedMemory){$k6CombinedMemory}else{536870912L})){$breach='k6_aggregate_memory_limit'}
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if($script:k6NativeWriter -and ($breach -or $now-$script:k6NativeLast -ge 250)){
        if(++$script:k6NativeRows -gt $script:k6NativeMaxRows){throw 'bounded native observation count'}
        $row=@{epochMillis=$now;processes=$series;combinedPrivateBytes=$private;combinedWorkingSetBytes=$resident;failure=$breach}|ConvertTo-Json -Depth 5 -Compress
        $script:k6NativeBytes+=[Text.Encoding]::UTF8.GetByteCount($row)+1
        if($script:k6NativeBytes -gt 134217728L){throw 'bounded native observation bytes'}
        $script:k6NativeWriter.WriteLine($row);$script:k6NativeWriter.Flush();$script:k6NativeLast=$now
    }
    if($breach){throw $breach}
}

# k6 전용 결과·후손·출력 경계. 호출 runner의 최초 clock과 registry 사용
function Invoke-Bridge([string]$Route,$Value=@{}) {
    if((Get-CapRemaining $clock) -le 0 -and $Route -notin @('/stop','/close-ws')){throw 'Original k6 expiry'}
    if($Route -ne '/stop'){Assert-K6OutputBudget;if($inputData.plan.schemaVersion -eq 4){Observe-K6Native}}
    $waitMillis=5000
    if($Route -eq '/finish'){$waitMillis=[Math]::Min((Get-CapRemaining $clock),$inputData.plan.cases[0].workload.drainMillis)}
    $timeoutSeconds=[Math]::Max(1,[Math]::Ceiling($waitMillis/1000))
    if($inputData.plan.schemaVersion -eq 4){
        # 긴 서버 소진 응답 대기 중에도 동일 native guard를 계속 검사한다.
        if(-not $script:k6BridgeClient){Add-Type -AssemblyName System.Net.Http;$handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false;$script:k6BridgeClient=[Net.Http.HttpClient]::new($handler);$script:k6BridgeClient.Timeout=[Threading.Timeout]::InfiniteTimeSpan}
        $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post,($inputData.bridge+$Route))
        $request.Headers.Add('X-Bridge-Key',$env:PIXEL_PLACE_BENCH_JWT_KEY)
        $request.Content=[Net.Http.StringContent]::new(($Value|ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')
        $cancel=[Threading.CancellationTokenSource]::new();$response=$null;$pending=$null
        $cancel.CancelAfter([int][Math]::Max(1,[Math]::Min($waitMillis,(Get-CapRemaining $clock))))
        try{
            $pending=$script:k6BridgeClient.SendAsync($request,$cancel.Token)
            while(-not $pending.IsCompleted){if($Route -ne '/stop'){Observe-K6Native};Start-Sleep -Milliseconds 25}
            $response=$pending.GetAwaiter().GetResult()
            if([int]$response.StatusCode -ne 200){throw 'required bridge operation failed'}
            return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json)
        }finally{
            $cancel.Cancel()
            if($pending -and -not $pending.IsCompleted){try{$null=$pending.GetAwaiter().GetResult()}catch{}}
            if($response){$response.Dispose()};$request.Dispose();$cancel.Dispose()
        }
    }
    $response=Invoke-WebRequest -UseBasicParsing -Uri ($inputData.bridge+$Route) -Method Post -Headers @{'X-Bridge-Key'=$env:PIXEL_PLACE_BENCH_JWT_KEY} -ContentType 'application/json' -Body ($Value|ConvertTo-Json -Compress) -TimeoutSec $timeoutSeconds -MaximumRedirection 0
    return ($response.Content|ConvertFrom-Json)
}
# CAP-1만 더 큰 유한 출력 예약 사용. 스트리밍 parser의 메모리 한도와 별개
function Assert-K6MetricsLimit($Value) {
    $maximum=12884901888L
    if($inputData.plan.phase -ceq 'CAP' -and $inputData.plan.stage -ceq 'CAP-1' -and $inputData.plan.bounds.samples -gt 10000){$maximum=64424509440L}
    if(($Value -isnot [long] -and $Value -isnot [int]) -or $Value -le 0 -or $Value -gt $maximum){throw 'Bounded k6 metrics reservation required'}
}
function Assert-K6OutputBudget {
    $limit=$inputData.limits.metricsBytes
    if($null -eq $limit){return} # 구형 격리 fixture에는 공유 예약이 없음
    Assert-K6MetricsLimit $limit
    $total=0L
    foreach($owned in $captures){
        if(Test-Path -LiteralPath $owned.Metrics){
            $size=(Get-Item -LiteralPath $owned.Metrics).Length
            if($size -gt $owned.MetricsLimit){if(-not $owned.Failure){$owned.Failure='metrics_output_limit'};throw 'metrics_output_limit'}
            $total+=$size
        }
    }
    if($total -gt $limit){throw 'k6_aggregate_metrics_limit'}
    $runLimit=$inputData.plan.bounds.outputBytes
    if($null -ne $runLimit -and $inputData.plan.phase -ceq 'CAP'){
        $runRoot=Split-Path $root -Parent
        $bytes=0L
        foreach($file in Get-ChildItem -LiteralPath $runRoot -File -Recurse){$bytes+=$file.Length}
        if($bytes -gt $runLimit){throw 'k6_total_output_limit'}
    }
}
function Start-K6([string]$Phase,[string]$ScriptPath="$PSScriptRoot/phase18-k6.js") {
    if((Get-CapRemaining $clock) -le 0){throw 'Original k6 expiry before process'}
    Assert-K6OutputBudget
    $summary="$root/k6-$Phase-summary.json";$metrics="$root/k6-$Phase-metrics.json"
    foreach($path in @($summary,$metrics)){if(Test-Path -LiteralPath $path){throw 'New k6 evidence required'}}
    $env:PHASE18_K6_PHASE=$Phase;$env:PHASE18_K6_SUMMARY=$summary
    Assert-PixelPlaceWorkPath $ScriptPath
    if(-not(Test-Path -LiteralPath $ScriptPath -PathType Leaf)){throw 'Explicit local k6 script required'}
    $config="$root/k6-$Phase-options.json";Save-CapJson $config @{}
    $arguments=@('run','--config',$config,'--no-usage-report','--no-color','--log-format=json','--address','127.0.0.1:0','--out',"json=$metrics",$ScriptPath)
    $metricsLimit=33554432L
    if($null -ne $inputData.limits.metricsBytes){
        $metricsLimit=$inputData.limits.metricsBytes
        Assert-K6MetricsLimit $metricsLimit
    }
    $capture=[Phase18Cap.JobCapture]::new()
    $item=[pscustomobject]@{Phase=$Phase;Capture=$capture;Summary=$summary;Metrics=$metrics;MetricsLimit=$metricsLimit;MetricsDigest=$null;MetricsValidatedBytes=$null;PeakWorkingSet=0L;PeakPrivateBytes=0L;Released=$false;Started=$false;Exit=$null;Failure=$null;Result=$null}
    $captures.Add($item)
    $argv=@($arguments|ForEach-Object {[Phase18Cap.JobCapture]::Quote($_)}) -join ' '
    try{
        $capture.Start($K6,$argv,(Split-Path $PSScriptRoot -Parent),"$root/k6-$Phase.stdout.log","$root/k6-$Phase.stderr.log",1048576)
        $item.Started=$true
    }catch{
        # 시작 API 후반 실패도 이미 생긴 실제 Process/Job의 소유를 잃으면 안 됨
        if($null -ne $capture.Process){$item.Started=$true}
        $item.Failure='k6_start_failure';throw
    }
    return $item
}
function Test-K6Joined($Item) {
    return $Item.Started -and $Item.Capture.Process.HasExited -and $Item.Capture.OutTask.IsCompleted -and $Item.Capture.ErrTask.IsCompleted -and @($Item.Capture.Members()).Count -eq 0
}
function Test-K6OutputNumber($Value) {
    # null/문자열/bool의 숫자 강제 변환으로 누락을 0처럼 수용하지 않음
    return ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal]) -and
        -not [double]::IsNaN($Value) -and -not [double]::IsInfinity($Value)
}
function Read-K6RequiredOutput($Item) {
    # 고정 k6 2.3.0의 읽기 계약만 검사. 집계 재계산/정합성/최종 판정은 수정 3 책임
    try { $summary=Get-Content -LiteralPath $Item.Summary -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Required k6 output content: summary JSON' }
    if($summary -isnot [pscustomobject] -or $summary.schema -cne 'phase18-k6-summary-v1' -or
       $summary.phase -cne $Item.Phase -or $summary.k6Version -cne '2.3.0'){
        throw 'Required k6 output content: summary identity'
    }
    $data=$summary.data
    if($data -isnot [pscustomobject] -or $data.state -isnot [pscustomobject] -or
       -not(Test-K6OutputNumber $data.state.testRunDurationMs) -or $data.state.testRunDurationMs -lt 0 -or
       $data.metrics -isnot [pscustomobject] -or @($data.metrics.PSObject.Properties).Count -eq 0){
        throw 'Required k6 output content: summary structure'
    }
    foreach($property in $data.metrics.PSObject.Properties){
        $metric=$property.Value
        if($metric -isnot [pscustomobject] -or $metric.type -cnotin @('counter','gauge','rate','trend') -or
           $metric.contains -cnotin @('default','time','data') -or $metric.values -isnot [pscustomobject]){
            throw 'Required k6 output content: summary metric structure'
        }
        $fields=switch -CaseSensitive ($metric.type) {
            'counter' { @('count','rate') }
            'gauge' { @('value','min','max') }
            'rate' { @('rate','passes','fails') }
            'trend' { @('count','avg','min','med','max','p(95)','p(99)') }
        }
        foreach($field in $fields){
            if(-not(Test-K6OutputNumber $metric.values.$field)){throw 'Required k6 output content: summary metric value'}
        }
        if($null -ne $metric.PSObject.Properties['thresholds']){
            if($metric.thresholds -isnot [pscustomobject]){throw 'Required k6 output content: summary thresholds'}
            foreach($threshold in $metric.thresholds.PSObject.Properties){
                if($threshold.Value -isnot [pscustomobject] -or $threshold.Value.ok -isnot [bool]){
                    throw 'Required k6 output content: summary threshold result'
                }
            }
        }
    }
    # phase18-k6.js가 본측정 쓰기에 설치하는 threshold. 0표본도 summary에는 결과가 존재
    $workload=$inputData.plan.cases[0].workload
    $writes=$workload.writeRate -gt 0 -or $workload.schedule.kind -cin @('users','smoke-s2')
    if($Item.Phase -ceq 'measurement' -and $writes){
        $accepted=$data.metrics.accepted_http_duration
        $p95=if($env:PHASE18_K6_FORCE_THRESHOLD -ceq '1'){'p(95)<0'}else{'p(95)<=100'}
        if($accepted.type -cne 'trend' -or $accepted.contains -cne 'time' -or $accepted.thresholds -isnot [pscustomobject] -or
           $null -eq $accepted.thresholds.PSObject.Properties[$p95] -or
           $null -eq $accepted.thresholds.PSObject.Properties['p(99)<=250']){
            throw 'Required k6 output content: phase thresholds missing'
        }
    }
    # 표준 Python JSON 파서를 재사용해 큰 NDJSON도 한 줄씩 검사. child는 기존 Job 소유/최초 기한 안에서 종료.
    $validationEvidence="$($Item.Metrics).validation"
    $validationExit=Invoke-CapNative 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe' @('-B',"$PSScriptRoot/phase18_k6.py",'--validate-metrics',$Item.Metrics,'--summary',$Item.Summary,'--expires',[string]$clock.Expires) $validationEvidence
    if((Get-CapRemaining $clock) -le 0){$Item.Failure='original_expiry';throw $Item.Failure}
    $validation=Get-Content -LiteralPath "$validationEvidence.native.stdout" -Raw -Encoding UTF8 | ConvertFrom-Json
    if($validationExit -ne 0 -or $validation.valid -ne $true){throw $validation.reason}
    $Item.MetricsDigest=$validation.metricsSha256;$Item.MetricsValidatedBytes=$validation.metricsBytes
    return $summary
}
function Wait-K6($Item) {
    if($null -eq $script:k6PeakResident){$script:k6PeakResident=0L;$script:k6PeakPrivate=0L}
    while(-not(Test-K6Joined $Item)){
        if((Get-CapRemaining $clock) -le 0){$Item.Failure='original_expiry';throw $Item.Failure}
        Assert-K6OutputBudget
        Observe-K6Native
        Start-Sleep -Milliseconds 50
    }
    Assert-K6OutputBudget
    $Item.Exit=$Item.Capture.Process.ExitCode
    if($Item.Exit -notin @(0,99)){throw "k6 script/config/runtime exit $($Item.Exit)"}
    foreach($stream in @($Item.Capture.Out,$Item.Capture.Err)){if($stream.Overflow -or $stream.Failure){throw 'Required k6 stream failed'}}
    foreach($task in @($Item.Capture.OutTask,$Item.Capture.ErrTask)){if($task.IsFaulted -or $task.IsCanceled){throw 'Required k6 stream task failed'}}
    foreach($line in [IO.File]::ReadAllLines("$root/k6-$($Item.Phase).stderr.log")){
        if(-not $line.Trim()){continue}
        $entry=$line|ConvertFrom-Json
        if($entry.level -in @('error','fatal','panic') -and -not($Item.Exit -eq 99 -and $entry.msg -eq "thresholds on metrics 'accepted_http_duration' have been crossed")){throw 'k6 runtime error evidence'}
    }
    if(-not(Test-Path -LiteralPath $Item.Summary) -or -not(Test-Path -LiteralPath $Item.Metrics)){throw 'Required k6 output missing'}
    if((Get-Item -LiteralPath $Item.Summary).Length -gt 1048576 -or (Get-Item -LiteralPath $Item.Metrics).Length -gt $Item.MetricsLimit){throw 'Required k6 output bound'}
    if($inputData.plan.schemaVersion -ne 4){Complete-K6Output $Item}
    else{$Item.Capture.Release();$Item.Released=$true}

    Assert-K6OutputBudget
}

function Complete-K6Output($Item) {
    try { $summary=Read-K6RequiredOutput $Item }
    catch {
        if(-not $Item.Failure){$Item.Failure='required_output_content'}
        throw
    }
    $failedThresholds=@()
    foreach($metric in $summary.data.metrics.PSObject.Properties){
        foreach($threshold in $metric.Value.thresholds.PSObject.Properties){if($threshold.Value.ok -eq $false){$failedThresholds+="$($metric.Name):$($threshold.Name)"}}
    }
    $thresholdExit=$Item.Exit -eq 99 -and $failedThresholds.Count -gt 0
    if($Item.Exit -ne 0 -and -not $thresholdExit){throw "k6 script/config/runtime exit $($Item.Exit)"}
    if($Item.Exit -eq 0 -and $failedThresholds.Count -gt 0){throw 'k6 exit/threshold mismatch'}
    $result=@{Phase=$Item.Phase;ActualExit=$Item.Exit;FailedThresholds=$failedThresholds;RequiredOutputComplete=$true;ProvisionalReason=$(if($thresholdExit){'THRESHOLD_FAILED'}else{'K6_EXIT_ZERO'});FinalClassification='NOT_EVALUATED';OptionalHtml='not_requested';Summary=$Item.Summary;Metrics=$Item.Metrics;MetricsBytes=$Item.MetricsValidatedBytes;MetricsSha256=$Item.MetricsDigest;SummarySha256=(Get-FileHash -LiteralPath $Item.Summary -Algorithm SHA256).Hash.ToLowerInvariant();PeakWorkingSet=$Item.PeakWorkingSet;PeakPrivateBytes=$Item.PeakPrivateBytes}
    # stream/후손 확인 후 Release하고 결과 저장. 결과 쓰기 실패도 wrapper 실패로 유지
    if(-not $Item.Released){$Item.Capture.Release();$Item.Released=$true}
    Save-CapJson "$root/k6-$($Item.Phase)-exit.json" $result
    $Item.Result=$result;$results.Add($result)
}
