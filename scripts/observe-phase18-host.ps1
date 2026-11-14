param(
    [Parameter(Mandatory=$true)][string]$Output,
    [ValidateRange(1,10000)][int]$Samples=120,
    [ValidateRange(100,60000)][int]$IntervalMillis=1000,
    [long]$ExecutionExpiry=0,
    [string]$StopFile
)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $Output){throw 'New host observation output required'}
# 장치 전체 raw counter. 같은 물리 장치의 DB/로그/증거 I/O까지 포함하며 WAL 비용으로 귀속하지 않음
$phase18Stream=[IO.File]::Open($Output,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
$phase18Writer=[IO.StreamWriter]::new($phase18Stream,[Text.UTF8Encoding]::new($false))
try {
    for($i=0;$i -lt $Samples;$i++) {
        if(($ExecutionExpiry -gt 0 -and [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $ExecutionExpiry) -or ($StopFile -and (Test-Path -LiteralPath $StopFile))){break}
        $phase18Started=[Diagnostics.Stopwatch]::GetTimestamp()
        try {
            $phase18CimOptions=@{};if($ExecutionExpiry -gt 0){$phase18CimOptions.OperationTimeoutSec=2}
            $phase18Counters=@(Get-CimInstance Win32_PerfRawData_PerfDisk_PhysicalDisk @phase18CimOptions | Select-Object Name,Timestamp_PerfTime,Frequency_PerfTime,DiskReadBytesPersec,DiskWriteBytesPersec,DiskReadsPersec,DiskWritesPersec,AvgDisksecPerRead,AvgDisksecPerRead_Base,AvgDisksecPerWrite,AvgDisksecPerWrite_Base,CurrentDiskQueueLength)
            $phase18Row=@{schemaVersion=1;status='observed';utc=[DateTime]::UtcNow.ToString('o');clockFrequency=[Diagnostics.Stopwatch]::Frequency;startedTicks=$phase18Started;completedTicks=[Diagnostics.Stopwatch]::GetTimestamp();disks=$phase18Counters;scope='host physical disk; all processes'}
        } catch {
            $phase18Row=@{schemaVersion=1;status='missing';utc=[DateTime]::UtcNow.ToString('o');reason=$_.Exception.GetType().Name;scope='auxiliary host physical disk collector'}
        }
        $phase18Writer.WriteLine(($phase18Row|ConvertTo-Json -Depth 6 -Compress));$phase18Writer.Flush()
        if($phase18Stream.Length -gt 16MB){throw 'Host observation byte bound'}
        if($i+1 -lt $Samples){Start-Sleep -Milliseconds $IntervalMillis}
    }
} finally {$phase18Writer.Dispose();$phase18Stream.Dispose()}
