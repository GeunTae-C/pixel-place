# 기존 Redis의 identity와 빈 시험 index를 읽기 전용 확인. 키·인증정보 출력 및 삭제 없음
. "$PSScriptRoot/phase18-cap-process.ps1"
function Read-TestRedisIdentity([ValidateRange(2,15)][int]$Database) {
    $null=Get-CapOperationMillis
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort 6379)
    if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1'){throw 'Redis listener identity unavailable'}
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0].OwningProcess)"
    $tcp=[Net.Sockets.TcpClient]::new()
    try{
        $connecting=$tcp.ConnectAsync('127.0.0.1',6379)
        if(-not $connecting.Wait((Get-CapOperationMillis 3000))){throw 'CAP Redis connect deadline'}
    }catch{$tcp.Dispose();throw}
    $stream=$tcp.GetStream();$stream.ReadTimeout=Get-CapOperationMillis 3000;$stream.WriteTimeout=Get-CapOperationMillis 3000
    $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8,$false,4096,$true)
    function Read-Redis([string[]]$Parts) {
        $stream.ReadTimeout=Get-CapOperationMillis 3000;$stream.WriteTimeout=Get-CapOperationMillis 3000
        $wire='*'+$Parts.Count+"`r`n"
        foreach($part in $Parts){$wire+='$'+([Text.Encoding]::UTF8.GetByteCount($part))+"`r`n"+$part+"`r`n"}
        $bytes=[Text.Encoding]::UTF8.GetBytes($wire);$stream.Write($bytes,0,$bytes.Length)
        $line=$reader.ReadLine();if(-not $line -or $line.StartsWith('-')){throw 'Redis read-only query rejected'}
        if($line.StartsWith('$')){
            $n=[int]$line.Substring(1);if($n -lt 0 -or $n -gt 1048576){throw 'Redis response bound'}
            $chars=New-Object char[] $n;$read=0
            while($read -lt $n){$stream.ReadTimeout=Get-CapOperationMillis 3000;$count=$reader.Read($chars,$read,$n-$read);if($count -eq 0){throw 'Redis truncated'};$read+=$count}
            if($reader.ReadLine() -ne ''){throw 'Redis response terminator'};return (-join $chars)
        }
        return $line.Substring(1)
    }
    try {
        if((Read-Redis @('SELECT',"$Database")) -ne 'OK'){throw 'Redis selection failed'}
        $size=Read-Redis @('DBSIZE');$cluster=Read-Redis @('INFO','cluster');$replication=Read-Redis @('INFO','replication');$clients=Read-Redis @('CLIENT','LIST');$server=Read-Redis @('INFO','server')
        $redisRun=[regex]::Match($server,'run_id:([0-9a-f]{40})').Groups[1].Value
        $redisPid=[regex]::Match($server,'process_id:([0-9]+)').Groups[1].Value
        if($size -ne '0' -or $cluster -notmatch 'cluster_enabled:0' -or $replication -notmatch 'role:master' -or
            @($clients -split "`n"|Where-Object {$_ -match "(?:^| )db=$Database(?: |$)"}).Count -ne 1 -or -not $redisRun -or [long]$redisPid -ne $process.ProcessId){throw 'Empty standalone Redis identity required'}
        [pscustomobject]@{Database=$Database;Pid=[long]$process.ProcessId;RunId=$redisRun;StartUtc=$process.CreationDate.ToUniversalTime().ToString('o');Empty=$true;OtherClients=0}
    } finally {$reader.Dispose();$stream.Dispose();$tcp.Dispose()}
}
