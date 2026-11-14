[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-GateMessage {
    param([string]$Message)

    Write-Output "[repo-hygiene] $Message"
}

try {
    $repoRoot = (& git rev-parse --show-toplevel 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
        Write-GateMessage "Git worktree를 확인할 수 없음"
        exit 30
    }

    Set-Location $repoRoot

    $unmerged = @(& git diff --name-only --diff-filter=U)
    if ($LASTEXITCODE -ne 0) {
        Write-GateMessage "unmerged file 검사 실패"
        exit 30
    }
    if ($unmerged.Count -gt 0) {
        Write-GateMessage "병합 충돌은 자동 수정 대상이 아님: $($unmerged -join ', ')"
        exit 20
    }

    $suspiciousRootFiles = @(
        Get-ChildItem -LiteralPath $repoRoot -File |
            Where-Object {
                $_.Name -match '^git (commit|status|diff|log|add|push|pull|checkout|switch|reset|clean)\b' -or
                $_.Extension -in @('.orig', '.rej')
            } |
            Select-Object -ExpandProperty Name
    )

    if ($suspiciousRootFiles.Count -gt 0) {
        Write-GateMessage "사람 확인이 필요한 의심스러운 루트 파일: $($suspiciousRootFiles -join ', ')"
        Write-GateMessage "기존 사용자 파일일 수 있으므로 하네스가 자동 삭제하지 않음"
        exit 20
    }

    Write-GateMessage "PASS"
    exit 0
}
catch {
    Write-GateMessage "내부 오류: $($_.Exception.Message)"
    exit 30
}
