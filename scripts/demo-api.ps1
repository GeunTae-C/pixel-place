# 분리 확인된 19-2 데모 DB/WAL 쌍의 production 앱 실행. 초기화·DDL·보안 우회 책임 없음
param(
    [string]$SessionId = ('phase19-demo-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$Jar = 'F:/pixel-place-work/sessions/phase19-2-20261019/build/libs/pixel-place-0.0.1-SNAPSHOT.jar'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
# 외부 JVM/Spring 설정이 데모 분리 설정보다 우선하는 실행은 기동 전에 거부
foreach ($name in @('SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION')) {
    if ([Environment]::GetEnvironmentVariable($name, 'Process')) { throw "Conflicting external configuration: $name" }
}
# active=default와 별개로 profile을 추가하는 include/group 및 외부 import도 차단
foreach ($entry in Get-ChildItem Env:) {
    if ($entry.Value -and $entry.Name -match '^SPRING_PROFILES_(INCLUDE|GROUP)(_|$)|^SPRING_CONFIG_IMPORT(_|$)') {
        throw "Conflicting external configuration: $($entry.Name)"
    }
}
. "$PSScriptRoot/pixel-place-work.ps1"
. "$PSScriptRoot/harness-local-secrets.ps1"
. "$PSScriptRoot/mysql-test-redis.ps1"
$config = Get-Content -LiteralPath "$PSScriptRoot/demo-environment.json" -Raw | ConvertFrom-Json
$work = Set-PixelPlaceWorkEnvironment $SessionId
Set-Location $repo
Import-HarnessLocalSecrets -Root $repo
foreach ($name in @('DB_PASSWORD','KAKAO_CLIENT_ID','KAKAO_CLIENT_SECRET','PIXEL_PLACE_JWT_SECRET','PIXEL_PLACE_OAUTH_COOKIE_SECRET')) {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) { throw "Required credential absent: $name" }
}
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw 'Build the current source JAR on F first' }
Assert-PixelPlaceWorkPath $Jar
if ($Jar -notlike 'F:/*') { throw 'Demo build must be on F' }
Assert-PixelPlaceWorkPath $config.walFile
if ($config.walFile -notlike 'F:/pixel-place-wal/demo/*/pixel-place.wal') { throw 'Dedicated demo WAL path required' }
foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe'")) {
    if (-not $process.CommandLine -or $process.CommandLine -match 'pixel-place.*\.jar|PixelPlaceApplication') {
        throw 'Another application JVM or unknown Java owner; verify WAL ownership before starting'
    }
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw 'API port occupied; preserve its owner' }
# 기동 직전에도 예약 DB15의 빈 상태와 다른 client 부재 확인. CAP와 동시에 실행하면 안 됨
$null = Read-TestRedisIdentity $config.redisDatabase
$mysql = 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe'
$env:MYSQL_PWD = $env:DB_PASSWORD
try {
    $identity = & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$($config.mysqlPort)" --user=root --batch --raw --skip-column-names "--database=$($config.mysqlSchema)" -e 'SELECT @@port,@@datadir,@@server_uuid,DATABASE();' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Demo DB connection failed; credentials suppressed' }
    $expected = "$($config.mysqlPort)`t$($config.mysqlRoot.Replace('/','\'))\data\`t$($config.mysqlUuid)`t$($config.mysqlSchema)"
    if ($identity -cne $expected) { throw 'Demo DB identity mismatch; do not start API' }
} finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }

# 원본 application.yml을 바꾸지 않고 자식 JVM에만 실제 저장소와 활성 정책을 명시
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:$($config.mysqlPort)/$($config.mysqlSchema)?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
$env:SPRING_DATA_REDIS_HOST = '127.0.0.1'
$env:SPRING_DATA_REDIS_PORT = '6379'
$env:SPRING_DATA_REDIS_DATABASE = [string]$config.redisDatabase
$env:PIXEL_PLACE_WAL_ACTIVE_FILE = $config.walFile
$env:PIXELPLACE_WRITE_MODE = 'group'
$env:SPRING_PROFILES_ACTIVE = 'default'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8080'
Write-Output "Demo API: loopback8080, DB3308/$($config.mysqlSchema), Redis DB$($config.redisDatabase), WAL=$($config.walFile), profile=default, write=group"
& $work.Java '-Xmx512m' "-Djava.io.tmpdir=$($work.Temp)" "-Djna.tmpdir=$($work.Jna)" -jar $Jar `
    '--server.address=127.0.0.1' '--server.port=8080' '--spring.profiles.active=default' `
    "--spring.datasource.url=$env:SPRING_DATASOURCE_URL" '--spring.datasource.username=root' `
    '--spring.data.redis.host=127.0.0.1' '--spring.data.redis.port=6379' "--spring.data.redis.database=$($config.redisDatabase)" `
    "--pixel-place.wal.active-file=$($config.walFile)" '--pixel-place.write.mode=group'
exit $LASTEXITCODE
