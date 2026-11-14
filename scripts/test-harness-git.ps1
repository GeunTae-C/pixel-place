[CmdletBinding()]
param()
# 실제 Git의 줄바꿈 경고와 공백 오류를 분리하고 production Fast의 baseline 비교까지 확인
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness-evidence.ps1')
. (Join-Path $PSScriptRoot 'harness-process.ps1')
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("git warning 한글 ' 검증 " + [Guid]::NewGuid().ToString('N'))
$passed = 0
$previousBaseline = [Environment]::GetEnvironmentVariable('PIXEL_PLACE_DIFF_BASELINE','Process')
function Assert-That { param([bool]$Condition,[string]$Message) if (-not $Condition) { throw $Message } }
function Pass { param([string]$Name) $script:passed++; Write-Host "[test-harness-git] PASS $Name" }
function Put { param([string]$Path,[string]$Text) [IO.File]::WriteAllText($Path,$Text,(New-Object Text.UTF8Encoding($false))) }
function Run-Fast {
    param([string]$Name,[int]$Expected,[int]$GradleCalls)
    $trace=Join-Path $script:fixture 'gradle-called.txt'
    if (Test-Path -LiteralPath $trace) { Remove-Item -LiteralPath $trace -Force }
    $dir=Join-Path $testRoot $Name
    $code=Invoke-SupervisedProcess -FilePath (Get-Process -Id $PID).Path -Arguments @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $script:fixture 'scripts/verify-fast.ps1'),'-RunDirectory',$dir) -Directory $script:fixture -OutputPath (Join-Path $dir 'fast.log') -TimeoutMilliseconds 30000 -Phase 'fast-git-fixture'
    Assert-That ($code -eq $Expected) "$Name Fast exit=$code expected=$Expected; 증거: $dir"
    $calls=if (Test-Path -LiteralPath $trace) { @(Get-Content -LiteralPath $trace).Count } else { 0 }
    Assert-That ($calls -eq $GradleCalls) "$Name 실패 뒤 Gradle 실행 또는 정상 검증 누락"
    Pass $Name
}
try {
    [IO.Directory]::CreateDirectory($testRoot) | Out-Null
    foreach ($file in @($PSCommandPath,(Join-Path $PSScriptRoot 'harness-worker.ps1'),(Join-Path $PSScriptRoot 'harness-evidence.ps1'),(Join-Path $PSScriptRoot 'verify-fast.ps1'))) {
        $tokens=$null;$errors=$null
        [Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$errors) | Out-Null
        Assert-That (@($errors).Count -eq 0) "구문 오류: $file / $errors"
    }
    Assert-That ($null -ne (Get-Command java -ErrorAction SilentlyContinue)) '기존 Java PATH 필요; fixture에서 Java를 실행하지는 않음'
    $script:fixture=Join-Path $testRoot 'repository'
    [IO.Directory]::CreateDirectory($script:fixture) | Out-Null
    Invoke-EvidenceGit $script:fixture @('init','--quiet') | Out-Null
    $tracked=Join-Path $script:fixture 'tracked.txt'
    Put $tracked "original`n"
    Invoke-EvidenceGit $script:fixture @('-c','core.autocrlf=false','add','tracked.txt') | Out-Null
    Invoke-EvidenceGit $script:fixture @('config','--local','core.autocrlf','true') | Out-Null
    Invoke-EvidenceGit $script:fixture @('config','--local','core.safecrlf','warn') | Out-Null
    Invoke-EvidenceGit $script:fixture @('config','--local','core.whitespace','blank-at-eol,blank-at-eof') | Out-Null
    Put $tracked "changed`n"
    # 실제 native stderr 발생 확인. 원래 Stop preference 경로와 달리 경고 수집 중에만 Continue
    $preference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $native=@(& git --no-optional-locks -C $script:fixture diff --check 2>&1)
        $nativeCode=$LASTEXITCODE
    }
    finally { $ErrorActionPreference=$preference }
    Assert-That ($nativeCode -eq 0 -and ($native -join "`n") -match 'LF will be replaced by CRLF') '줄바꿈 경고 fixture를 재현하지 못함'
    [IO.File]::WriteAllLines((Join-Path $testRoot 'native-warning.log'),[string[]]@($native | ForEach-Object { [string]$_ }),(New-Object Text.UTF8Encoding($false)))
    Assert-That (@(Get-EvidenceWhitespaceLines $script:fixture).Count -eq 0) '경고가 공백 오류 기준선에 혼입'
    Assert-That ($ErrorActionPreference -eq 'Stop') '전역 오류 처리 약화'
    Pass 'native-warning-separated'

    foreach ($part in @('scripts','src/test/java')) { [IO.Directory]::CreateDirectory((Join-Path $script:fixture $part)) | Out-Null }
    foreach ($name in @('verify-fast.ps1','harness-evidence.ps1','harness-failures.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $script:fixture "scripts/$name") }
    Put (Join-Path $script:fixture 'scripts/repo-hygiene.ps1') "[Console]::Error.WriteLine('fixture-hygiene-warning')`nexit 0`n"
    Put (Join-Path $script:fixture 'build.gradle') "excludeTags 'mysql-integration'`ntasks.register('mysqlIntegrationTest', Test)`nincludeTags 'mysql-integration'`n"
    Put (Join-Path $script:fixture 'src/test/java/Fixture.java') '@ActiveProfiles("mysql-integration") @Tag("mysql-integration")'
    [IO.File]::WriteAllText((Join-Path $script:fixture 'gradlew.bat'),"@echo off`r`necho called>>gradle-called.txt`r`nexit /b 0`r`n",[Text.Encoding]::ASCII)
    [Environment]::SetEnvironmentVariable('PIXEL_PLACE_DIFF_BASELINE',$null,'Process')
    Run-Fast 'warning-only-pass' 0 1

    Put $tracked "old  `n"
    $baseline=@(Get-EvidenceWhitespaceLines $script:fixture)
    Assert-That ($baseline.Count -gt 0 -and ($baseline -join "`n") -match 'trailing whitespace' -and ($baseline -join "`n") -notmatch 'warning:') '진짜 공백 오류 기준선 누락'
    $baselineFile=Join-Path $testRoot 'baseline.txt'
    [IO.File]::WriteAllLines($baselineFile,[string[]]$baseline,(New-Object Text.UTF8Encoding($false)))
    [Environment]::SetEnvironmentVariable('PIXEL_PLACE_DIFF_BASELINE',$baselineFile,'Process')
    Run-Fast 'preexisting-whitespace-pass' 0 1
    Put $tracked "old  `nnew  `n"
    Run-Fast 'new-whitespace-failed' 10 0

    Put $tracked "staged  `n"
    Invoke-EvidenceGit $script:fixture @('add','tracked.txt') | Out-Null
    [Environment]::SetEnvironmentVariable('PIXEL_PLACE_DIFF_BASELINE',$null,'Process')
    $staged=@(Get-EvidenceWhitespaceLines $script:fixture)
    Assert-That (@($staged | Where-Object { $_ -like 'staged|*' }).Count -gt 0) 'staged 공백 검사 누락'
    Run-Fast 'staged-whitespace-failed' 10 0

    $invalid=Join-Path $testRoot 'absent-repository'
    $rejected=$false
    try { Get-EvidenceWhitespaceLines $invalid | Out-Null }
    catch { $rejected=$_.Exception.Message -match 'Git 실패' }
    Assert-That $rejected '실제 Git 실패를 정상 또는 경고로 무시함'
    Pass 'git-failure-rejected'
    Assert-That ($passed -eq 6) 'Git 회귀 수 불일치'
    & (Get-Process -Id $PID).Path -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'test-harness-writer.ps1') -PathTests
    Assert-That ($LASTEXITCODE -eq 0) '기존 경로/Writer 47개 회귀 실패'
    Write-Host '[test-harness-git] PASS: 53 checks (git/Fast 6 + path/writer 47; 실제 Codex/API/DB 실행 없음)'
    Write-Host "[test-harness-git] 증거: $testRoot"
    exit 0
}
catch {
    Write-Host "[test-harness-git] FAIL: $($_.Exception.Message)"
    Write-Host "[test-harness-git] 증거: $testRoot"
    exit 1
}
finally { [Environment]::SetEnvironmentVariable('PIXEL_PLACE_DIFF_BASELINE',$previousBaseline,'Process') }
