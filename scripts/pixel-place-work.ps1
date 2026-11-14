# 현재 C 프로젝트/F 작업 경로의 프로세스 한정 환경. 시스템·사용자 영구 환경 변경 책임 없음
function Assert-PixelPlaceExecutionEnvironment {
    foreach($name in @('PATH','JAVA_HOME','GRADLE_USER_HOME','GRADLE_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','TEMP','TMP','CLASSPATH','PIXEL_PLACE_WAL_ACTIVE_FILE')) {
        $value=[Environment]::GetEnvironmentVariable($name,'Process')
        if($value -match '(?i)(?<![a-z0-9])e:[\\/]'){throw "Historical E execution path forbidden in $name"}
    }
}

# 별칭·reparse를 통한 데이터/증거 영역 우회 차단. 없는 경로도 존재하는 모든 부모 확인
function Assert-PixelPlaceWorkPath([string]$Path) {
    if($Path -notmatch '^[CFcf]:[\\/]' -or $Path.Substring(2).Contains(':')){throw 'C/F absolute execution path required'}
    $full=[IO.Path]::GetFullPath($Path)
    if($full -ne $Path.Replace('/','\').TrimEnd('\')){throw 'Normalized work path required'}
    foreach($part in $full.Substring(3).Split('\')){
        if($part -match '[. ]$|^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)'){throw 'Work path alias forbidden'}
    }
    for($p=$full;$p;$p=Split-Path $p -Parent){
        if((Test-Path -LiteralPath $p) -and ((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Work reparse path forbidden'}
    }
}

# 신규 증거 소유 경계. archive·C fallback·다른 session 중첩 금지
function Get-PixelPlaceEvidencePath([ValidatePattern('^[a-z0-9][a-z0-9-]{5,79}$')][string]$SessionId) {
    $path="F:/pixel-place-work/evidence/$SessionId"
    Assert-PixelPlaceWorkPath $path
    return $path
}

function Assert-PixelPlaceEvidencePath([string]$Path) {
    Assert-PixelPlaceWorkPath $Path
    $full=[IO.Path]::GetFullPath($Path)
    $root=[IO.Path]::GetFullPath('F:/pixel-place-work/evidence/')
    if(-not $full.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){throw 'New evidence requires dedicated F root'}
}

# 완료 증거 bytes를 바꾸지 않는 읽기 전용 재배치 연결
function Resolve-PixelPlaceHistoricalPath([string]$Path) {
    Assert-PixelPlaceWorkPath $Path
    $python='C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
    $resolved=& $python -B "$PSScriptRoot/pixel_place_paths.py" --resolve $Path
    if($LASTEXITCODE -ne 0 -or @($resolved).Count -ne 1){throw 'Historical relocation identity unavailable'}
    Assert-PixelPlaceWorkPath $resolved
    return [string]$resolved
}

function Resolve-PixelPlaceControlRoot([string]$Path) {
    Assert-PixelPlaceWorkPath $Path
    $resolved=& 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe' -B "$PSScriptRoot/pixel_place_paths.py" --control-root $Path
    if($LASTEXITCODE -ne 0 -or @($resolved).Count -ne 1){throw 'Control root identity unavailable'}
    Assert-PixelPlaceWorkPath $resolved
    return [string]$resolved
}

# 호출 셸에만 적용. F 준비 실패는 원래 예외로 유지하며 E/cache fallback 없음
function Set-PixelPlaceWorkEnvironment([ValidatePattern('^[a-z0-9][a-z0-9-]{5,79}$')][string]$SessionId) {
    Assert-PixelPlaceExecutionEnvironment
    $workRoot="F:/pixel-place-work/sessions/$SessionId"
    $gradleHome='F:/pixel-place-work/gradle-home'
    $javaHome='C:/Program Files/Java/jdk-21.0.10'
    foreach($p in @($workRoot,$gradleHome,$javaHome)){Assert-PixelPlaceWorkPath $p}
    if(-not(Test-Path -LiteralPath "$gradleHome/caches/modules-2") -or -not(Test-Path -LiteralPath "$gradleHome/wrapper/dists")){throw 'Verified F Gradle cache required; no fallback'}
    foreach($drive in @('C:','F:')){
        $v=[IO.DriveInfo]::new($drive)
        if($v.DriveType -ne 'Fixed' -or $v.DriveFormat -ne 'NTFS' -or $v.AvailableFreeSpace -lt 10GB){throw 'Work volume/minimum free gate failed'}
    }
    foreach($area in @('build','project-cache','temp','jna')){
        $p="$workRoot/$area";Assert-PixelPlaceWorkPath $p
        New-Item -ItemType Directory -Path $p -Force -ErrorAction Stop | Out-Null
    }
    $env:JAVA_HOME=$javaHome;$env:GRADLE_USER_HOME=$gradleHome
    $env:TEMP="$workRoot/temp";$env:TMP=$env:TEMP
    $env:PIXEL_PLACE_WORK_SESSION=$SessionId
    $env:GRADLE_OPTS="-Djava.io.tmpdir=$workRoot/temp -Djna.tmpdir=$workRoot/jna"
    $env:PYTHONDONTWRITEBYTECODE='1'
    $env:GRAPHIFY_OUT='C:/Users/cgt/Desktop/Java/pixel-place/graphify-out'
    $env:PIXEL_PLACE_WORK_ROOT=$workRoot
    [pscustomobject]@{SessionId=$SessionId;Root=$workRoot;Evidence=(Get-PixelPlaceEvidencePath $SessionId);Build="$workRoot/build";Cache="$workRoot/project-cache";Temp="$workRoot/temp";Jna="$workRoot/jna";Java="$javaHome/bin/java.exe";GradleHome=$gradleHome;
        GradleArguments=@('--offline','--no-daemon','--console=plain','--project-cache-dir',"$workRoot/project-cache","-Pphase15BuildRoot=$workRoot/build","-Pphase15TempRoot=$workRoot/temp","-Pphase18JnaRoot=$workRoot/jna","-Dorg.gradle.jvmargs=-Xmx512m -Djava.io.tmpdir=$workRoot/temp -Djna.tmpdir=$workRoot/jna")}
}
