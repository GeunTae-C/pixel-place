# 실패 근거의 출처를 구분하고 공통 gate 종료 계약 유지
function ConvertTo-GateExit {
    param([int]$Code)
    if ($Code -in @(0, 10, 20, 30)) { return $Code }
    return 30
}
function Get-GateStatus {
    param([int]$Code)
    switch (ConvertTo-GateExit $Code) {
        0 { return "PASS" }
        10 { return "FAILED" }
        20 { return "BLOCKED" }
        30 { return "HARNESS_ERROR" }
    }
}

function Get-DatabaseFailureReason {
    param([string]$Text)
    # 일반 테스트의 기대 문자열·임의 ConnectException을 DB 장애로 확대하지 않는 구체적 원인
    if ($Text -match '(?im)^\s*(?:Caused by:\s*)?(?:java\.sql\.SQLException|java\.sql\.SQLInvalidAuthorizationSpecException|com\.mysql\.[\w.]+Exception):\s*Access denied for user') { return "DB_AUTH" }
    if ($Text -match '(?im)^\s*(?:Caused by:\s*)?(?:java\.sql\.SQLSyntaxErrorException|java\.sql\.SQLException|com\.mysql\.[\w.]+Exception):\s*Unknown database') { return "DB_CATALOG" }
    if ($Text -match '(?im)^\s*(?:Caused by:\s*)?com\.mysql\.[\w.]*CommunicationsException:[^\r\n]*Communications link failure' -or
        ($Text -match 'com\.mysql\.cj\.' -and $Text -match '(?im)^\s*(?:Caused by:\s*)?java\.net\.(ConnectException|UnknownHostException):')) { return "DB_CONNECT" }
    return ""
}

function Get-GradleFailureBlock {
    param([string]$Text)
    $blocks = [regex]::Matches($Text, '(?ms)^\* What went wrong:\s*\r?\n(.*?)(?=^\* Try:|^\* Exception is:|^BUILD FAILED|\z)')
    if ($blocks.Count -eq 0) { return "" }
    # 가장 마지막 실제 Gradle 진단 구간 우선
    return $blocks[$blocks.Count - 1].Groups[1].Value
}

function Read-FailedTestReasons {
    param([string]$XmlDirectory)
    $reasons = New-Object 'System.Collections.Generic.List[string]'
    if (-not (Test-Path -LiteralPath $XmlDirectory -PathType Container)) { throw "현재 실행 XML 디렉터리 누락" }
    foreach ($file in Get-ChildItem -LiteralPath $XmlDirectory -File -Filter "TEST-*.xml") {
        $settings = New-Object Xml.XmlReaderSettings
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
        try {
            $document = New-Object Xml.XmlDocument
            $document.XmlResolver = $null
            $document.Load($reader)
        }
        finally { $reader.Dispose() }
        $suite = $document.DocumentElement
        if ($null -eq $suite -or $suite.get_LocalName() -cne "testsuite") { throw "실패 XML testsuite 형식 오류" }
        $suiteName = $suite.GetAttribute("name")
        if ([string]::IsNullOrWhiteSpace($suiteName)) { throw "실패 XML suite 이름 누락" }
        foreach ($attribute in @("tests", "failures", "errors", "skipped")) {
            $number = 0
            $raw = $suite.GetAttribute($attribute)
            if ($raw -notmatch '^[0-9]+$' -or -not [int]::TryParse($raw, [ref]$number)) { throw "실패 XML 집계 오류" }
            $selector = switch ($attribute) { "tests" { "testcase" }; "failures" { "testcase[failure]" }; "errors" { "testcase[error]" }; "skipped" { "testcase[skipped]" } }
            if ($suite.SelectNodes($selector).Count -ne $number) { throw "실패 XML 집계/실제 testcase 불일치" }
        }

        $knownMySqlSuite = $suiteName -in @(
            "dev.cgt.pixelplace.flush.infra.FlushPersistenceMySqlIntegrationTest",
            "dev.cgt.pixelplace.recovery.infra.StartupRecoveryDbViewCaptureMySqlIntegrationTest"
        )
        $suiteReasons = New-Object 'System.Collections.Generic.List[string]'
        foreach ($case in $suite.SelectNodes("testcase")) {
            foreach ($failure in $case.SelectNodes("failure | error")) {
                $text = $failure.GetAttribute("type") + ": " + $failure.GetAttribute("message") + "`n" + $failure.InnerText
                if ($knownMySqlSuite -and $text -match 'assertRequiredMySqlVersion|requireTestCatalog' -and
                    $text -match 'MariaDB is not accepted|Unexpected database product comment|MySQL semantic version must be 8\.4\.11|Destructive MySQL integration work is allowed only in pixel_place_test') {
                    $suiteReasons.Add("DB_CONTRACT"); continue
                }
                if ($failure.GetAttribute("type") -match 'Assertion(FailedError|Error)|ComparisonFailure') {
                    $suiteReasons.Add("CODE_OR_TEST"); continue
                }
                $reason = Get-DatabaseFailureReason $text
                if ($reason -ne "") { $suiteReasons.Add($reason); continue }
                # Hibernate Dialect 실패는 원인 자체가 아님. 같은 suite의 JDBC 원인 로그와 연결할 때만 환경 분류
                if ($text -match 'Unable to determine Dialect without JDBC metadata' -and
                    $text -match 'BeanCreationException|ServiceException|HibernateException') {
                    $suiteReasons.Add("BOOTSTRAP_UNKNOWN"); continue
                }
                if ($text -match 'ApplicationContext failure threshold .* exceeded') {
                    $suiteReasons.Add("CONTEXT_REUSE"); continue
                }
                $suiteReasons.Add("CODE_OR_TEST")
            }
        }
        # stdout에서 발견한 환경 신호로 일반 assertion 실패를 덮어쓰지 않음
        $bootstrapCause = ""
        if ($suiteReasons -contains "BOOTSTRAP_UNKNOWN" -or $suiteReasons -contains "CONTEXT_REUSE") {
            $output = @($suite.SelectNodes("system-out | system-err") | ForEach-Object { $_.InnerText }) -join "`n"
            $bootstrapCause = Get-DatabaseFailureReason $output
        }
        $hasEnvironment = @($suiteReasons | Where-Object { $_ -like "DB_*" }).Count -gt 0 -or $bootstrapCause -ne ""
        foreach ($reason in $suiteReasons) {
            if ($reason -eq "BOOTSTRAP_UNKNOWN") {
                if ($bootstrapCause -ne "") { $reasons.Add($bootstrapCause) }
                else { $reasons.Add("UNKNOWN_TEST_CAUSE") }
            }
            elseif ($reason -eq "CONTEXT_REUSE") {
                if (-not $hasEnvironment) { $reasons.Add("UNKNOWN_TEST_CAUSE") }
                elseif ($bootstrapCause -ne "") { $reasons.Add($bootstrapCause) }
            }
            else { $reasons.Add($reason) }
        }
    }
    return $reasons.ToArray()
}

