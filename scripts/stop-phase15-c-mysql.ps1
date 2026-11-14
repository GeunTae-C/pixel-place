# 지정 C 실행에서 기동한 E: instance identity 확인 후 SQL 정상 종료. 기존 종료 증거 보존
param([ValidatePattern('^phase15-C-[1-5]-[0-9]{8}(?:-[0-9]{9})?$')][string]$RunPrefix='phase15-C-1-20260922')
$ErrorActionPreference = 'Stop'
$phaseRoot = 'E:/pixel-place-phase15/phase15-a-20260918'
$evidence = Join-Path $phaseRoot "agent-runs/$RunPrefix-mysql-stop"
$start = Get-Content -Raw -LiteralPath "$phaseRoot/agent-runs/$RunPrefix-mysql-start/started.json" | ConvertFrom-Json
if (Test-Path -LiteralPath $evidence) { throw 'Stop evidence already exists; inspect before resuming' }
. (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
$charge = (Save-Phase15CCharge).chargedBytes
if ($charge + 64MB -gt 74GB -or ([IO.DriveInfo]::new('E:')).AvailableFreeSpace -lt 5GB + 64MB) { throw 'Final evidence budget gate failed' }
$launcher = Get-Process -Id $start.Pid
if ([IO.Path]::GetFullPath($launcher.Path) -ne [IO.Path]::GetFullPath($start.Binary) -or $launcher.StartTime.ToUniversalTime().ToString('o') -ne $start.StartedUtc) { throw 'C launcher identity changed' }
$ownedServer = [int](Get-Content -Raw -LiteralPath "$phaseRoot/mysql/mysql.pid").Trim()
$server = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedServer"
if ($server.ParentProcessId -ne $start.Pid -or [IO.Path]::GetFullPath($server.ExecutablePath) -ne [IO.Path]::GetFullPath($start.Binary) -or -not $server.CommandLine.Contains($start.RuntimeConfig)) { throw 'Owned child process mismatch' }
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 3307)
if ($listeners.Count -ne 1 -or $listeners[0].OwningProcess -ne $ownedServer -or $listeners[0].LocalAddress -ne '127.0.0.1') { throw 'Owned listener mismatch' }
$original = @(Get-NetTCPConnection -State Listen -LocalPort 3306,6379 | Select-Object LocalPort,OwningProcess)
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
$env:TEMP = "$phaseRoot/tmp"; $env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $env:TEMP
. ./scripts/harness-local-secrets.ps1
Import-HarnessLocalSecrets -Root (Get-Location).Path
$application = Get-Content -Raw -Encoding UTF8 -LiteralPath 'src/main/resources/application.yml'
$dbUsers = [regex]::Matches($application, '(?m)^    username: ([A-Za-z0-9_]+)\s*$')
if ($dbUsers.Count -ne 1 -or -not $env:DB_PASSWORD) { throw 'Existing safe credential injection required' }
$env:PIXEL_PLACE_BENCH_DB_USERNAME = $dbUsers[0].Groups[1].Value
$driver = @(Get-ChildItem -LiteralPath "$phaseRoot/gradle-home/caches/modules-2/files-2.1/com.mysql/mysql-connector-j" -Filter '*.jar' -Recurse -File | Where-Object { $_.Name -match '^mysql-connector-j-[0-9.]+\.jar$' })
if ($driver.Count -ne 1) { throw 'Exact existing JDBC driver required' }
New-Item -ItemType Directory -Path $evidence | Out-Null
# Java guard가 실제 port/UUID/real datadir/loopback을 다시 확인한 뒤 SHUTDOWN
& "$env:JAVA_HOME/bin/java.exe" -cp $driver[0].FullName scripts/Phase15OwnedDatabaseStop.java
$stopExit = $LASTEXITCODE
$stopExit | Set-Content -Encoding UTF8 -LiteralPath "$evidence/sql-exit.txt"
if ($stopExit -ne 0) { throw 'Owned SQL shutdown failed; do not kill unrelated processes' }
$deadline = [DateTime]::UtcNow.AddSeconds(45)
do {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue)
    $alive = @(Get-Process -Id $start.Pid,$ownedServer -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0 -and $alive.Count -eq 0) { break }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if ($listeners.Count -ne 0 -or $alive.Count -ne 0) { throw 'Owned server is still stopping; preserve evidence and observe' }
$after = @(Get-NetTCPConnection -State Listen -LocalPort 3306,6379 | Select-Object LocalPort,OwningProcess)
if (Compare-Object $original $after -Property LocalPort,OwningProcess) { throw 'Original service listener identity changed' }
[pscustomobject]@{Utc=[DateTime]::UtcNow.ToString('o');SqlExit=$stopExit;Port3307Listening=$false;OwnedProcessesStopped=@($start.Pid,$ownedServer);OriginalListeners=$after} | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath "$evidence/shutdown-verification.json"
Save-Phase15CCharge | Out-Null
Write-Output 'C_OWNED_E_MYSQL_SHUTDOWN_VERIFIED port=3307'
