[CmdletBinding()]
param(
    [string]$RunDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-failures.ps1")
. (Join-Path $PSScriptRoot "harness-evidence.ps1")

function Write-GateMessage {
    param([string]$Message)

    Write-Output "[verify-fast] $Message"
}

function Invoke-LoggedNative {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$LogPath
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $FilePath @Arguments 2>&1 | Tee-Object -FilePath $LogPath -ErrorAction Stop | ForEach-Object { Write-Host $_ }
        $nativeExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    return $nativeExit
}

function Test-TestPartitionContract {
    param([string]$RepoRoot)

    $buildFile = Join-Path $RepoRoot "build.gradle"
    $buildText = Get-Content -LiteralPath $buildFile -Raw
    if ($buildText -notmatch "excludeTags\s+'mysql-integration'" -or
        $buildText -notmatch "tasks\.register\('mysqlIntegrationTest',\s*Test\)" -or
        $buildText -notmatch "includeTags\s+'mysql-integration'") {
        return $false
    }

    $integrationProfilePattern = '@ActiveProfiles\("mysql-integration"\)'
    $integrationTagPattern = '@Tag\("mysql-integration"\)'
    $testRoot = Join-Path $RepoRoot "src/test/java"
    $profileTests = @(
        Get-ChildItem -LiteralPath $testRoot -Recurse -File -Filter "*.java" |
            Where-Object {
                (Get-Content -LiteralPath $_.FullName -Raw) -match $integrationProfilePattern
            }
    )

    if ($profileTests.Count -eq 0) {
        return $false
    }
    foreach ($profileTest in $profileTests) {
        if ((Get-Content -LiteralPath $profileTest.FullName -Raw) -notmatch $integrationTagPattern) {
            return $false
        }
    }
    return $true
}

try {
    $repoRoot = (& git rev-parse --show-toplevel 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
        Write-GateMessage "Git worktree를 확인할 수 없음"
        exit 30
    }
    Set-Location $repoRoot

    if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
        $RunDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "pixel-place-harness"
    }
    [System.IO.Directory]::CreateDirectory($RunDirectory) | Out-Null

    $powerShellPath = (Get-Process -Id $PID).Path
    $hygieneScript = Join-Path $repoRoot "scripts/repo-hygiene.ps1"
    if (-not (Test-Path -LiteralPath $hygieneScript -PathType Leaf)) {
        Write-GateMessage "repo hygiene script 누락: $hygieneScript"
        exit 30
    }

    $hygieneLog = Join-Path $RunDirectory "repo-hygiene.log"
    $hygieneNativeExit = Invoke-LoggedNative -FilePath $powerShellPath -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $hygieneScript) -LogPath $hygieneLog
    $hygieneExit = ConvertTo-GateExit $hygieneNativeExit
    if ($hygieneExit -ne 0) {
        Write-GateMessage "repository hygiene 실패 (exit=$hygieneExit)"
        exit $hygieneExit
    }

    if (-not (Test-TestPartitionContract -RepoRoot $repoRoot)) {
        Write-GateMessage "빠른/통합 테스트 분리 계약이 변경되었거나 누락됨"
        exit 10
    }

    $diffLog = Join-Path $RunDirectory "git-diff-check.log"
    $diffOutput = @(Get-EvidenceWhitespaceLines -Root $repoRoot)
    [IO.File]::WriteAllLines($diffLog, [string[]]$diffOutput, (New-Object Text.UTF8Encoding($false)))

    if ($diffOutput.Count -gt 0) {
        $newDiffErrors = @($diffOutput)
        $baselinePath = [Environment]::GetEnvironmentVariable(
            "PIXEL_PLACE_DIFF_BASELINE",
            [EnvironmentVariableTarget]::Process
        )
        if (-not [string]::IsNullOrWhiteSpace($baselinePath) -and
            (Test-Path -LiteralPath $baselinePath -PathType Leaf)) {
            $baseline = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($baselineLine in Get-Content -LiteralPath $baselinePath) {
                $baseline.Add($baselineLine) | Out-Null
            }
            $newDiffErrors = @($diffOutput | Where-Object { -not $baseline.Contains($_) })
        }

        if ($newDiffErrors.Count -gt 0) {
            $newDiffErrors | Write-Output
            Write-GateMessage "새 git diff whitespace 오류 발견"
            exit 10
        }
        Write-GateMessage "기존 baseline에 이미 있던 diff 오류만 존재함"
    }

    $gradleWrapper = Join-Path $repoRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        Write-GateMessage "gradlew.bat 누락"
        exit 20
    }
    if ($null -eq (Get-Command java -ErrorAction SilentlyContinue)) {
        Write-GateMessage "Java 실행 파일을 PATH에서 찾을 수 없음"
        exit 20
    }

    $gradleLog = Join-Path $RunDirectory "verify-fast-gradle.log"
    Write-GateMessage "default test에서 mysql-integration tag를 제외하고 실행"
    $gradleArguments = @("test", "--console=plain")
    if ($env:PIXEL_PLACE_SUPERVISED -eq "1") { $gradleArguments += "--no-daemon" }
    $gradleExit = Invoke-LoggedNative -FilePath $gradleWrapper -Arguments $gradleArguments -LogPath $gradleLog

    if ($gradleExit -ne 0) {
        $failure = Get-GradleFailure -NativeExit $gradleExit -LogPath $gradleLog
        $failurePath = Join-Path $RunDirectory ("verify-fast-failure-" + [Guid]::NewGuid().ToString("N") + ".json")
        Write-FailureClassification -Failure $failure -Path $failurePath
        exit $failure.exit_code
    }

    Write-GateMessage "PASS"
    exit 0
}
catch {
    Write-GateMessage "내부 오류: $($_.Exception.Message)"
    exit 30
}
