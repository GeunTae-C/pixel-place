# C-1 입력 보존과 C-2 예산 확인. 원본 DB/WAL 및 Git 변경 책임 없음
$ErrorActionPreference = 'Stop'
$phaseRoot = 'E:/pixel-place-phase15/phase15-a-20260918'
$evidence = Join-Path $phaseRoot 'agent-runs/phase15-C-2-20260922-entry'
$prior = Join-Path $phaseRoot 'agent-runs/phase15-C-1-20260922-regression'
$inputs = Get-Content -Raw -Encoding UTF8 -LiteralPath "$prior/execution-input.json" | ConvertFrom-Json
$owned = [long](Get-ChildItem -LiteralPath $phaseRoot -Recurse -File -Force | Measure-Object Length -Sum).Sum
$actual = $owned + 2891607341L
$carried = [Math]::Max($actual,27739610021L)
$estimate = 5GB + 2GB + 512MB + 1GB
$free = ([IO.DriveInfo]::new('E:')).AvailableFreeSpace
if ($carried + $estimate -gt 36GB -or $free - $estimate -lt 5GB) { throw 'C-2 budget insufficient' }
if (Test-Path -LiteralPath $evidence) { throw 'Existing entry must be preserved' }
foreach ($entry in $inputs) {
    if ((Get-FileHash -LiteralPath $entry.Path).Hash -ne $entry.Sha256) { throw 'C-1 executable input changed' }
}
New-Item -ItemType Directory -Path "$evidence/source" | Out-Null
$paths = @($inputs.Path) + @('docs/pixel-place-details.md','docs/pixel-place-specs.md','작업기록/phase-15-progress.md')
$rows = foreach ($path in $paths) {
    $relative = [IO.Path]::GetFullPath($path).Substring((Get-Location).Path.Length+1)
    $target = Join-Path "$evidence/source" $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $path -Destination $target
    [pscustomobject]@{Path=$relative;Sha256=(Get-FileHash -LiteralPath $path).Hash}
}
$rows | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath "$evidence/baseline-input.json"
git -c core.quotePath=false -c core.safecrlf=false diff -- | Set-Content -Encoding UTF8 -LiteralPath "$evidence/baseline.diff"
git -c core.quotePath=false status --short --untracked-files=all | Set-Content -Encoding UTF8 -LiteralPath "$evidence/baseline-status.txt"
[pscustomobject]@{SavedAt=(Get-Date -Format o);ActualCharge=$actual;CarriedCharge=$carried;MaximumBytes=36GB;EstimatedAdditionalBytes=$estimate;EstimatedTotalBytes=$carried+$estimate;FreeBytes=$free;EstimatedRemainingFree=$free-$estimate;Approval='C-1 36GiB approval carried forward by C-2 user instruction';Estimate='5GiB full regression including MySQL FULL binlog/fixture/WAL; 2GiB affected rerun reserve; 512MiB limited tests/build/cache/temp/source/evidence; 1GiB contingency';C1Inputs=$inputs.Count;Changed=0} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath "$evidence/budget-entry.json"
Write-Output "C2_ENTRY_SAVED $evidence"
