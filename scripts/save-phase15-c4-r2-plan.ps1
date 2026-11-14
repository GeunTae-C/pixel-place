# 발생기 보완 뒤 남은 실행의 보수적 신규량. 이미 승인된 74GiB 안에서만 진행
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'phase15-c-budget.ps1')
$budget=Save-Phase15CCharge
$parts=[ordered]@{
    fixed15=21752478720L
    finalRegression=5368709120L
    twoSmokes=671088640L
    snapshotsAnalysisGraph=536870912L
    sharedDbLogsTemp=268435456L
    limitedRetryMargin=1073741824L
}
$estimate=[long]($parts.Values | Measure-Object -Sum).Sum
if (-not (Test-Phase15CBudget $budget.chargedBytes $estimate $budget.freeBytes)) { throw 'Remaining complete scope exceeds approved budget' }
$path='E:/pixel-place-phase15/phase15-a-20260918/agent-runs/phase15-C-4-20260923-pacing-r2-validation/remaining-plan.json'
if (Test-Path -LiteralPath $path) { throw 'Existing plan evidence cannot be replaced' }
$report=[ordered]@{savedUtc=[DateTime]::UtcNow.ToString('o');status='APPROVED_CAP_UNCHANGED';budget=$budget;
    remainingParts=$parts;remainingTotal=$estimate;projectedCharge=$budget.chargedBytes+$estimate;
    projectedFree=$budget.freeBytes-$estimate;reserveExplanation='Includes 1GiB limited retry margin; heavy retry requires fresh full-scope forecast. Completed 32-test validation already charged.';
    fixed15Formula='13 high + 2 low; existing conservative per-trial DB/binlog/WAL/request/resource/source/recovery estimates total 21752478720';
    regressionFormula='5GiB one full normal+Redis+MySQL+bootJar runner';smokeFormula='2*320MiB';
    otherFormula='512MiB snapshots/analysis/Graphify + 256MiB shared DB logs/temp + 1GiB limited retry';
    priorInvalidPerformanceSession='c4r120260923';newCandidate='phase15-C-4-20260923-candidate-r2';newSession='c4r220260923';
    reason='G-01: old 15 raw trials preserved, generator delivered about 68/s due arrival/preparation late; separate corrected-pacer candidate and complete fixed matrix required'}
[IO.File]::WriteAllText($path,($report | ConvertTo-Json -Depth 8)+"`n",[Text.UTF8Encoding]::new($false))
$report | ConvertTo-Json -Depth 8
