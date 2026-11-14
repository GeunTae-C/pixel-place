# 승인 상한과 순차 실행의 누적 charge 경계. 임시 파일 감소를 다음 실행 예산으로 반환하지 않음
$script:Phase15BudgetRoot = 'E:/pixel-place-phase15/phase15-a-20260918'
$script:Phase15Ledger = Join-Path $script:Phase15BudgetRoot 'agent-runs/phase15-C-4-budget-ledger'

function Assert-Phase15CBudget($Budget) {
    if ($Budget.maximumBytes -ne 74GB -or $Budget.carriedChargeBytes -ne 36960813040L -or
        $Budget.legacyChargeBytes -ne 2824498477L -or $Budget.ancillaryChargeBytes -ne 67108864L -or
        $Budget.minimumFreeBytes -ne 5GB) { throw 'C approved budget inputs disagree' }
}

function Get-Phase15CCharge {
    $approval = Get-Content -Raw -Encoding UTF8 -LiteralPath 'scripts/phase15-c-budget.json' | ConvertFrom-Json
    Assert-Phase15CBudget $approval
    $owned = [long](Get-ChildItem -LiteralPath $script:Phase15BudgetRoot -Recurse -File -Force | Measure-Object Length -Sum).Sum
    $files = @(if (Test-Path -LiteralPath $script:Phase15Ledger) {
        Get-ChildItem -LiteralPath $script:Phase15Ledger -File -Filter '*.json' | Sort-Object Name
    })
    # 최초 기준은 승인 직전 저장한 실제 root와 이미 보수적으로 승계한 charge
    $previousOwned = 34054160482L
    $previousCharge = [long]$approval.carriedChargeBytes
    if ($files.Count -gt 0) {
        $last = Get-Content -Raw -LiteralPath $files[-1].FullName | ConvertFrom-Json
        if ($last.maximumBytes -ne $approval.maximumBytes -or $last.chargedBytes -lt $previousCharge -or $last.ownedBytes -lt 0) { throw 'C ledger identity or charge invalid' }
        $previousOwned = [long]$last.ownedBytes
        $previousCharge = [long]$last.chargedBytes
    }
    $charge = [decimal]$previousCharge + [Math]::Max([decimal]0, [decimal]$owned - $previousOwned)
    if ($charge -gt [long]::MaxValue) { throw 'C cumulative charge overflow' }
    [pscustomobject]@{ownedBytes=$owned;chargedBytes=[long]$charge;maximumBytes=[long]$approval.maximumBytes;
        freeBytes=([IO.DriveInfo]::new('E:')).AvailableFreeSpace;sequence=$files.Count+1;savedUtc=[DateTime]::UtcNow.ToString('o')}
}

function Save-Phase15CCharge {
    $snapshot = Get-Phase15CCharge
    if (-not (Test-Path -LiteralPath $script:Phase15Ledger)) { New-Item -ItemType Directory -Path $script:Phase15Ledger | Out-Null }
    $path = Join-Path $script:Phase15Ledger ('{0:D8}.json' -f $snapshot.sequence)
    # 같은 ledger의 동시 실행·기존 증거 덮어쓰기는 허용하지 않음
    $stream = [IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($snapshot | ConvertTo-Json) + "`n")
        $stream.Write($bytes,0,$bytes.Length)
    } finally { $stream.Dispose() }
    $snapshot
}

function Test-Phase15CBudget([decimal]$Charge,[decimal]$Estimate,[decimal]$Free) {
    return $Charge -ge 36960813040L -and $Estimate -ge 0 -and $Free -ge 5GB -and
        $Charge -le 74GB -and $Estimate -le (74GB - $Charge) -and $Estimate -le ($Free - 5GB)
}
