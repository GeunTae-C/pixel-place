# Optional compatibility update for the existing legacy fixture, not production.
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot 'test-harness-evidence.ps1'
if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    Write-Host '[fixture-update] Legacy test absent; nothing to update.'
    exit 0
}
$bytes = [IO.File]::ReadAllBytes($path)
$text = (New-Object Text.UTF8Encoding($false, $true)).GetString($bytes)
$old = '"url: jdbc:mysql://localhost/fixture`n"'
$new = '"url: jdbc:mysql://localhost/fixture?password=fixture-only-never-real`n"'
if ($text.Contains($new)) { Write-Host '[fixture-update] Already updated.'; exit 0 }
if (($text.Split(@($old), [StringSplitOptions]::None).Count - 1) -ne 1) {
    throw 'Unknown legacy fixture version; no file changed. Provide the current test-harness-evidence.ps1.'
}
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'pixel-place-work.ps1')
$backup = Get-PixelPlaceEvidencePath ('fixture-backup-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($backup) | Out-Null
[IO.File]::WriteAllBytes((Join-Path $backup 'test-harness-evidence.ps1'), $bytes)
# Decoding retains a BOM as U+FEFF; encoding without adding another BOM preserves it.
[IO.File]::WriteAllText($path, $text.Replace($old, $new), (New-Object Text.UTF8Encoding($false)))
Write-Host '[fixture-update] Updated one secret-bearing fixture; other content preserved.'
Write-Host "[fixture-update] Backup: $backup"
