[CmdletBinding()]
param()
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "harness-process.ps1")
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-failures.ps1")

# 실제 DB/API 없이 원인 혼합·출처 오인·분류 전달과 기존 회귀 계약 검증
function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}
function Load-Functions {
    param([string]$Path)
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) { throw "대상 PowerShell 구문 오류" }
    return @($ast.EndBlock.Statements | Where-Object { $_ -is [System.Management.Automation.Language.FunctionDefinitionAst] })
}
function Put-FixtureXml {
    param([string]$Path, [object[]]$Failures, [string]$Output = "", [string]$SuiteName = "dev.cgt.pixelplace.flush.infra.FlushPersistenceMySqlIntegrationTest")
    $doc = New-Object Xml.XmlDocument
    $suite = $doc.CreateElement("testsuite"); $doc.AppendChild($suite) | Out-Null
    $suite.SetAttribute("name", $SuiteName)
    $suite.SetAttribute("tests", [string]$Failures.Count)
    $suite.SetAttribute("failures", [string]$Failures.Count)
    $suite.SetAttribute("errors", "0"); $suite.SetAttribute("skipped", "0")
    $i = 0
    foreach ($f in $Failures) {
        $case = $doc.CreateElement("testcase"); $case.SetAttribute("name", "case-$i"); $case.SetAttribute("classname", $SuiteName)
        $failure = $doc.CreateElement("failure"); $failure.SetAttribute("type", $f.Type); $failure.InnerText = $f.Text
        $case.AppendChild($failure) | Out-Null; $suite.AppendChild($case) | Out-Null; $i++
    }
    $out = $doc.CreateElement("system-out"); $out.InnerText = $Output; $suite.AppendChild($out) | Out-Null
    $doc.Save($Path)
}
function Prepare-Case {
    param($Case, [string]$LogPath, [string]$XmlDirectory)
    if ($Case.Name -ne "missing-log") {
        [IO.File]::WriteAllText($LogPath, $Case.Log, (New-Object Text.UTF8Encoding($false)))
    }
    if ($Case.Db) {
        $path = Join-Path $XmlDirectory "TEST-fixture.xml"
        Put-FixtureXml $path $Case.Failures $Case.Output $Case.Suite
        if ($Case.Name -eq "malformed") { [IO.File]::WriteAllText($path, "<testsuite>") }
        if ($Case.Name -eq "dtd") { [IO.File]::WriteAllText($path, '<!DOCTYPE testsuite [<!ENTITY x "test">]><testsuite/>') }
        if ($Case.Name -eq "count-mismatch") {
            [xml]$bad = Get-Content -LiteralPath $path -Raw; $bad.DocumentElement.SetAttribute("failures", "9"); $bad.Save($path)
        }
    }
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("failure harness 검증 " + [Guid]::NewGuid().ToString("N"))
$passed = 0; $success = $false
try {
    [IO.Directory]::CreateDirectory($testRoot) | Out-Null
    $auth = @{ Type = "java.sql.SQLException"; Text = "java.sql.SQLException: Access denied for user 'fixture-private-name'" }
    $syntax = @{ Type = "java.sql.SQLSyntaxErrorException"; Text = "java.sql.SQLSyntaxErrorException: You have an error in your SQL syntax" }
    $assertion = @{ Type = "org.opentest4j.AssertionFailedError"; Text = "expected:<1> but was:<2>" }
    $dialect = @{ Type = "org.hibernate.HibernateException"; Text = "org.hibernate.HibernateException: Unable to determine Dialect without JDBC metadata" }
    $reuse = @{ Type = "java.lang.IllegalStateException"; Text = "ApplicationContext failure threshold (1) exceeded" }
    $testLog = "* What went wrong:`nExecution failed for task ':mysqlIntegrationTest'.`nThere were failing tests.`n* Try:"
    $network = "* What went wrong:`nExecution failed for task ':compileJava'.`nCould not resolve all files for configuration ':testRuntimeClasspath'.`nCould not GET resource. UnknownHostException: fixture.invalid`n* Try:"
    $rawCases = @(
        @{ Name = "unknown"; Expected = 10 },
        @{ Name = "db-auth"; Db = $true; Failures = @($auth); Expected = 20 },
        @{ Name = "db-catalog"; Db = $true; Failures = @(@{ Type = "java.sql.SQLSyntaxErrorException"; Text = "java.sql.SQLSyntaxErrorException: Unknown database 'fixture'" }); Expected = 20 },
        @{ Name = "db-connect"; Db = $true; Failures = @(@{ Type = "com.mysql.cj.jdbc.exceptions.CommunicationsException"; Text = "com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure" }); Expected = 20 },
        @{ Name = "http-connect"; Db = $true; Failures = @(@{ Type = "java.net.ConnectException"; Text = "java.net.ConnectException: Connection refused" }); Expected = 10 },
        @{ Name = "assert-quotes-sql"; Db = $true; Failures = @(@{ Type = "org.opentest4j.AssertionFailedError"; Text = "expected:`njava.sql.SQLException: Access denied for user fixture" }); Expected = 10 },
        @{ Name = "sql-code"; Db = $true; Failures = @($syntax); Expected = 10 },
        @{ Name = "placeholder-code"; Db = $true; Failures = @(@{ Type = "java.lang.IllegalArgumentException"; Text = "Could not resolve placeholder 'app.typographical-error'" }); Expected = 10 },
        @{ Name = "dialect-unknown"; Db = $true; Failures = @($dialect); Expected = 10 },
        @{ Name = "dialect-auth-log"; Db = $true; Failures = @($dialect); Output = $auth.Text; Expected = 20 },
        @{ Name = "mixed"; Db = $true; Failures = @($auth, $assertion); Expected = 10 },
        @{ Name = "context-reuse"; Db = $true; Failures = @($reuse); Output = $auth.Text; Expected = 20 },
        @{ Name = "orphan-context"; Db = $true; Failures = @($reuse); Expected = 10 },
        @{ Name = "assert-with-db-stdout"; Db = $true; Failures = @($assertion); Output = $auth.Text; Expected = 10 },
        @{ Name = "db-version"; Db = $true; Failures = @(@{ Type = "org.opentest4j.AssertionFailedError"; Text = "MySQL semantic version must be 8.4.11. actual=other`n at fixture.assertRequiredMySqlVersion(test.java:1)" }); Expected = 20 },
        @{ Name = "version-unrelated-suite"; Db = $true; Suite = "example.OtherTest"; Failures = @(@{ Type = "org.opentest4j.AssertionFailedError"; Text = "MySQL semantic version must be 8.4.11.`n at fixture.assertRequiredMySqlVersion(test.java:1)" }); Expected = 10 },
        @{ Name = "db-safety-catalog"; Db = $true; Failures = @(@{ Type = "org.opentest4j.AssertionFailedError"; Text = "Destructive MySQL integration work is allowed only in pixel_place_test.`n at fixture.requireTestCatalog(test.java:1)" }); Expected = 20 },
        @{ Name = "dependency-network"; Log = $network; Expected = 20 },
        @{ Name = "dependency-coordinate"; Log = "* What went wrong:`nCould not resolve all files.`nCould not GET artifact. Received status code 404. Could not find fixture:invalid:1.0.`n* Try:"; Expected = 10 },
        @{ Name = "stdout-connect"; Log = "java.net.ConnectException: Connection refused`n$testLog"; Expected = 10 },
        @{ Name = "wrapper-network"; Log = "Exception in thread main java.net.SocketException: Network is unreachable`n at org.gradle.wrapper.Download.downloadInternal(Download.java:1)"; Expected = 20 },
        @{ Name = "jdk-toolchain"; Log = "* What went wrong:`nCannot find a Java installation matching requirements`n* Try:"; Expected = 20 },
        @{ Name = "jdk-wrapper"; Log = "ERROR: JAVA_HOME is set to an invalid directory"; Expected = 20 },
        @{ Name = "stdout-java-home"; Log = "> Task :test`nERROR: JAVA_HOME is set to an invalid directory`n$testLog"; Expected = 10 },
        @{ Name = "compile-with-network"; Log = "* What went wrong:`nExecution failed for task ':compileJava'.`nCompilation failed. Could not resolve all files. Could not GET.`n* Try:"; Expected = 10 },
        @{ Name = "missing-task"; Log = "* What went wrong:`nTask 'mysqlIntegrationTest' not found in root project.`n* Try:"; Expected = 30 },
        @{ Name = "init-script"; Log = "* What went wrong:`nCould not compile initialization script.`n* Try:"; Expected = 30 },
        @{ Name = "malformed"; Db = $true; Expected = 30; Error = "XML" },
        @{ Name = "dtd"; Db = $true; Expected = 30; Error = "XML" },
        @{ Name = "count-mismatch"; Db = $true; Failures = @($auth); Expected = 30; Error = "집계" },
        @{ Name = "missing-log"; Expected = 30; Error = "로그 누락" },
        @{ Name = "nonzero-with-no-failed-cases"; Db = $true; Expected = 10 }
    )
    $cases = @()
    foreach ($raw in $rawCases) {
        $case = @{ Name = ""; Expected = 10; Db = $false; Log = $testLog; Failures = @(); Output = ""; Suite = "dev.cgt.pixelplace.flush.infra.FlushPersistenceMySqlIntegrationTest"; Error = "" }
        foreach ($key in $raw.Keys) { $case[$key] = $raw[$key] }
        $cases += $case
        $directory = Join-Path $testRoot $case.Name; [IO.Directory]::CreateDirectory($directory) | Out-Null
        $xml = Join-Path $directory "xml"; [IO.Directory]::CreateDirectory($xml) | Out-Null
        $log = Join-Path $directory "gradle.log"
        Prepare-Case $case $log $xml
        try {
            $result = Get-GradleFailure -NativeExit 1 -LogPath $log -XmlDirectory $xml -Database:$case.Db
            Assert-That ($case.Error -eq "") "예상 예외 누락"
            Assert-That ($result.exit_code -eq $case.Expected) "$($case.Name): 예상=$($case.Expected), 실제=$($result.exit_code)"
            Assert-That ($result.status -eq (Get-GateStatus $case.Expected)) "status/exit 불일치"
            $safeJson = $result | ConvertTo-Json -Depth 6
            Assert-That (-not $safeJson.Contains("fixture-private-name")) "분류 JSON에 원문 계정 노출"
        }
        catch {
            if ($case.Error -eq "") { throw }
            if ($case.Error -eq "XML") {
                $cause = $_.Exception
                while ($null -ne $cause -and $cause -isnot [Xml.XmlException]) { $cause = $cause.InnerException }
                Assert-That ($null -ne $cause) "XML 외의 예상하지 못한 예외"
            }
            else { Assert-That ($_.Exception.Message.Contains($case.Error)) "예상하지 못한 예외" }
        }
        $passed++; Write-Host "[test-harness-failures] PASS classify/$($case.Name)"
    }

    foreach ($definition in Load-Functions (Join-Path $PSScriptRoot "verify-fast.ps1")) { . ([scriptblock]::Create($definition.Extent.Text)) }
    $wrapper = Join-Path $testRoot "fake native.cmd"
    foreach ($code in @(0, 7)) {
        [IO.File]::WriteAllText($wrapper, "@echo off`r`necho fixture-warning 1>&2`r`nexit /b $code`r`n", [Text.Encoding]::ASCII)
        $actual = Invoke-LoggedNative -FilePath $wrapper -Arguments @() -LogPath (Join-Path $testRoot "native.log")
        Assert-That ($actual -eq $code) "Fast native stderr/exit 수집 실패"
        $passed++; Write-Host "[test-harness-failures] PASS fast-native/$code"
    }
    $definition = @(Load-Functions (Join-Path $PSScriptRoot "harness-worker.ps1") | Where-Object { $_.Name -eq "Invoke-PowerShellScript" })[0]
    . ([scriptblock]::Create($definition.Extent.Text))
    $script:PowerShellPath = (Get-Process -Id $PID).Path
    foreach ($code in @(0, 10, 20, 30, 7)) {
        $child = Join-Path $testRoot "child.ps1"; [IO.File]::WriteAllText($child, "exit $code")
        $actual = Invoke-PowerShellScript -ScriptPath $child -Arguments @() -LogPath (Join-Path $testRoot "child.log")
        $expected = if ($code -eq 7) { 30 } else { $code }
        Assert-That ($actual -eq $expected) "supervisor child exit 정규화 실패"
        $passed++; Write-Host "[test-harness-failures] PASS supervisor-exit/$code"
    }

    foreach ($definition in Load-Functions (Join-Path $PSScriptRoot "verify-full.ps1")) { . ([scriptblock]::Create($definition.Extent.Text)) }
    function Invoke-MySqlGradle {
        param([string]$Wrapper, $Attempt)
        $script:attempt = $Attempt
        Prepare-Case $script:fullCase $Attempt.LogPath $Attempt.XmlDirectory
        return 1
    }
    foreach ($name in @("db-auth", "dialect-auth-log", "mixed", "placeholder-code", "db-version", "dependency-network", "missing-task", "unknown")) {
        $script:fullCase = @($cases | Where-Object { $_.Name -eq $name })[0]
        $actual = Invoke-MySqlVerification -Wrapper "unused" -BaseDirectory $testRoot
        Assert-That ($actual -eq $script:fullCase.Expected) "Full classifier 연결 실패: $name"
        $record = Get-Content -LiteralPath (Join-Path $script:attempt.Directory "failure-classification.json") -Raw | ConvertFrom-Json
        Assert-That ($record.exit_code -eq $actual) "Full 분류 증거/exit 불일치"
        Assert-That (-not (Test-Path -LiteralPath $script:attempt.SummaryPath)) "실패에 PASS summary 생성"
        $passed++; Write-Host "[test-harness-failures] PASS full-flow/$name"
    }
    $ps = (Get-Process -Id $PID).Path
    & $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "test-verify-full.ps1")
    if ($LASTEXITCODE -ne 0) { throw "기존 Full 34개 검증 실패" }
    & $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "test-harness-evidence.ps1")
    if ($LASTEXITCODE -ne 0) { throw "기존 증거/Writer 76개 검증 실패" }
    Assert-That ($passed -eq 47) "5단계 검증 수 불일치"
    $success = $true
    Write-Host "[test-harness-failures] PASS: 157 checks (failure 47 + full 34 + evidence/writer 76; 실제 Codex/API/DB 실행 없음)"
    exit 0
}
catch {
    Write-Host "[test-harness-failures] FAIL: $($_.Exception.Message)"
    Write-Host "[test-harness-failures] 증거: $testRoot"
    exit 1
}
finally {
    if ($success -and (Test-Path -LiteralPath $testRoot)) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
