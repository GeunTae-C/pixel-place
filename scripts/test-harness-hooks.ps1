[CmdletBinding()]
param()

# 등록된 Windows Stop 명령을 임시 Git 저장소에서 호출하고 실제 CLI로 규칙 판정 확인
# Codex 모델/API/DB 호출 없음. 실제 Codex 이벤트 발화와 신뢰 상태는 9단계에서 확인
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harness-process.ps1")
$repoRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("hook harness 한글 ' 검증 " + [Guid]::NewGuid().ToString("N"))
$passed = 0
$powerShellPath = (Get-Process -Id $PID).Path
function Assert-That { param([bool]$Condition,[string]$Message) if (-not $Condition) { throw $Message } }
function Pass { param([string]$Name) $script:passed++; Write-Host "[test-harness-hooks] PASS $Name" }
try {
    [IO.Directory]::CreateDirectory($testRoot) | Out-Null
    $codexCommand = Get-Command codex -ErrorAction SilentlyContinue
    Assert-That ($null -ne $codexCommand) "Codex CLI가 PATH에 필요함. 이 검증은 execpolicy check만 호출하며 로그인/API는 사용하지 않음"
    $codexPath = $codexCommand.Source
    if ([string]::IsNullOrWhiteSpace($codexPath)) { $codexPath = $codexCommand.Path }
    foreach ($path in @($PSCommandPath,(Join-Path $repoRoot '.codex/hooks/stop_gate.ps1'),(Join-Path $PSScriptRoot 'harness-worker.ps1'),(Join-Path $PSScriptRoot 'test-harness-writer.ps1'))) {
        $tokens=$null; $errors=$null
        [Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors) | Out-Null
        Assert-That (@($errors).Count -eq 0) "PowerShell 구문 오류: $path / $errors"
    }
    $config = Get-Content -LiteralPath (Join-Path $repoRoot '.codex/hooks.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-That (@($config.hooks.Stop).Count -eq 1 -and @($config.hooks.Stop[0].hooks).Count -eq 1) "Stop handler 수 변경"
    $handler = $config.hooks.Stop[0].hooks[0]
    Assert-That ($handler.type -eq 'command' -and $handler.timeout -eq 900 -and -not [string]::IsNullOrWhiteSpace($handler.commandWindows)) "Windows Stop 설정 계약 오류"
    $configHash = (Get-FileHash -LiteralPath (Join-Path $repoRoot '.codex/hooks.json') -Algorithm SHA256).Hash
    [IO.File]::WriteAllText((Join-Path $testRoot 'hooks-config.sha256'),$configHash)
    Pass 'config/parser-and-handler'

    $hookCases = @(
        @{Name='disabled';Input='{bad';Hook=$false;Code=0;Warning=$false;Kind='empty';Calls=0},
        @{Name='recursive';Input='{"stop_hook_active":true}';Hook=$true;Code=10;Warning=$false;Kind='empty';Calls=0},
        @{Name='success';Input='{"stop_hook_active":false,"session_id":"00000000-0000-0000-0000-000000000001","turn_id":"00000000-0000-0000-0000-000000000002"}';Hook=$true;Code=0;Warning=$false;Kind='empty';Calls=1},
        @{Name='warning-success';Input='{"stop_hook_active":false}';Hook=$true;Code=0;Warning=$true;Kind='empty';Calls=1},
        @{Name='code-failure';Input='{"stop_hook_active":false}';Hook=$true;Code=10;Warning=$true;Kind='block';Calls=1},
        @{Name='environment';Input='{"stop_hook_active":false}';Hook=$true;Code=20;Warning=$false;Kind='message';Calls=1},
        @{Name='internal-error';Input='{"stop_hook_active":false}';Hook=$true;Code=30;Warning=$false;Kind='message';Calls=1},
        @{Name='unknown-exit';Input='{"stop_hook_active":false}';Hook=$true;Code=7;Warning=$false;Kind='message';Calls=1},
        @{Name='malformed';Input='{bad';Hook=$true;Code=0;Warning=$false;Kind='message';Calls=0},
        @{Name='missing-flag';Input='{}';Hook=$true;Code=0;Warning=$false;Kind='message';Calls=0},
        @{Name='string-flag';Input='{"stop_hook_active":"false"}';Hook=$true;Code=0;Warning=$false;Kind='message';Calls=0},
        @{Name='empty-input';Input=' ';Hook=$true;Code=0;Warning=$false;Kind='message';Calls=0},
        @{Name='array-input';Input='[{"stop_hook_active":false},{"stop_hook_active":true}]';Hook=$true;Code=0;Warning=$false;Kind='message';Calls=0},
        @{Name='missing-verifier';Input='{"stop_hook_active":false}';Hook=$true;Code=0;Warning=$false;Kind='message';Calls=0}
    )
    foreach ($launchMode in @('cmd','powershell')) {
        foreach ($case in $hookCases) {
            $dir = Join-Path $testRoot ($launchMode + "-" + $case.Name)
            foreach ($part in @('.codex/hooks','scripts','src/nested','evidence')) { [IO.Directory]::CreateDirectory((Join-Path $dir $part)) | Out-Null }
            # init는 이 검증이 만든 빈 임시 저장소에서만 수행. 사용자 저장소 index에는 접근하지 않음
            $gitOutput = @(& git init --quiet $dir 2>&1)
            Assert-That ($LASTEXITCODE -eq 0) "fixture git init 실패"
            Copy-Item -LiteralPath (Join-Path $repoRoot '.codex/hooks/stop_gate.ps1') -Destination (Join-Path $dir '.codex/hooks/stop_gate.ps1')
            $fake = @'
param([string]$RunDirectory)
$ErrorActionPreference = "Stop"
Add-Content -LiteralPath (Join-Path $PSScriptRoot '../trace.txt') -Value 'gate' -Encoding UTF8
Write-Output 'fixture stdout must not enter hook JSON'
if (__WARNING__) { [Console]::Error.WriteLine('fixture warning') }
[IO.File]::WriteAllText((Join-Path $RunDirectory 'fixture.log'),'fixture')
exit __CODE__
'@
            $warningLiteral = if ($case.Warning) { '$true' } else { '$false' }
            $fake = $fake.Replace('__WARNING__',$warningLiteral).Replace('__CODE__',[string]$case.Code)
            if ($case.Name -ne 'missing-verifier') {
                [IO.File]::WriteAllText((Join-Path $dir 'scripts/verify-fast.ps1'),$fake,(New-Object Text.UTF8Encoding($true)))
            }
            # 두 셸에서 등록 명령 원문을 실행. PowerShell 외부 전달은 UTF-16LE로 보존.
            if ($launchMode -eq 'cmd') {
                $launcher = Join-Path $dir 'invoke-configured-hook.cmd'
                [IO.File]::WriteAllText($launcher,("@echo off`r`n" + $handler.commandWindows + "`r`nexit /b %errorlevel%`r`n"),[Text.Encoding]::ASCII)
                $launcherArguments = @()
            }
            else {
                $launcher = $powerShellPath
                $outerCommand = $handler.commandWindows + "`r`nexit `$LASTEXITCODE"
                $outerEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($outerCommand))
                $launcherArguments = @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$outerEncoded)
            }
            $inputPath = Join-Path $dir 'input.json'
            [IO.File]::WriteAllText($inputPath,$case.Input,(New-Object Text.UTF8Encoding($false)))
            $stdout = Join-Path $dir 'evidence/hook.json'
            $code = Invoke-SupervisedProcess -FilePath $launcher -Arguments $launcherArguments -Directory (Join-Path $dir 'src/nested') -OutputPath $stdout -ErrorPath (Join-Path $dir 'evidence/stderr.log') -InputPath $inputPath -Hook $case.Hook -TimeoutMilliseconds 30000 -Phase 'hook-fixture'
            Assert-That ($code -eq 0) "$($case.Name): hook exit=$code"
            $raw = Get-Content -LiteralPath $stdout -Raw -Encoding UTF8
            Assert-That (@($raw -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -eq 1) "$($case.Name): stdout은 JSON 한 줄이어야 함"
            $result = $raw | ConvertFrom-Json -ErrorAction Stop
            Assert-That ($null -ne $result -and $result -is [pscustomobject]) "$($case.Name): hook JSON 객체 없음"
            switch ($case.Kind) {
                'empty' { Assert-That (@($result.PSObject.Properties).Count -eq 0) "$($case.Name): 예상 밖 block/message" }
                'block' { Assert-That ($result.decision -ceq 'block' -and -not [string]::IsNullOrWhiteSpace($result.reason)) '코드 실패 continuation 누락' }
                'message' { Assert-That ($null -ne $result.PSObject.Properties['systemMessage'] -and $null -eq $result.PSObject.Properties['decision']) "$($case.Name): 외부 supervisor 인계 오류" }
            }
            Assert-That ($raw -notmatch 'fixture stdout|fixture warning') '검증 원문이 hook JSON에 섞임'
            $tracePath = Join-Path $dir 'trace.txt'
            $calls = if (Test-Path -LiteralPath $tracePath) { @(Get-Content -LiteralPath $tracePath).Count } else { 0 }
            Assert-That ($calls -eq $case.Calls) "$($case.Name): Fast 호출 수 오류"
            $receipts = @(Get-ChildItem -LiteralPath $dir -Recurse -File -Filter 'hook-result.json')
            Assert-That ($receipts.Count -eq $case.Calls) "$($case.Name): 실제 호출 기록 수 오류"
            if ($case.Calls -eq 1) {
                $receipt = Get-Content -LiteralPath $receipts[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
                $expected = if ($case.Code -eq 0) { 'FAST_PASS' } elseif ($case.Code -eq 10) { 'RETRY' } else { 'SUPERVISOR_REQUIRED' }
                Assert-That ($receipt.native_exit_code -eq $case.Code -and $receipt.outcome -ceq $expected) "$($case.Name): 기록 판정 오류"
            }
            # 실제 handler 진입·조기 종료 관측과 gate 결과 기록을 별도로 검증
            $diagnostics = @(Get-ChildItem -LiteralPath (Join-Path $dir 'build/agent-runs/stop-hook-trace') -File -Filter '*.json')
            Assert-That ($diagnostics.Count -eq 1) "$($case.Name): 진입 진단 누락/중복"
            $diagnosticText = Get-Content -LiteralPath $diagnostics[0].FullName -Raw -Encoding UTF8
            $diagnostic = $diagnosticText | ConvertFrom-Json
            $expectedStage = if ($case.Name -eq 'disabled') { 'disabled' } elseif ($case.Name -eq 'recursive') { 'recursive' } elseif ($case.Calls -eq 1) { 'verify-finish' } else { 'error' }
            $expectedFlag = if ($case.Hook) { 'enabled' } else { 'missing' }
            Assert-That ($diagnostic.stage -ceq $expectedStage -and $diagnostic.harness_flag_state -ceq $expectedFlag) "$($case.Name): 진단 분기 불일치"
            if ($case.Calls -eq 1) { Assert-That ($diagnostic.verification_exit -eq $case.Code) "$($case.Name): 진단 exit 불일치" }
            if ($expectedStage -eq 'error') { Assert-That (-not [string]::IsNullOrWhiteSpace($diagnostic.failure_stage) -and -not [string]::IsNullOrWhiteSpace($diagnostic.error_type)) "$($case.Name): 실패 위치 누락" }
            if ($case.Name -eq 'success') {
                Assert-That ($diagnostic.session_id -ceq '00000000-0000-0000-0000-000000000001' -and $diagnostic.turn_id -ceq '00000000-0000-0000-0000-000000000002') '세션/turn 진단 연결 누락'
            }
            Assert-That ($diagnosticText -notmatch 'fixture stdout|fixture warning|rawInput|last_assistant_message') '진단에 원문 혼입'
            Pass "hook/$launchMode/$($case.Name)"
        }
    }
    $rulesFile = Join-Path $repoRoot '.codex/rules/safety.rules'
    $rulesCases = @(
        @{Args=@('git','reset','--hard');Deny=$true},
        @{Args=@('git','clean','-fdx');Deny=$true},
        @{Args=@('git','restore','--staged','.');Deny=$true},
        @{Args=@('git','checkout','--','.');Deny=$true},
        @{Args=@('git','commit','--amend');Deny=$true},
        @{Args=@('git','add','.');Deny=$true},
        @{Args=@('git','merge','fixture');Deny=$true},
        @{Args=@('git','push','origin','main');Deny=$true},
        @{Args=@('git','stash');Deny=$true},
        @{Args=@('git','switch','fixture');Deny=$true},
        @{Args=@('git','worktree','add','../fixture');Deny=$true},
        @{Args=@('git','worktree','remove','../fixture');Deny=$true},
        @{Args=@('git','worktree','prune');Deny=$true},
        @{Args=@('git','branch','-D','fixture');Deny=$true},
        @{Args=@('rm','-rf','fixture');Deny=$true},
        @{Args=@('git','worktree','list');Deny=$false},
        @{Args=@('git','worktree','list','--porcelain');Deny=$false},
        @{Args=@('git','status','--short');Deny=$false},
        @{Args=@('git','diff','--check');Deny=$false},
        @{Args=@('git','branch','--show-current');Deny=$false}
    )
    $index=0
    foreach ($case in $rulesCases) {
        $index++
        $out = Join-Path $testRoot "rules-$index.json"
        $code = Invoke-SupervisedProcess -FilePath $codexPath -Arguments (@('execpolicy','check','--pretty','--rules',$rulesFile,'--') + $case.Args) -Directory $repoRoot -OutputPath $out -ErrorPath (Join-Path $testRoot "rules-$index.stderr.log") -TimeoutMilliseconds 30000 -Phase 'rules-check'
        Assert-That ($code -eq 0) "execpolicy 검사 명령 실패: $($case.Args -join ' ')"
        $result = Get-Content -LiteralPath $out -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-That ($null -ne $result -and $result -is [pscustomobject]) 'execpolicy JSON 객체 없음'
        $decision = if ($null -ne $result.PSObject.Properties['decision']) { [string]$result.decision } else { '' }
        if ($case.Deny) { Assert-That ($decision -ceq 'forbidden') "차단 규칙 불일치: $($case.Args -join ' ')" }
        else { Assert-That ($decision -cne 'forbidden') "조회까지 차단됨: $($case.Args -join ' ')" }
        Pass "rules/$($case.Args -join ' ')"
    }
    Assert-That ($passed -eq 49) '훅/규칙 검증 수 불일치'
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'test-harness-writer.ps1') -RulesTests
    Assert-That ($LASTEXITCODE -eq 0) '잘못된 rules 판정 뒤 Writer 실행 차단 3개 실패'
    & $powerShellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'test-harness-writer.ps1')
    Assert-That ($LASTEXITCODE -eq 0) '변경된 preflight가 기존 Writer 흐름 44개를 깨뜨림'
    @{status='PASS';checks=96;hook_config_sha256=$configHash;codex_event_dispatch_verified=$false;model_api_db_called=$false} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'test-result.json') -Encoding UTF8
    Write-Host '[test-harness-hooks] PASS: 96 checks (config 1 + hook 28 + rules 20 + rules-flow 3 + writer 44; 실제 모델/API/DB 실행 없음)'
    Write-Host "[test-harness-hooks] 증거: $testRoot"
    Write-Host '[test-harness-hooks] Codex 이벤트 자동 호출·프로젝트 신뢰 확인은 별도 필요'
    exit 0
}
catch {
    Write-Host "[test-harness-hooks] FAIL: $($_.Exception.Message)"
    Write-Host "[test-harness-hooks] 증거: $testRoot"
    exit 1
}
