[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RequestPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$stream = $null
try {
    $request = Get-Content -LiteralPath $RequestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Set-Location -LiteralPath $request.directory
    $OutputEncoding = New-Object Text.UTF8Encoding($false)
    [Console]::OutputEncoding = $OutputEncoding
    $env:PIXEL_PLACE_SUPERVISED = "1"
    if ($request.hook) { $env:PIXEL_PLACE_HARNESS = "1" }
    else { Remove-Item Env:PIXEL_PLACE_HARNESS -ErrorAction SilentlyContinue }
    $arguments = @($request.arguments | ForEach-Object { [string]$_ })
    $file = [string]$request.file
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "실행 대상 누락" }
    # PowerShell 5.1 Tee-Object 기본 인코딩에 의존하지 않는 UTF-8 JSONL/로그 기록
    $stream = New-Object IO.StreamWriter($request.output, $false, (New-Object Text.UTF8Encoding($false)))
    $stream.AutoFlush = $true
    $ErrorActionPreference = "Continue"
    if ($request.separate_error) {
        if ([string]::IsNullOrWhiteSpace($request.input)) {
            & $file @arguments 2> $request.error | ForEach-Object -ErrorAction Stop { $stream.WriteLine([string]$_) }
        }
        else {
            Get-Content -LiteralPath $request.input -Raw -Encoding UTF8 -ErrorAction Stop | & $file @arguments 2> $request.error | ForEach-Object -ErrorAction Stop { $stream.WriteLine([string]$_) }
        }
    }
    else {
        if ([string]::IsNullOrWhiteSpace($request.input)) {
            & $file @arguments 2>&1 | ForEach-Object -ErrorAction Stop { $stream.WriteLine([string]$_) }
        }
        else {
            Get-Content -LiteralPath $request.input -Raw -Encoding UTF8 -ErrorAction Stop | & $file @arguments 2>&1 | ForEach-Object -ErrorAction Stop { $stream.WriteLine([string]$_) }
        }
    }
    exit $LASTEXITCODE
}
catch {
    if ($null -ne (Get-Variable request -ErrorAction SilentlyContinue)) {
        @{ status="HARNESS_ERROR"; detail="child launch or I/O failure" } | ConvertTo-Json | Set-Content -LiteralPath $request.child_error -Encoding UTF8
    }
    exit 30
}
finally { if ($null -ne $stream) { $stream.Dispose() } }
