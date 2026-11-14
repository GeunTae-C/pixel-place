[CmdletBinding()]
param(
    [string]$RunDirectory,
    [switch]$EnvironmentOnly,
    [switch]$SkipFast
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-failures.ps1")

function Write-GateMessage {
    param([string]$Message)

    Write-Output "[verify-full] $Message"
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutMilliseconds = 3000
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne($TimeoutMilliseconds, $false)) {
            return $false
        }
        $client.EndConnect($connect)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

# 호출별 XML 격리와 실제 task 실행 증거 생성, 기존 결과 삭제 책임 없음
function New-MySqlVerificationAttempt {
    param([string]$BaseDirectory)

    $BaseDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BaseDirectory)
    $runId = [Guid]::NewGuid().ToString("N")
    $directory = Join-Path $BaseDirectory "mysql-$runId"
    if (Test-Path -LiteralPath $directory) {
        throw "통합 테스트 실행 디렉터리 충돌"
    }
    $directory = [System.IO.Directory]::CreateDirectory($directory).FullName
    $xmlDirectory = Join-Path $directory "xml"
    [System.IO.Directory]::CreateDirectory($xmlDirectory) | Out-Null
    $initPath = Join-Path $directory "mysql-results.init.gradle"
    # 경로를 Groovy 문자열에 삽입하지 않고 별도 system property로 전달
    $initText = @'
gradle.projectsEvaluated {
    def xmlDirectory = System.getProperty('pixelPlace.harness.xmlDirectory')
    def runId = System.getProperty('pixelPlace.harness.runId')
    if (!xmlDirectory || !runId) {
        throw new GradleException('Missing harness result configuration')
    }
    gradle.rootProject.tasks.named('mysqlIntegrationTest', org.gradle.api.tasks.testing.Test).configure {
        reports.junitXml.required.set(true)
        reports.junitXml.outputLocation.set(new File(xmlDirectory))
        reports.junitXml.mergeReruns.set(false)
        doFirst {
            logger.lifecycle('PIXEL_PLACE_MYSQL_RUN=' + runId)
        }
    }
}
'@
    [System.IO.File]::WriteAllText($initPath, $initText, [System.Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{
        RunId = $runId
        Directory = $directory
        XmlDirectory = $xmlDirectory
        InitPath = $initPath
        LogPath = (Join-Path $directory "verify-full-mysql-gradle.log")
        SummaryPath = (Join-Path $directory "mysql-result-summary.json")
    }
}

function Get-MySqlGradleArguments {
    param($Attempt)

    return @(
        "mysqlIntegrationTest", "--rerun-tasks", "--no-build-cache",
        "--no-configuration-cache", "--console=plain",
        "--init-script", $Attempt.InitPath,
        "-DpixelPlace.harness.xmlDirectory=$($Attempt.XmlDirectory)",
        "-DpixelPlace.harness.runId=$($Attempt.RunId)"
    )
}

function Invoke-MySqlGradle {
    param([string]$Wrapper, $Attempt)

    $gradleArguments = @(Get-MySqlGradleArguments -Attempt $Attempt)
    if ($env:PIXEL_PLACE_SUPERVISED -eq "1") { $gradleArguments += "--no-daemon" }
    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1의 native stderr 경고를 프로세스 실패와 구분
        $ErrorActionPreference = "Continue"
        & $Wrapper @gradleArguments 2>&1 |
            Tee-Object -FilePath $Attempt.LogPath -ErrorAction Stop |
            ForEach-Object { Write-Host $_ }
        $nativeExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    return $nativeExit
}

function Read-JUnitCount {
    param([System.Xml.XmlElement]$Suite, [string]$Name)

    $text = $Suite.GetAttribute($Name)
    $value = 0
    if ($text -notmatch '^[0-9]+$' -or -not [int]::TryParse($text, [ref]$value)) {
        throw [System.IO.InvalidDataException]::new("XML 집계 속성 누락/오류: $Name")
    }
    return $value
}

# 요약 숫자와 실제 testcase를 함께 확인, 필수 두 클래스 외 추가 suite도 동일 검증
function Read-MySqlTestResults {
    param([string]$XmlDirectory)

    $requiredSuites = @(
        "dev.cgt.pixelplace.flush.infra.FlushPersistenceMySqlIntegrationTest",
        "dev.cgt.pixelplace.recovery.infra.StartupRecoveryDbViewCaptureMySqlIntegrationTest"
    )
    $files = @(Get-ChildItem -LiteralPath $XmlDirectory -File -Filter "TEST-*.xml")
    if ($files.Count -eq 0) {
        throw [System.IO.InvalidDataException]::new("이번 실행의 테스트 XML 없음")
    }
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $summaries = [System.Collections.Generic.List[object]]::new()
    foreach ($file in $files) {
        $settings = [System.Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [System.Xml.XmlReader]::Create($file.FullName, $settings)
        try {
            $document = [System.Xml.XmlDocument]::new()
            $document.XmlResolver = $null
            $document.Load($reader)
        }
        finally {
            $reader.Dispose()
        }
        $suite = $document.DocumentElement
        # XML의 name 속성과 PowerShell adapter의 Name 충돌 회피
        if ($null -eq $suite -or $suite.get_LocalName() -cne "testsuite") {
            throw [System.IO.InvalidDataException]::new("testsuite 형식 아님: $($file.Name)")
        }
        $name = $suite.GetAttribute("name")
        if ([string]::IsNullOrWhiteSpace($name) -or -not $seen.Add($name)) {
            throw [System.IO.InvalidDataException]::new("suite 이름 누락/중복: $($file.Name)")
        }
        $tests = Read-JUnitCount -Suite $suite -Name "tests"
        $failures = Read-JUnitCount -Suite $suite -Name "failures"
        $errors = Read-JUnitCount -Suite $suite -Name "errors"
        $skipped = Read-JUnitCount -Suite $suite -Name "skipped"
        $cases = @($suite.SelectNodes("testcase"))
        if ($tests -le 0 -or $cases.Count -ne $tests -or
            $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw [System.IO.InvalidDataException]::new("실행 건수/실패/오류/skip 계약 불충족: $name")
        }
        foreach ($case in $cases) {
            if ($case.GetAttribute("classname") -cne $name -or
                [string]::IsNullOrWhiteSpace($case.GetAttribute("name")) -or
                $case.GetAttribute("name") -eq "initializationError" -or
                $case.SelectNodes("failure | error | skipped | flakyFailure | flakyError | rerunFailure | rerunError").Count -ne 0) {
                throw [System.IO.InvalidDataException]::new("실제 testcase 계약 불충족: $name")
            }
        }
        $summaries.Add([pscustomobject]@{
            Class = $name
            Tests = $tests
            Failures = $failures
            Errors = $errors
            Skipped = $skipped
            XmlFile = $file.FullName
            Sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        })
    }
    foreach ($required in $requiredSuites) {
        if (-not $seen.Contains($required)) {
            throw [System.IO.InvalidDataException]::new("필수 통합 테스트 XML 누락: $required")
        }
    }
    return $summaries.ToArray()
}

# Gradle nonzero를 XML로 뒤집지 않고, zero도 실행 marker와 새 XML 확인 후 인정
function Invoke-MySqlVerification {
    param([string]$Wrapper, [string]$BaseDirectory)

    $attempt = New-MySqlVerificationAttempt -BaseDirectory $BaseDirectory
    Write-Host "[verify-full] 실행 증거: $($attempt.Directory)"
    $gradleExit = Invoke-MySqlGradle -Wrapper $Wrapper -Attempt $attempt
    $gradleText = Get-Content -LiteralPath $attempt.LogPath -Raw
    if ($gradleExit -ne 0) {
        $failure = Get-GradleFailure -NativeExit $gradleExit -LogPath $attempt.LogPath -XmlDirectory $attempt.XmlDirectory -Database
        Write-FailureClassification -Failure $failure -Path (Join-Path $attempt.Directory "failure-classification.json")
        return $failure.exit_code
    }
    $marker = "(?m)^PIXEL_PLACE_MYSQL_RUN=" + [regex]::Escape($attempt.RunId) + "\r?$"
    if ([regex]::Matches([string]$gradleText, $marker).Count -ne 1) {
        Write-Host "[verify-full] 이번 통합 테스트 task의 실제 실행 증거 없음"
        return 10
    }
    try {
        $results = @(Read-MySqlTestResults -XmlDirectory $attempt.XmlDirectory)
    }
    catch [System.IO.InvalidDataException] {
        Write-Host "[verify-full] $($_.Exception.Message)"
        return 10
    }
    $summary = [pscustomobject]@{
        Status = "PASS"
        RunId = $attempt.RunId
        GradleExitCode = $gradleExit
        TotalTests = ($results | Measure-Object -Property Tests -Sum).Sum
        Suites = $results
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $attempt.SummaryPath -Encoding UTF8
    Write-Host "[verify-full] MySQL XML 확인: $($summary.TotalTests)건, 실패/오류/skip 0"
    return 0
}

try {
    $repoRoot = (& git rev-parse --show-toplevel 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
        Write-GateMessage "Git worktree를 확인할 수 없음"
        exit 30
    }
    Set-Location $repoRoot
    . (Join-Path $PSScriptRoot "harness-local-secrets.ps1")
    Import-HarnessLocalSecrets -Root $repoRoot

    if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
        $RunDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "pixel-place-harness-full"
    }
    $RunDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($RunDirectory)
    [System.IO.Directory]::CreateDirectory($RunDirectory) | Out-Null

    if (-not (Test-Path Env:DB_PASSWORD)) {
        Write-GateMessage "DB_PASSWORD 환경 변수가 없음"
        exit 20
    }
    if (-not (Test-TcpPort -HostName "127.0.0.1" -Port 3306)) {
        Write-GateMessage "localhost:3306의 MySQL에 연결할 수 없음"
        exit 20
    }

    if ($EnvironmentOnly) {
        Write-GateMessage "환경 preflight PASS"
        exit 0
    }

    $powerShellPath = (Get-Process -Id $PID).Path
    if (-not $SkipFast) {
        $fastScript = Join-Path $repoRoot "scripts/verify-fast.ps1"
        & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File $fastScript -RunDirectory $RunDirectory 2>&1
        $fastExit = ConvertTo-GateExit $LASTEXITCODE
        if ($fastExit -ne 0) {
            Write-GateMessage "빠른 gate 실패 (exit=$fastExit)"
            exit $fastExit
        }
    }

    $gradleWrapper = Join-Path $repoRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        Write-GateMessage "gradlew.bat 누락"
        exit 20
    }

    Write-GateMessage "MySQL 8.4.11 통합 테스트 실행"
    $verificationExit = Invoke-MySqlVerification -Wrapper $gradleWrapper -BaseDirectory $RunDirectory
    if ($verificationExit -ne 0) { exit $verificationExit }

    Write-GateMessage "PASS"
    exit 0
}
catch {
    Write-GateMessage "내부 오류: $($_.Exception.Message)"
    exit 30
}
