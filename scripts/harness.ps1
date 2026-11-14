[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$TaskFile,
    [ValidateRange(1, 20)][int]$MaxAttempts = 6,
    [ValidateRange(1, 10)][int]$SameFailureLimit = 3,
    [switch]$ValidateOnly,
    [ValidateRange(1, 86400)][int]$WriterTimeoutSeconds = 3600,
    [ValidateRange(1, 86400)][int]$ReviewerTimeoutSeconds = 1800,
    [ValidateRange(1, 86400)][int]$GateTimeoutSeconds = 1800,
    [ValidateRange(1, 86400)][int]$TotalTimeoutSeconds = 7200
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
try {
    . (Join-Path $PSScriptRoot "harness-process.ps1")
    . (Join-Path $PSScriptRoot "pixel-place-work.ps1")
    # 전체 worker 감시: native 명령 외 증거 수집 중 정체에도 동일한 시간 상한 적용
    $root = Split-Path -Parent $PSScriptRoot
    . (Join-Path $PSScriptRoot "harness-local-secrets.ps1")
    Import-HarnessLocalSecrets -Root $root
    $runId = "{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), ([guid]::NewGuid().ToString("N"))
    $runDirectory = Get-PixelPlaceEvidencePath $runId
    $limits = @{ writer_seconds=$WriterTimeoutSeconds; reviewer_seconds=$ReviewerTimeoutSeconds; gate_seconds=$GateTimeoutSeconds; total_seconds=$TotalTimeoutSeconds; max_attempts=$MaxAttempts; same_failure_limit=$SameFailureLimit }
    $arguments = @("-MaxAttempts", [string]$MaxAttempts, "-SameFailureLimit", [string]$SameFailureLimit, "-WriterTimeoutSeconds", [string]$WriterTimeoutSeconds, "-ReviewerTimeoutSeconds", [string]$ReviewerTimeoutSeconds, "-GateTimeoutSeconds", [string]$GateTimeoutSeconds)
    if ($ValidateOnly) { $arguments += "-ValidateOnly" }
    if (-not [string]::IsNullOrWhiteSpace($TaskFile)) { $arguments += @("-TaskFile", $TaskFile) }
    $mode = if ($ValidateOnly) { "VALIDATE_ONLY" } else { "IMPLEMENT" }
    $code = Invoke-HarnessSupervisor -WorkerPath (Join-Path $PSScriptRoot "harness-worker.ps1") -WorkerArguments $arguments -Directory (Get-Location).Path -RunDirectory $runDirectory -Limits $limits -Mode $mode
    exit $code

}
catch {
    Write-Host "[harness] FINAL STATUS: HARNESS_ERROR (exit=30)"
    Write-Host "[harness] $($_.Exception.Message)"
    exit 30
}
