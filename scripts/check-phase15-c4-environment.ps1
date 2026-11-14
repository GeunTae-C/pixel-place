# 실행 중인 전용 서비스의 PID 재사용·경로·실제 SQL 내구성 확인. 기동/종료/DB 변경 없음
$ErrorActionPreference='Stop'
$root='E:/pixel-place-phase15/phase15-a-20260918'
$start=Get-Content -Raw "$root/agent-runs/phase15-C-4-20260923-mysql-start/started.json" | ConvertFrom-Json
$launcher=Get-Process -Id $start.Pid
if($launcher.StartTime.ToUniversalTime().ToString('o') -ne $start.StartedUtc -or [IO.Path]::GetFullPath($launcher.Path) -ne [IO.Path]::GetFullPath($start.Binary)){throw 'Owned launcher changed'}
$serverId=[int](Get-Content -Raw "$root/mysql/mysql.pid").Trim()
$server=Get-CimInstance Win32_Process -Filter "ProcessId=$serverId"
if($server.ParentProcessId -ne $start.Pid -or -not $server.CommandLine.Contains($start.RuntimeConfig) -or [IO.Path]::GetFullPath($server.ExecutablePath) -ne [IO.Path]::GetFullPath($start.Binary)){throw 'Owned server changed'}
$listener=@(Get-NetTCPConnection -State Listen -LocalPort 3307)
if($listener.Count -ne 1 -or $listener[0].OwningProcess -ne $serverId -or $listener[0].LocalAddress -ne '127.0.0.1'){throw 'Owned listener changed'}
$env:TEMP="$root/tmp"; $env:TMP=$env:TEMP; $env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir='+$env:TEMP
. ./scripts/harness-local-secrets.ps1
Import-HarnessLocalSecrets -Root (Get-Location).Path
$application=Get-Content -Raw -Encoding UTF8 src/main/resources/application.yml
$users=[regex]::Matches($application,'(?m)^    username: ([A-Za-z0-9_]+)\s*$')
if($users.Count -ne 1 -or -not $env:DB_PASSWORD){throw 'Safe credential injection missing'}
$env:PIXEL_PLACE_BENCH_DB_USERNAME=$users[0].Groups[1].Value
$drivers=@(Get-ChildItem -LiteralPath "$root/gradle-home/caches/modules-2/files-2.1/com.mysql/mysql-connector-j" -Recurse -Filter '*.jar' -File | Where-Object { $_.Name -match '^mysql-connector-j-[0-9.]+\.jar$' })
if($drivers.Count -ne 1){throw 'Exact cached driver required'}
$out="$root/agent-runs/phase15-C-4-20260923-environment"
if(Test-Path -LiteralPath $out){throw 'Environment evidence already exists'}
New-Item -ItemType Directory -Path $out | Out-Null
& 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -cp $drivers[0].FullName scripts/Phase15C4DatabaseAudit.java | Tee-Object "$out/live-mysql.txt"
$result=$LASTEXITCODE
$result | Set-Content -Encoding UTF8 "$out/exit-code.txt"
if($result -ne 0){throw 'Live environment verification failed'}
[pscustomobject]@{SavedUtc=[DateTime]::UtcNow.ToString('o');Launcher=$start.Pid;LauncherStarted=$start.StartedUtc;Server=$serverId;ServerStarted=$server.CreationDate.ToUniversalTime().ToString('o');Binary=$server.ExecutablePath;RuntimeConfig=$start.RuntimeConfig;ServerUuid=$start.ServerUuid;Port=3307;ReadOnly=$true} | ConvertTo-Json | Set-Content -Encoding UTF8 "$out/identity.json"
