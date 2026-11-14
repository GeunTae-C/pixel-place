# A 실행 입력 보존과 C-1 최초 예산 확인. 기존 증거·DB·WAL 변경 책임 없음
$ErrorActionPreference = 'Stop'
$phaseRoot = 'E:/pixel-place-phase15/phase15-a-20260918'
$evidence = Join-Path $phaseRoot 'agent-runs/phase15-C-1-20260922-entry'
$audit = Get-Content -Raw -Encoding UTF8 -LiteralPath "$phaseRoot/agent-runs/phase15-A-final-audit/final-audit.json" | ConvertFrom-Json
$owned = [long](Get-ChildItem -LiteralPath $phaseRoot -Recurse -File -Force | Measure-Object Length -Sum).Sum
$charge = $owned + 2824498477L + 67108864L
$free = ([IO.DriveInfo]::new('E:')).AvailableFreeSpace
$estimate = 16MB
if ($charge + $estimate -gt 25GB -or $free - $estimate -lt 5GB) { throw 'Entry snapshot budget exceeded' }
if (Test-Path -LiteralPath $evidence) { throw 'Entry evidence already exists; inspect before resuming' }
New-Item -ItemType Directory -Path "$evidence/source" | Out-Null
$rows = foreach ($entry in $audit.inputHashes.PSObject.Properties) {
    $relative = $entry.Name
    # A 감사에서 식별한 비민감 입력만 복사. 비밀 주입 파일·runtime 데이터 제외
    if ($relative -match '(?i)(^|/)(\.env|secrets)(/|$)|\.zip$') { throw 'Unexpected sensitive snapshot input' }
    $target = Join-Path "$evidence/source" $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $relative -Destination $target
    [pscustomobject]@{Path=$relative;Sha256=(Get-FileHash -LiteralPath $relative -Algorithm SHA256).Hash;MatchesA=((Get-FileHash -LiteralPath $relative -Algorithm SHA256).Hash -eq $entry.Value)}
}
$rows | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath "$evidence/baseline-input.json"
git -c core.quotePath=false -c core.safecrlf=false diff -- | Set-Content -Encoding UTF8 -LiteralPath "$evidence/baseline.diff"
git -c core.quotePath=false status --short | Set-Content -Encoding UTF8 -LiteralPath "$evidence/baseline-status.txt"
[pscustomobject]@{Utc=[DateTime]::UtcNow.ToString('o');OwnedBytes=$owned;LegacyBytes=2824498477L;AncillaryBytes=67108864L;ChargedBytes=$charge;MaximumBytes=25GB;FreeBytes=$free;SnapshotEstimateBytes=$estimate;ProposedC1TotalBytes=36GB;ProposalApproved=$false;RemainingEstimateBytes=(2*5GB+512MB+1GB);Estimate='Two regression reservations (5GiB each, includes MySQL/FULL binlog/fixture/WAL); 512MiB baseline/unit/build/cache/temp/evidence; 1GiB contingency. C-1 only.'} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$evidence/budget-entry.json"
Write-Output "C1_ENTRY_SNAPSHOT_SAVED $evidence"
