# 기존 E: 소유 datadir만 다시 기동. 초기화·credential 변경·기존 3306 제어 책임 없음
param([ValidatePattern('^phase15-C-[1-5]-[0-9]{8}(?:-[0-9]{9})?$')][string]$RunPrefix='phase15-C-1-20260922')
$ErrorActionPreference = 'Stop'
$phaseRoot = 'E:/pixel-place-phase15/phase15-a-20260918'
$mysqlRoot = Join-Path $phaseRoot 'mysql'
$evidence = Join-Path $phaseRoot "agent-runs/$RunPrefix-mysql-start"
$budget = Get-Content -Raw -Encoding UTF8 -LiteralPath 'scripts/phase15-c-budget.json' | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
Assert-Phase15CBudget $budget
$charged = (Save-Phase15CCharge).chargedBytes
if ($budget.maximumBytes -ne 74GB -or $budget.carriedChargeBytes -lt 36960813040L -or $charged + 5GB -gt $budget.maximumBytes -or ([IO.DriveInfo]::new('E:')).AvailableFreeSpace -lt 10GB) { throw 'Approved C budget or free-space gate failed' }
if (Test-Path -LiteralPath $evidence) { throw 'Start evidence exists; inspect owned process before resuming' }
$owner = Get-Content -Raw -LiteralPath "$mysqlRoot/ownership.json" | ConvertFrom-Json
if ($owner.Port -ne 3307 -or $owner.Bind -ne '127.0.0.1' -or [IO.Path]::GetFullPath($owner.Root) -ne [IO.Path]::GetFullPath($mysqlRoot)) { throw 'Existing MySQL ownership mismatch' }
$serverUuid = [regex]::Match((Get-Content -Raw -LiteralPath "$mysqlRoot/data/auto.cnf"), 'server-uuid=([a-z0-9-]+)').Groups[1].Value
$regressionUuid = [regex]::Match((Get-Content -Raw -LiteralPath "$mysqlRoot/regression-ownership.properties"), 'server_uuid=([a-z0-9-]+)').Groups[1].Value
if (-not $serverUuid -or $serverUuid -ne $regressionUuid) { throw 'Existing datadir UUID mismatch' }
$config = Get-Content -LiteralPath "$mysqlRoot/runtime.ini"
foreach ($required in @('port=3307','bind-address=127.0.0.1','mysqlx=OFF','innodb-flush-log-at-trx-commit=1','sync-binlog=1','binlog-row-image=FULL')) {
    if ($config -notcontains $required) { throw 'Existing runtime configuration contract mismatch' }
}
if (Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue) { throw '3307 already listening; do not duplicate startup' }
$probe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,3307)
try { $probe.Start() } finally { $probe.Stop() }
$binary = 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysqld.exe'
$version = & $binary --no-defaults --version
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'Ver 8\.4\.11\b') { throw 'Expected MySQL binary version not found' }
New-Item -ItemType Directory -Path $evidence | Out-Null
$env:TEMP = Join-Path $phaseRoot 'tmp'; $env:TMP = $env:TEMP
$server = Start-Process -FilePath $binary -ArgumentList ('--defaults-file='+$owner.RuntimeConfig) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence/server.stdout" -RedirectStandardError "$evidence/server.stderr"
[pscustomobject]@{Pid=$server.Id;StartedUtc=$server.StartTime.ToUniversalTime().ToString('o');Binary=$binary;RuntimeConfig=$owner.RuntimeConfig;ServerUuid=$serverUuid;Port=3307;OwnedRoot=$mysqlRoot;Original3306Untouched=$true} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$evidence/started.json"
Write-Output "C_MYSQL_STARTED pid=$($server.Id) port=3307"