# 분류기는 실패에만 적용하며 PASS 검증은 기존 gate 책임
function Get-GradleFailure {
    param([int]$NativeExit, [string]$LogPath, [string]$XmlDirectory = "", [switch]$Database)
    if ($NativeExit -eq 0) { throw "성공 종료를 실패 분류기에 전달함" }
    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) { throw "Gradle 실패 로그 누락" }
    $text = [string](Get-Content -LiteralPath $LogPath -Raw -ErrorAction Stop)
    $block = Get-GradleFailureBlock $text
    $reasons = @()
    if ($Database) { $reasons = @(Read-FailedTestReasons $XmlDirectory) }
    $code = 10
    $source = "gradle-failure-block"
    $reason = "UNKNOWN_FAILURE"
    if ($block -match "Compilation failed|Script compilation error|Could not compile (build|initialization)" -or
        $block -match "Task .*mysqlIntegrationTest.* not found|Missing harness result configuration") {
        $reason = "BUILD_OR_CONFIGURATION"
        if ($block -match "Task .*mysqlIntegrationTest.* not found|Missing harness result configuration|Could not compile initialization") { $code = 30; $reason = "HARNESS_TASK_CONFIGURATION" }
    }
    elseif ($reasons.Count -gt 0) {
        $source = "current-test-xml"
        if (@($reasons | Where-Object { $_ -notlike "DB_*" }).Count -eq 0) { $code = 20; $reason = "DATABASE_ENVIRONMENT" }
        else { $reason = "TEST_FAILURE_OR_MIXED_CAUSES" }
    }
    elseif (([string]::IsNullOrWhiteSpace($block) -and $text -notmatch '(?m)^> Task ' -and
        $text -match '(?m)^ERROR: JAVA_HOME is set to an invalid directory|^ERROR: JAVA_HOME is not set and no .java. command could be found') -or
        $block -match 'No matching toolchains found|Cannot find a Java installation|Could not find a Java installation') {
        $code = 20; $reason = "JDK_ENVIRONMENT"
        if ([string]::IsNullOrWhiteSpace($block)) { $source = "wrapper-startup" }
    }
    elseif ($block -match 'Could not resolve all (files|artifacts|dependencies)|Could not download' -and
        $block -match 'UnknownHostException|Network is unreachable|Connection refused|PKIX path building failed|timed out|Received status code (401|403|429|5[0-9][0-9])') {
        $code = 20; $reason = "DEPENDENCY_NETWORK"
    }
    elseif ([string]::IsNullOrWhiteSpace($block) -and $text -notmatch '(?m)^> Task ' -and
        $text -match 'org\.gradle\.wrapper\.' -and
        $text -match 'java\.net\.(UnknownHostException|ConnectException)|java\.net\.SocketException: Network is unreachable|PKIX path building failed') {
        $code = 20; $reason = "WRAPPER_NETWORK"; $source = "wrapper-startup"
    }
    elseif ($Database -and (Get-DatabaseFailureReason $block) -ne "") {
        $code = 20; $reason = Get-DatabaseFailureReason $block
    }
    return [pscustomobject]@{
        status = (Get-GateStatus $code)
        exit_code = $code
        native_exit_code = $NativeExit
        reason = $reason
        source = $source
        test_reasons = @($reasons | Sort-Object -Unique)
    }
}

function Write-FailureClassification {
    param($Failure, [string]$Path)
    # 원문 오류·계정·URL 대신 분류 ID와 종료 코드만 기록
    $Failure | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Path -Encoding UTF8 -ErrorAction Stop
    Write-Host "[failure] $($Failure.status) reason=$($Failure.reason) native=$($Failure.native_exit_code)"
}
