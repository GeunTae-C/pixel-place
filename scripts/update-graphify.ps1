# 설치된 Graphify의 GRAPHIFY_OUT 및 AST update만 사용. 날짜별 대량 사본 생성 없음
param([switch]$NoCluster)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/pixel-place-work.ps1"
$repo=Split-Path $PSScriptRoot -Parent
$env:GRAPHIFY_OUT='C:\Users\cgt\Desktop\Java\pixel-place\graphify-out'
$env:TEMP='F:/pixel-place-work/projects/pixel-place/temp'
$env:TMP=$env:TEMP
$env:PYTHONDONTWRITEBYTECODE='1'
foreach($path in @($env:GRAPHIFY_OUT,$env:TEMP)) {
    Assert-PixelPlaceWorkPath $path
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}
$arguments=@('-B','-m','graphify','update',$repo)
if($NoCluster){$arguments+='--no-cluster'}
& 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe' @arguments
if($LASTEXITCODE -ne 0){throw 'Graphify AST update failed'}
