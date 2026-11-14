# Restricted YAML: top-level credential names with single-quoted, single-line values.
# No evaluation, YAML aliases, expansion or environment persistence.
function Import-HarnessLocalSecrets {
    param([Parameter(Mandatory=$true)][string]$Root)
    $directory = Join-Path $Root 'local-secrets'
    $path = Join-Path $directory 'secrets.yml'
    if (-not (Test-Path -LiteralPath $directory)) { return }
    $folder = Get-Item -LiteralPath $directory -Force
    if (-not $folder.PSIsContainer -or ($folder.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'local-secrets must be a regular directory.'
    }
    if (-not (Test-Path -LiteralPath $path)) { return }
    $file = Get-Item -LiteralPath $path -Force
    if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $file.Length -gt 65536) {
        throw 'secrets.yml must be a regular UTF-8 file, at most 64 KiB.'
    }
    try { $text = (New-Object Text.UTF8Encoding($false, $true)).GetString([IO.File]::ReadAllBytes($path)) }
    catch { throw 'Cannot read secrets.yml as UTF-8.' }
    $text = $text.TrimStart([char]0xFEFF)
    $values = @{}
    $lineNumber = 0
    foreach ($line in ($text -split '\r?\n')) {
        $lineNumber++
        if ($line -match '^\s*(#.*)?$') { continue }
        $m = [regex]::Match($line, '^(?<key>[A-Z][A-Z0-9_]*)[ \t]*:[ \t]*''(?<value>(?:[^''\r\n]|'''')*)''[ \t]*(?:#.*)?$')
        if (-not $m.Success) { throw "Invalid secrets.yml syntax at line $lineNumber; use KEY: 'value'." }
        $key = $m.Groups['key'].Value
        if ($key -cnotmatch '^(DB_PASSWORD|[A-Z][A-Z0-9_]*_(API_KEY|CLIENT_ID|CLIENT_SECRET|ACCESS_TOKEN|REFRESH_TOKEN|SECRET|PASSWORD|SIGNING_KEY))$') {
            throw "Unsupported credential name at line $lineNumber."
        }
        if ($values.ContainsKey($key)) { throw "Duplicate credential at line $lineNumber." }
        $value = $m.Groups['value'].Value.Replace("''", "'")
        if ([string]::IsNullOrWhiteSpace($value) -or $value -match '[\x00-\x1F\x7F]') {
            throw "Empty or invalid credential at line $lineNumber."
        }
        $values[$key] = $value
    }
    # Parse everything before setting anything. Roll back on an assignment error.
    $previous = @{}
    try {
        foreach ($key in $values.Keys) {
            $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
        }
    }
    catch {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
        throw 'Could not import local credentials into this process.'
    }
}
