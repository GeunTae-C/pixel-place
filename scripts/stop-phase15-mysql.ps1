param()
$ErrorActionPreference = 'Stop'
$phase15Root = 'E:/pixel-place-phase15/phase15-a-20260918'
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
$env:TEMP = "$phase15Root/tmp"; $env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $env:TEMP
. ./scripts/harness-local-secrets.ps1
Import-HarnessLocalSecrets -Root (Get-Location).Path
$application = Get-Content -LiteralPath 'src/main/resources/application.yml' -Raw -Encoding UTF8
$dbUserMatches = [regex]::Matches($application, '(?m)^    username: ([A-Za-z0-9_]+)\s*$')
if ($dbUserMatches.Count -ne 1 -or -not $env:DB_PASSWORD) { throw 'Existing safe credential injection required' }
$env:PIXEL_PLACE_BENCH_DB_USERNAME = $dbUserMatches[0].Groups[1].Value
$mysqlDriver = @(Get-ChildItem -LiteralPath "$phase15Root/gradle-home/caches/modules-2/files-2.1/com.mysql/mysql-connector-j" -Filter '*.jar' -Recurse -File | Where-Object { $_.Name -match '^mysql-connector-j-[0-9.]+\.jar$' })
if ($mysqlDriver.Count -ne 1) { throw 'Exact existing JDBC driver required' }
$ownedLauncher = [int](Get-Content -LiteralPath "$phase15Root/mysql/ownership.json" -Raw | ConvertFrom-Json).Pid
$ownedServer = [int](Get-Content -LiteralPath "$phase15Root/mysql/mysql.pid" -Raw).Trim()
& "$env:JAVA_HOME/bin/java.exe" -cp $mysqlDriver[0].FullName scripts/Phase15OwnedDatabaseStop.java
if ($LASTEXITCODE -ne 0) { throw 'Owned E database shutdown failed' }
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    $listener = @(Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue)
    $alive = @(Get-Process -Id $ownedLauncher,$ownedServer -ErrorAction SilentlyContinue)
    if ($listener.Count -eq 0 -and $alive.Count -eq 0) { break }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if ($listener.Count -ne 0 -or $alive.Count -ne 0) { throw 'Owned E database listener/process still active' }
[pscustomobject]@{ShutdownCommandExit=0;Port3307Listening=$false;OwnedProcessesStopped=@($ownedLauncher,$ownedServer);Existing3306Untouched=$true;Existing3306Listening=(@(Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue).Count -gt 0);Utc=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath "$phase15Root/mysql/shutdown-verification.json" -Encoding UTF8
Write-Output 'OWNED_E_MYSQL_SHUTDOWN_VERIFIED port=3307'
