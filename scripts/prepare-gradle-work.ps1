# Gradle JVM보다 먼저 F cache/temp 소유 경계 확인. 사용자 영구 환경 변경 없음
$ErrorActionPreference='Stop'
. "$PSScriptRoot/pixel-place-work.ps1"
Assert-PixelPlaceExecutionEnvironment
$expected='F:/pixel-place-work/projects/pixel-place'
if($env:PIXEL_PLACE_WORK_SESSION){
    if($env:PIXEL_PLACE_WORK_SESSION -notmatch '^[a-z0-9][a-z0-9-]{5,79}$'){throw 'Invalid work session'}
    $expected="F:/pixel-place-work/sessions/$env:PIXEL_PLACE_WORK_SESSION"
}
if($env:PIXEL_PLACE_WORK_ROOT -cne $expected){throw 'Gradle work ownership mismatch'}
if($env:PIXEL_PLACE_REQUESTED_CACHE){
    Assert-PixelPlaceWorkPath $env:PIXEL_PLACE_REQUESTED_CACHE
    if([IO.Path]::GetFullPath($env:PIXEL_PLACE_REQUESTED_CACHE) -ine [IO.Path]::GetFullPath("$expected/project-cache")){throw 'Gradle cache must match the selected F work owner'}
}
foreach($path in @($expected,$env:GRADLE_USER_HOME,$env:JAVA_HOME)) { Assert-PixelPlaceWorkPath $path }
if($env:GRADLE_USER_HOME -cne 'F:/pixel-place-work/gradle-home'){throw 'Dedicated F Gradle home required'}
if(-not(Test-Path "$env:GRADLE_USER_HOME/caches/modules-2") -or -not(Test-Path "$env:GRADLE_USER_HOME/wrapper/dists")){throw 'Verified F Gradle cache required'}
foreach($volume in @('C:','F:')) { if([IO.DriveInfo]::new($volume).AvailableFreeSpace -lt 10GB){throw 'Minimum free space required'} }
foreach($area in @('build','project-cache','temp','jna')) {
    $path="$expected/$area"; Assert-PixelPlaceWorkPath $path
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}
