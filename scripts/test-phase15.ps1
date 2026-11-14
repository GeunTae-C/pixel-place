param([string]$Label, [string[]]$Tasks, [string]$TaskSpec)
if ($TaskSpec) { $Tasks = $TaskSpec -split '\|' }
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'phase15-inputs.ps1')
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
$phase15Root = 'E:/pixel-place-phase15/phase15-a-20260918'
$env:GRADLE_USER_HOME = Join-Path $phase15Root 'gradle-home'
$env:TEMP = Join-Path $phase15Root 'tmp'
$env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $env:TEMP
$evidence = Join-Path (Join-Path $phase15Root 'agent-runs') $Label
if (Test-Path -LiteralPath $evidence) { throw 'Evidence label already exists' }
$maximumBytes = 25GB
$ancillary = 67108864L
$legacy = [long](Get-Content -LiteralPath "$phase15Root/legacy-budget-charge.json" -Raw | ConvertFrom-Json).chargedBytes
$carriedCharge = 0L
if ($Label -like 'phase15-C-*') {
    $budget = Get-Content -Raw -Encoding UTF8 -LiteralPath 'scripts/phase15-c-budget.json' | ConvertFrom-Json
    . (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
    Assert-Phase15CBudget $budget
    $maximumBytes = [long]$budget.maximumBytes
    $carriedCharge = [long]$budget.carriedChargeBytes
    if ($carriedCharge -lt 36960813040L -or $carriedCharge -gt $maximumBytes) { throw 'C carried cumulative charge is missing or invalid' }
}
$ownedBytes = [long](Get-ChildItem -LiteralPath $phase15Root -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum
$reserve = if ($Tasks -contains 'mysqlIntegrationTest') { 5GB } else { 512MB }
# C-3 동일 필수 회귀 두 번의 실제 증가 1,701,336,142 / 1,688,453,915 bytes 근거.
# 새 추정 2.25GiB(최대 실측 + 714,582,962 bytes 여유). 누적 36GiB 및 E:5GiB 경계는 유지.
if ($Label -like 'phase15-C-3-*' -and $Tasks -contains 'mysqlIntegrationTest') { $reserve = 2304MB }
# decimal 합산으로 overflow 및 이전 환경/소규모 부대 쓰기 누락 차단
$chargedBytes = if ($Label -like 'phase15-C-*') { (Save-Phase15CCharge).chargedBytes } else { [decimal]$ownedBytes + $legacy + $ancillary }
if ($chargedBytes + $reserve -gt $maximumBytes -or ([IO.DriveInfo]::new('E:')).AvailableFreeSpace -lt 5GB + $reserve) { throw 'Regression storage budget insufficient; existing data preserved' }
New-Item -ItemType Directory -Path $evidence | Out-Null
[pscustomobject]@{OwnedBytes=$ownedBytes;LegacyCharge=$legacy;AncillaryCharge=$ancillary;CarriedCharge=$carriedCharge;ChargedBytes=$chargedBytes;ReservedBytes=$reserve;MaximumBytes=$maximumBytes;FreeBytes=([IO.DriveInfo]::new('E:')).AvailableFreeSpace} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$evidence/budget-before.json"
$env:PIXEL_PLACE_REGRESSION_EVIDENCE = $evidence
if ($Tasks -contains 'redisIntegrationTest') {
    $env:PIXEL_PLACE_TEST_REDIS_DATABASE = '1'
}
if ($Tasks -contains 'mysqlIntegrationTest') {
    . ./scripts/harness-local-secrets.ps1
    Import-HarnessLocalSecrets -Root (Get-Location).Path
    $application = Get-Content -LiteralPath 'src/main/resources/application.yml' -Raw -Encoding UTF8
    $dbUserMatches = [regex]::Matches($application, '(?m)^    username: ([A-Za-z0-9_]+)\s*$')
    if ($dbUserMatches.Count -ne 1 -or -not $env:DB_PASSWORD) { throw 'Existing safe credential injection required' }
    $env:PIXEL_PLACE_BENCH_DB_USERNAME = $dbUserMatches[0].Groups[1].Value
    $mysqlDriver = @(Get-ChildItem -LiteralPath "$phase15Root/gradle-home/caches/modules-2/files-2.1/com.mysql/mysql-connector-j" -Filter '*.jar' -Recurse -File | Where-Object { $_.Name -match '^mysql-connector-j-[0-9.]+\.jar$' })
    if ($mysqlDriver.Count -ne 1) { throw 'Exact existing JDBC driver required' }
    & "$env:JAVA_HOME/bin/java.exe" -cp $mysqlDriver[0].FullName scripts/Phase15RegressionDatabase.java prepare
    if ($LASTEXITCODE -ne 0) { throw 'E regression catalog/storage preflight failed' }
}
$startedUtc = [DateTime]::UtcNow
$startedUtc.ToString('o') | Set-Content -Encoding UTF8 -LiteralPath "$evidence/started-utc.txt"
$testArgs = @($Tasks) + @('--rerun-tasks','--offline','--no-daemon','--console=plain','--project-cache-dir',(Join-Path $phase15Root 'project-cache'),('-Pphase15BuildRoot='+(Join-Path $phase15Root 'build')),('-Pphase15TempRoot='+$env:TEMP))
if ($Tasks -contains 'mysqlIntegrationTest') { $testArgs += '-Pphase15MySqlRegression=3307' }
$testArgs | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$evidence/command.json"
$inputBefore = @(Get-Phase15ExecutionInputs (Get-Location).Path $Label)
$inputBefore | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 -LiteralPath "$evidence/execution-input.json"
$ErrorActionPreference = 'Continue'
& ./gradlew.bat @testArgs 2>&1 | ForEach-Object {
    $line = [string]$_
    if ($line -match '(?i)jdbc:|DATABASE=|Database (URL|driver|version|info)|password|authorization:|set-cookie:|"eventSeq"|"createdAt"') { '[sensitive or payload line omitted]' }
    else { $line }
} | Tee-Object -FilePath "$evidence/gradle.log"
$testExit = $LASTEXITCODE
$gradleExit = $testExit
$ErrorActionPreference = 'Stop'
if ($Tasks -contains 'mysqlIntegrationTest') {
    & "$env:JAVA_HOME/bin/java.exe" -cp $mysqlDriver[0].FullName scripts/Phase15RegressionDatabase.java verify-after
    $storageExit = $LASTEXITCODE
    $storageExit | Set-Content -Encoding UTF8 -LiteralPath "$evidence/storage-verification-exit.txt"
    if ($testExit -eq 0 -and $storageExit -ne 0) { $testExit = $storageExit }
    $afterBytes = (Get-ChildItem -LiteralPath $phase15Root -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum
    [pscustomobject]@{BeforeOwnedBytes=$ownedBytes;AfterOwnedBytes=$afterBytes;LegacyCharge=$legacy;AncillaryCharge=$ancillary;CarriedCharge=$carriedCharge;TotalCharged=$(if ($Label -like 'phase15-C-*') { (Save-Phase15CCharge).chargedBytes } else { [decimal]$afterBytes+$legacy+$ancillary });FreeBytes=([IO.DriveInfo]::new('E:')).AvailableFreeSpace;MaximumBytes=$maximumBytes;MinimumFreeBytes=5GB;InstancePort=3307;Catalog='pixel_place_test';Original3306Executed=$false} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$evidence/storage-budget.json"
}
$afterCharge = if ($Label -like 'phase15-C-*') { (Save-Phase15CCharge).chargedBytes } else { [decimal](Get-ChildItem -LiteralPath $phase15Root -Recurse -File -Force | Measure-Object Length -Sum).Sum + $legacy + $ancillary }
if ($afterCharge -gt $maximumBytes -or ([IO.DriveInfo]::new('E:')).AvailableFreeSpace -lt 5GB) { $testExit = 1 }
$gradleExit | Set-Content -Encoding UTF8 -LiteralPath "$evidence/gradle-exit-code.txt"
[DateTime]::UtcNow.ToString('o') | Set-Content -Encoding UTF8 -LiteralPath "$evidence/finished-utc.txt"
$rows = @(foreach($task in @('test','redisIntegrationTest','mysqlIntegrationTest')) {
    if ($Tasks -contains $task) {
        $xmlDir = (Join-Path $phase15Root "build/test-results/$task")
        if (Test-Path -LiteralPath $xmlDir) {
            New-Item -ItemType Directory -Path "$evidence/$task" | Out-Null
            foreach($file in Get-ChildItem -LiteralPath $xmlDir -Filter '*.xml' -File) {
                # compile 실패 뒤 남은 이전 XML을 이번 실행 증거로 복사하지 않음
                if ($file.LastWriteTimeUtc -lt $startedUtc) { continue }
                [xml]$xml = Get-Content -Raw -LiteralPath $file.FullName
                $rawHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
                foreach ($node in $xml.SelectNodes('/testsuite/system-out | /testsuite/system-err')) {
                    $node.InnerText = (($node.InnerText -split '\r?\n' | ForEach-Object {
                        if ($_ -match '(?i)jdbc:|DATABASE=|Database (URL|driver|version|info)|password|authorization:|set-cookie:|"eventSeq"|"createdAt"') { '[sensitive or payload line omitted]' } else { $_ }
                    }) -join "`n")
                }
                $xml.Save("$evidence/$task/$($file.Name)")
                [pscustomobject]@{File=$file.Name;RawSha256=$rawHash;SavedSha256=(Get-FileHash -LiteralPath "$evidence/$task/$($file.Name)" -Algorithm SHA256).Hash} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$evidence/$task/$($file.Name).hash.json"
                [pscustomobject]@{Task=$task;Suite=$xml.testsuite.name;Tests=[int]$xml.testsuite.tests;Failures=[int]$xml.testsuite.failures;Errors=[int]$xml.testsuite.errors;Skipped=[int]$xml.testsuite.skipped;Testcases=@($xml.testsuite.testcase).Count}
            }
        }
    }
})
foreach ($task in @('test','redisIntegrationTest','mysqlIntegrationTest')) {
    if ($Tasks -contains $task) {
        $taskRows = @($rows | Where-Object Task -eq $task)
        if ($taskRows.Count -eq 0 -or ($taskRows | Measure-Object Tests -Sum).Sum -eq 0 -or @($taskRows | Where-Object { $_.Tests -ne $_.Testcases -or $_.Failures -ne 0 -or $_.Errors -ne 0 -or $_.Skipped -ne 0 }).Count -ne 0) { $testExit = 1 }
    }
}
$testExit | Set-Content -Encoding UTF8 -LiteralPath "$evidence/runner-exit-code.txt"
# 실행 뒤 새로 열거하여 파일 추가까지 검출. 삭제된 필수 입력도 manifest/diff로 보존
$inputAfter = @(Get-Phase15InputPaths (Get-Location).Path $Label | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    ForEach-Object { [pscustomobject]@{Path=$_;Sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} })
$inputAfter | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 -LiteralPath "$evidence/execution-input-after.json"
$inputDifference = Compare-Phase15ExecutionInputs $inputBefore $inputAfter
if (-not $inputDifference.Exact) { $testExit = 1 }
$inputDifference | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath "$evidence/input-preservation.json"
$testExit | Set-Content -Encoding UTF8 -LiteralPath "$evidence/runner-exit-code.txt"
[pscustomobject]@{Exit=$testExit;GradleExit=$gradleExit;Suites=$rows} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath "$evidence/results.json"
Write-Output "RECORDED_GRADLE_EXIT=$testExit"
exit $testExit
