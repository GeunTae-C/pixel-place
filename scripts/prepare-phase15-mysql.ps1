param([string]$StorageRoot = 'E:\pixel-place-phase15\phase15-a-20260918', [switch]$ResumeInitialized)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($StorageRoot)
if ($root -cnotmatch '^E:\\pixel-place-phase15\\[a-z0-9_-]+$') { throw 'Explicit E: measurement root required' }
if ((New-Object IO.DriveInfo('E')).AvailableFreeSpace -lt 30GB) { throw '25GiB budget plus 5GiB remaining required' }
$mysqlRoot = Join-Path $root 'mysql'
if (Test-Path -LiteralPath $mysqlRoot) {
    if (-not $ResumeInitialized) { throw 'Existing MySQL directory cannot be initialized again' }
    $initLog = Get-Content -Raw -LiteralPath (Join-Path $mysqlRoot 'logs/initialize.err')
    if ($initLog -notmatch 'MySQL Server Initialization - end' -or $initLog -match '\[ERROR\]' -or (Test-Path -LiteralPath (Join-Path $mysqlRoot 'ownership.json'))) { throw 'Only this unstarted, completed initialization may resume' }
} elseif ($ResumeInitialized) { throw 'No owned initialization to resume' }
$listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,3307)
try { $listener.Start() } finally { $listener.Stop() }
$binary = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe'
$version = & $binary --no-defaults --version
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'Ver 8\.4\.11\b') { throw 'Existing MySQL 8.4.11 binary required' }
. (Join-Path $PSScriptRoot 'harness-local-secrets.ps1')
Import-HarnessLocalSecrets -Root (Get-Location).Path
if (-not $env:DB_PASSWORD) { throw 'Existing safe credential required' }
$env:TEMP = Join-Path $root 'tmp'; $env:TMP = $env:TEMP
$settings = @{}
Get-Content -LiteralPath (Join-Path $root 'mysql-original-settings.txt') | ForEach-Object {
    $pair = $_ -split '=',2
    if ($pair.Count -eq 2) { $settings[$pair[0]] = $pair[1] }
}
if ($settings['version'] -ne '8.4.11') { throw 'Source settings version mismatch' }
foreach ($name in @('','data','redo','undo','tmp','tmp/session','logs','imports','private')) {
    if (-not $ResumeInitialized) { New-Item -ItemType Directory -Path (Join-Path $mysqlRoot $name) | Out-Null }
}
$path = $mysqlRoot.Replace('\','/')
$location = @('[mysqld]','basedir="C:/Program Files/MySQL/MySQL Server 8.4"',
    ('datadir='+$path+'/data'),('innodb-data-home-dir='+$path+'/data/'),
    ('innodb-log-group-home-dir='+$path+'/redo'),('innodb-undo-directory='+$path+'/undo'),
    ('tmpdir='+$path+'/tmp'),('innodb-temp-data-file-path='+$path+'/tmp/ibtmp1:12M:autoextend'),
    ('innodb-temp-tablespaces-dir='+$path+'/tmp/session'),
    'lower-case-table-names=1','innodb-page-size=16384')
$utf8 = New-Object Text.UTF8Encoding($false)
$initialConfig = Join-Path $mysqlRoot 'initialize.ini'
if (-not $ResumeInitialized) { [IO.File]::WriteAllLines($initialConfig, ($location + ('log-error='+$path+'/logs/initialize.err')), $utf8) }
$runtime = @($location) + @('port=3307','bind-address=127.0.0.1','mysqlx=OFF',
    ('log-bin='+$path+'/logs/binlog'),('log-error='+$path+'/logs/server.err'),
    ('general-log-file='+$path+'/logs/general.log'),('slow-query-log-file='+$path+'/logs/slow.log'),
    ('pid-file='+$path+'/mysql.pid'),('secure-file-priv='+$path+'/imports'))
$copied = @('binlog_expire_logs_seconds','binlog_format','binlog_row_image','binlog_transaction_compression',
    'character_set_server','collation_server','general_log','slow_query_log','innodb_adaptive_hash_index',
    'innodb_buffer_pool_instances','innodb_buffer_pool_size','innodb_change_buffering','innodb_dedicated_server',
    'innodb_doublewrite','innodb_file_per_table','innodb_flush_log_at_trx_commit','innodb_flush_method','innodb_flush_neighbors',
    'innodb_io_capacity','innodb_io_capacity_max','innodb_log_buffer_size','innodb_purge_threads','innodb_read_io_threads',
    'innodb_redo_log_capacity','innodb_write_io_threads','max_allowed_packet','max_binlog_size','max_connections',
    'max_heap_table_size','performance_schema','sql_mode','sync_binlog','table_open_cache','temptable_max_mmap',
    'temptable_max_ram','thread_cache_size','tmp_table_size','transaction_isolation')
foreach ($key in $copied) {
    if (-not $settings.ContainsKey($key) -or $settings[$key] -cnotmatch '^[A-Za-z0-9_,.-]+$') { throw 'Missing or unsupported source setting' }
    $runtime += $key.Replace('_','-') + '=' + $settings[$key]
}
$runtimeConfig = Join-Path $mysqlRoot 'runtime.ini'
$initialExit = $null
if (-not $ResumeInitialized) {
    [IO.File]::WriteAllLines($runtimeConfig, $runtime, $utf8)
    $initial = Start-Process -FilePath $binary -ArgumentList @(('--defaults-file='+$initialConfig),'--initialize-insecure') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $mysqlRoot 'logs/initialize.stdout') -RedirectStandardError (Join-Path $mysqlRoot 'logs/initialize.stderr')
    [void]$initial.Handle
    if (-not $initial.WaitForExit(120000)) { throw 'Initialization deadline exceeded; owned process may remain alive; preserve files' }
    $initialExit = $initial.ExitCode
    if ($initialExit -ne 0) { throw 'Initialization failed; inspect sanitized E: error log; preserve files' }
} elseif (@(Compare-Object (Get-Content -LiteralPath $runtimeConfig) $runtime).Count -ne 0) { throw 'Resume settings changed' }
# 무작위 초기 비밀번호를 로그에 생성하지 않음. 첫 정상 시작의 init-file에서 기존 안전한 credential 적용
$secretFile = Join-Path $mysqlRoot 'private/credential-init.sql'
$file = New-Object IO.FileInfo($secretFile)
$stream = $file.Create(); $stream.Dispose()
$acl = New-Object Security.AccessControl.FileSecurity
$acl.SetAccessRuleProtection($true,$false)
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow')))
Set-Acl -LiteralPath $secretFile -AclObject $acl
$escaped = $env:DB_PASSWORD.Replace("'","''")
[IO.File]::WriteAllText($secretFile, "SET SESSION sql_mode='NO_BACKSLASH_ESCAPES';`nALTER USER 'root'@'localhost' IDENTIFIED BY '$escaped';`n", $utf8)
$escaped = $null
$server = Start-Process -FilePath $binary -ArgumentList @(('--defaults-file='+$runtimeConfig),('--init-file='+$secretFile)) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $mysqlRoot 'logs/server.stdout') -RedirectStandardError (Join-Path $mysqlRoot 'logs/server.stderr')
[pscustomobject]@{Pid=$server.Id;Port=3307;Bind='127.0.0.1';Binary=$binary;Version='8.4.11';Root=$mysqlRoot;RuntimeConfig=$runtimeConfig;InitializationExit=$initialExit;ResumedAfterCompletedInitialization=$ResumeInitialized.IsPresent;CopiedSettings=$copied;CredentialFile=$secretFile;CredentialFileNeedsRemovalAfterAuthentication=$true} |
    ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $mysqlRoot 'ownership.json')
Write-Output ('OWNED_MYSQL_STARTED pid='+$server.Id+' port=3307 root='+$mysqlRoot)
