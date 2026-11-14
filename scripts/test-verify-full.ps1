[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-failures.ps1")

# 실제 DB/Gradle 없이 Full의 결과 판정·native exit·과거 결과 차단 경로 검증
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("pixel harness 검증 " + [Guid]::NewGuid().ToString("N"))
$passed = 0

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Write-FixtureSuite {
    param([string]$Path, [string]$Name)
    $xml = "<testsuite name='$Name' tests='1' failures='0' errors='0' skipped='0'><testcase name='passes()' classname='$Name'/></testsuite>"
    [System.IO.File]::WriteAllText($Path, $xml, [System.Text.UTF8Encoding]::new($false))
}

try {
    [System.IO.Directory]::CreateDirectory($testRoot) | Out-Null
    $target = Join-Path $PSScriptRoot "verify-full.ps1"
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($target, [ref]$tokens, [ref]$parseErrors)
    Assert-That (@($parseErrors).Count -eq 0) "verify-full.ps1 구문 오류"

    # main/preflight/exit 실행 없이 실제 production 함수 정의만 로드
    foreach ($statement in $ast.EndBlock.Statements) {
        if ($statement -is [System.Management.Automation.Language.FunctionDefinitionAst]) {
            . ([scriptblock]::Create($statement.Extent.Text))
        }
    }

    $nativeAttempt = New-MySqlVerificationAttempt -BaseDirectory $testRoot
    $arguments = @(Get-MySqlGradleArguments -Attempt $nativeAttempt)
    foreach ($flag in @("mysqlIntegrationTest", "--rerun-tasks", "--no-build-cache", "--no-configuration-cache", "--console=plain", "--init-script")) {
        Assert-That ($arguments -contains $flag) "Gradle 필수 인자 누락: $flag"
    }
    Assert-That ($arguments -contains $nativeAttempt.InitPath) "공백/한글 init 경로 인자 손실"
    Assert-That ($arguments -contains "-DpixelPlace.harness.xmlDirectory=$($nativeAttempt.XmlDirectory)") "XML 경로 인자 손실"
    $passed++

    # PowerShell location과 .NET current directory가 달라도 결과 경로 일치
    Push-Location -LiteralPath $testRoot
    try {
        $relativeAttempt = New-MySqlVerificationAttempt -BaseDirectory ".\relative results"
        $expectedParent = Join-Path $testRoot "relative results"
        Assert-That ((Split-Path -Parent $relativeAttempt.Directory) -eq $expectedParent) "상대 RunDirectory 해석 불일치"
    }
    finally { Pop-Location }
    $passed++

    # 실제 native stderr/exit 수집 검증, Java나 MySQL 실행 없음
    $wrapper = Join-Path $testRoot "fake gradle.cmd"
    foreach ($nativeCode in @(0, 7)) {
        [System.IO.File]::WriteAllText($wrapper, "@echo off`r`necho fixture-warning 1>&2`r`nexit /b $nativeCode`r`n", [System.Text.Encoding]::ASCII)
        $code = Invoke-MySqlGradle -Wrapper $wrapper -Attempt $nativeAttempt
        Assert-That ($code -eq $nativeCode) "native exit 보존 실패: expected=$nativeCode actual=$code"
        Assert-That ((Get-Content -LiteralPath $nativeAttempt.LogPath -Raw) -match 'fixture-warning') "native stderr 로그 누락"
        $passed++
    }

    $script:flushClass = "dev.cgt.pixelplace.flush.infra.FlushPersistenceMySqlIntegrationTest"
    $script:recoveryClass = "dev.cgt.pixelplace.recovery.infra.StartupRecoveryDbViewCaptureMySqlIntegrationTest"
    $script:scenario = ""
    $script:observedAttempt = $null

    # 프로세스 경계만 대체하고 실제 attempt 생성·XML parser·최종 gate 함수 사용
    function Invoke-MySqlGradle {
        param([string]$Wrapper, $Attempt)
        $script:observedAttempt = $Attempt
        $flushFile = Join-Path $Attempt.XmlDirectory "TEST-flush.xml"
        $recoveryFile = Join-Path $Attempt.XmlDirectory "TEST-recovery.xml"
        $marker = "PIXEL_PLACE_MYSQL_RUN=$($Attempt.RunId)"
        switch ($script:scenario) {
            "no-marker" { $marker = "BUILD SUCCESSFUL" }
            "cached" { $marker = "> Task :mysqlIntegrationTest FROM-CACHE" }
            "up-to-date" { $marker = "> Task :mysqlIntegrationTest UP-TO-DATE" }
            "no-source" { $marker = "> Task :mysqlIntegrationTest NO-SOURCE" }
            "wrong-marker" { $marker = "PIXEL_PLACE_MYSQL_RUN=previous-run" }
            "duplicate-marker" { $marker = "$marker`r`n$marker" }
            "environment-failure" { $marker = "* What went wrong:`njava.sql.SQLException: Access denied for user fixture`n* Try:" }
        }
        $marker | Set-Content -LiteralPath $Attempt.LogPath -Encoding UTF8
        if ($script:scenario -notin @("empty", "stale-prior")) {
            Write-FixtureSuite -Path $flushFile -Name $script:flushClass
            Write-FixtureSuite -Path $recoveryFile -Name $script:recoveryClass
        }
        if ($script:scenario -eq "stale-prior") {
            # 성공 XML을 새 attempt의 바깥에 남겨도 읽지 않아야 함
            Write-FixtureSuite -Path (Join-Path $testRoot "TEST-old-flush.xml") -Name $script:flushClass
            Write-FixtureSuite -Path (Join-Path $testRoot "TEST-old-recovery.xml") -Name $script:recoveryClass
        }
        switch ($script:scenario) {
            "missing-suite" { Remove-Item -LiteralPath $recoveryFile }
            "duplicate-suite" { Copy-Item -LiteralPath $flushFile -Destination (Join-Path $Attempt.XmlDirectory "TEST-copy.xml") }
            "extra-suite" { Write-FixtureSuite -Path (Join-Path $Attempt.XmlDirectory "TEST-extra.xml") -Name "example.MoreIntegrationTest" }
            "malformed" { [System.IO.File]::WriteAllText($flushFile, "<testsuite>") }
            "dtd" { [System.IO.File]::WriteAllText($flushFile, '<!DOCTYPE testsuite [<!ENTITY x "unused">]><testsuite/>') }
            { $_ -in @("zero", "skip", "failure-count", "error-count", "case-skip", "case-failure", "case-error", "flaky", "count-mismatch", "missing-count", "negative-count", "classname", "initialization", "wrong-root") } {
                [xml]$document = Get-Content -LiteralPath $flushFile -Raw
                $suite = $document.DocumentElement
                $case = $suite.SelectSingleNode("testcase")
                switch ($script:scenario) {
                    "zero" { $suite.SetAttribute("tests", "0"); $suite.RemoveChild($case) | Out-Null }
                    "skip" { $suite.SetAttribute("skipped", "1") }
                    "failure-count" { $suite.SetAttribute("failures", "1") }
                    "error-count" { $suite.SetAttribute("errors", "1") }
                    "case-skip" { $case.AppendChild($document.CreateElement("skipped")) | Out-Null }
                    "case-failure" { $case.AppendChild($document.CreateElement("failure")) | Out-Null }
                    "case-error" { $case.AppendChild($document.CreateElement("error")) | Out-Null }
                    "flaky" { $case.AppendChild($document.CreateElement("flakyFailure")) | Out-Null }
                    "count-mismatch" { $suite.SetAttribute("tests", "2") }
                    "missing-count" { $suite.RemoveAttribute("skipped") }
                    "negative-count" { $suite.SetAttribute("tests", "-1") }
                    "classname" { $case.SetAttribute("classname", "unrelated.Test") }
                    "initialization" { $case.SetAttribute("name", "initializationError") }
                    "wrong-root" { $document.LoadXml("<testsuites/>") }
                }
                $document.Save($flushFile)
            }
        }
        if ($script:scenario -in @("gradle-failure", "environment-failure")) { return 1 }
        return 0
    }

    $cases = [ordered]@{
        "success" = 0; "extra-suite" = 0
        "empty" = 10; "missing-suite" = 10; "zero" = 10
        "skip" = 10; "failure-count" = 10; "error-count" = 10
        "case-skip" = 10; "case-failure" = 10; "case-error" = 10; "flaky" = 10
        "count-mismatch" = 10; "missing-count" = 10; "negative-count" = 10
        "classname" = 10; "initialization" = 10; "wrong-root" = 10
        "duplicate-suite" = 10; "malformed" = 30; "dtd" = 30
        "no-marker" = 10; "wrong-marker" = 10; "duplicate-marker" = 10
        "cached" = 10; "up-to-date" = 10; "no-source" = 10
        "gradle-failure" = 10; "environment-failure" = 20; "stale-prior" = 10
    }
    $previousDirectory = $null
    foreach ($entry in $cases.GetEnumerator()) {
        $script:scenario = $entry.Key
        try {
            $actual = Invoke-MySqlVerification -Wrapper "unused" -BaseDirectory $testRoot
        }
        catch {
            # main의 내부 오류 exit 30 경계. 예상하지 않은 예외도 성공으로 처리하지 않음
            if ($entry.Value -ne 30) { throw }
            $cause = $_.Exception
            while ($null -ne $cause -and $cause -isnot [System.Xml.XmlException]) {
                $cause = $cause.InnerException
            }
            Assert-That ($null -ne $cause) "XML 파싱 오류 외의 예외 발생: $($entry.Key)"
            $actual = 30
        }
        Assert-That ($actual -eq $entry.Value) "$($entry.Key): expected=$($entry.Value) actual=$actual"
        Assert-That ($script:observedAttempt.Directory -ne $previousDirectory) "재호출의 실행 디렉터리 재사용"
        $previousDirectory = $script:observedAttempt.Directory
        $hasSummary = Test-Path -LiteralPath $script:observedAttempt.SummaryPath
        Assert-That ($hasSummary -eq ($entry.Value -eq 0)) "$($entry.Key): 실패에 PASS summary 생성 또는 성공 summary 누락"
        if ($hasSummary) {
            $summary = Get-Content -LiteralPath $script:observedAttempt.SummaryPath -Raw | ConvertFrom-Json
            $expectedTotal = if ($entry.Key -eq "extra-suite") { 3 } else { 2 }
            Assert-That ($summary.TotalTests -eq $expectedTotal) "실제 테스트 합계 불일치"
            Assert-That ($summary.RunId -eq $script:observedAttempt.RunId) "summary 실행 ID 불일치"
        }
        $passed++
        Write-Host "[test-verify-full] PASS $($entry.Key)"
    }
    Write-Host "[test-verify-full] PASS: $passed checks (실제 DB/Gradle 실행 없음)"
    exit 0
}
catch {
    Write-Host "[test-verify-full] FAIL: $($_.Exception.Message)"
    exit 1
}
finally {
    # 이 검증이 만든 임시 fixture만 정리
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
