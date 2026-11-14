# Git 미추적 정책을 유지하며 시작 기준선·고정 명령문·검토 근거 연결
function Get-EvidenceHash {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace("-", "") }
    finally { $sha.Dispose() }
}

function Write-EvidenceJson {
    param([string]$Path, $Value)
    $json = ConvertTo-Json -InputObject $Value -Depth 12
    [IO.File]::WriteAllText($Path, $json, (New-Object Text.UTF8Encoding($false)))
}

# stderr를 JSON/경로 목록에 섞지 않으며 Git의 읽기 명령만 사용
function Invoke-EvidenceGit {
    param([string]$Root, [string[]]$Arguments, [int[]]$AllowedExit = @(0))
    $allArguments = @("--no-optional-locks", "-C", $Root) + $Arguments
    $quoted = foreach ($argument in $allArguments) {
        '"' + [regex]::Replace([regex]::Replace($argument, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
    }
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = "git"
    $info.Arguments = $quoted -join " "
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = New-Object Text.UTF8Encoding($false)
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw "증거 수집 Git 시작 실패" }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -notin $AllowedExit) { throw "증거 수집 Git 실패 (exit=$($process.ExitCode))" }
        return $output
    }
    finally { $process.Dispose() }
}

# LF/CRLF 경고(stderr)를 공백 오류(stdout)와 분리. 실제 Git 실행 오류는 예외 유지
function Get-EvidenceWhitespaceLines {
    param([string]$Root)
    foreach ($mode in @("worktree", "staged")) {
        $arguments = if ($mode -eq "staged") { @("diff", "--cached", "--check") } else { @("diff", "--check") }
        $output = Invoke-EvidenceGit -Root $Root -Arguments $arguments -AllowedExit @(0, 2)
        foreach ($line in ($output -split "`r?`n")) {
            if ($line.Length -gt 0) { "$mode|$line" }
        }
    }
}

function Get-EvidenceGitState {
    param([string]$Root)
    $head = (Invoke-EvidenceGit $Root @("rev-parse", "HEAD")).Trim()
    $branch = (Invoke-EvidenceGit $Root @("symbolic-ref", "--quiet", "--short", "HEAD") @(0, 1)).Trim()
    $index = Invoke-EvidenceGit $Root @("ls-files", "--stage", "-z")
    $tracked = @{}
    foreach ($entry in $index.Split([char]0)) {
        if ($entry.Length -eq 0) { continue }
        if ($entry -notmatch '^([0-9]{6}) ([0-9a-f]+) ([0-3])\t([\s\S]+)$') { throw "Git index 형식 오류" }
        if ($Matches[3] -ne "0" -or $Matches[1] -eq "160000") { throw "충돌 또는 submodule은 현재 증거 수집에서 지원하지 않음" }
        $tracked[$Matches[4]] = "$($Matches[1]) $($Matches[2])"
    }
    $untracked = @{}
    $other = Invoke-EvidenceGit $Root @("ls-files", "--others", "--exclude-standard", "-z")
    foreach ($path in $other.Split([char]0)) { if ($path.Length -gt 0) { $untracked[$path] = $true } }
    return [pscustomobject]@{ head = $head; branch = $branch; index_sha256 = (Get-EvidenceHash ([Text.Encoding]::UTF8.GetBytes($index))); tracked = $tracked; untracked = $untracked }
}

function Get-EvidenceExclusion {
    param([string]$Relative)
    # 루트 산출물만 제외하여 src/.../build 같은 실제 source 경로 보존
    return ($Relative -match '^(\.git|build|\.gradle|\.idea|\.vscode|out|bin|node_modules|graphify-out|data|셀프백업)(/|$)' -or
        $Relative -match '(?i)\.(zip|wal)$')
}

function Get-EvidenceFiles {
    param([string]$Root)
    $queue = New-Object 'System.Collections.Generic.Queue[string]'
    $queue.Enqueue($Root)
    while ($queue.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $queue.Dequeue() -Force -ErrorAction Stop) {
            $relative = $item.FullName.Substring($Root.TrimEnd('\', '/').Length + 1).Replace('\', '/')
            if (Get-EvidenceExclusion $relative) { continue }
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "증거 대상 link/reparse point 지원 안 됨: $relative" }
            if ($item.PSIsContainer) { $queue.Enqueue($item.FullName) }
            else { $item }
        }
    }
}

# Only exact references are accepted; defaults/concatenation remain protected.
function Test-EvidenceExternalReference {
    param([string]$Value)
    return ($Value -cmatch '^\$\{[A-Za-z_][A-Za-z0-9_]*\}$' -or
        $Value -imatch '^\$env:[A-Za-z_][A-Za-z0-9_]*$')
}

function Test-EvidencePublicJdbc {
    param([string]$Value)
    # Deliberately bounded MySQL syntax. No userinfo, encoded authority, credentials,
    # fragments, multi-host/address syntax or arbitrary query parameters.
    $m = [regex]::Match($Value, '^jdbc:mysql://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/[A-Za-z0-9_-]+(?:\?(?<query>[^\s]+))?$', 'IgnoreCase')
    if (-not $m.Success) { return $false }
    if (-not $m.Groups['query'].Success) { return $true }
    $seen = @{}
    foreach ($pair in $m.Groups['query'].Value.Split('&')) {
        $kv = $pair.Split('=')
        if ($kv.Count -ne 2 -or $seen.ContainsKey($kv[0])) { return $false }
        $seen[$kv[0]] = $true
        switch -Regex ($kv[0]) {
            '^(serverTimezone|connectionTimeZone)$' { if ($kv[1] -notmatch '^(Asia/Seoul|UTC|GMT|LOCAL|SERVER)$') { return $false }; break }
            '^characterEncoding$' { if ($kv[1] -notmatch '^(UTF-8|UTF8|utf8mb4)$') { return $false }; break }
            '^(useUnicode|useSSL|requireSSL|verifyServerCertificate|allowPublicKeyRetrieval)$' { if ($kv[1] -notmatch '^(true|false)$') { return $false }; break }
            '^sslMode$' { if ($kv[1] -notmatch '^(DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY)$') { return $false }; break }
            default { return $false }
        }
    }
    return $true
}

function Test-EvidenceSensitiveContent {
    param([string]$Text)
    # Heuristic, not a complete secret scanner. Never resolve environment values.
    if ($Text -match '(?im)-----BEGIN .*PRIVATE KEY|\bBearer\s+[A-Za-z0-9]') { return $true }
    foreach ($jdbc in [regex]::Matches($Text, '(?i)jdbc:[^\s`"'']*')) {
        if ($jdbc.Value -ieq 'jdbc:') { continue }
        if (-not (Test-EvidencePublicJdbc $jdbc.Value)) { return $true }
    }
    # Match key first; parse value separately to prevent optional quote backtracking.
    $keys = '(?im)(?:[\w]+[-_])*(?:password|passwd|secret|client[-_]?secret|access[-_]?token|refresh[-_]?token|api[-_]?key|private[-_]?key)["'']?\s*[:=][ \t]*'
    foreach ($entry in [regex]::Matches($Text, $keys)) {
        $tail = $Text.Substring($entry.Index + $entry.Length)
        if ($tail.Length -eq 0 -or $tail[0] -eq "`r" -or $tail[0] -eq "`n" -or $tail[0] -eq '#') { continue }
        $value = ''
        if ($tail[0] -eq '"' -or $tail[0] -eq "'") {
            $quote = $tail[0]
            $end = $tail.IndexOf($quote, 1)
            if ($end -lt 0) { return $true }
            $value = $tail.Substring(1, $end - 1)
            $remainder = $tail.Substring($end + 1)
            # Only structural separators/comments may follow a quoted reference.
            if ($remainder -notmatch '^[ \t]*(?:$|[\r\n,#}\]])') { return $true }
        }
        else {
            $value = [regex]::Match($tail, '^[^\s,;]+').Value
            $remainder = $tail.Substring($value.Length)
            if ($remainder -notmatch '^[ \t]*(?:$|[\r\n,#;])') { return $true }
        }
        if ($value.Length -eq 0) { continue }
        if (-not (Test-EvidenceExternalReference $value)) { return $true }
    }
    return $false
}

function Get-EvidenceTextReason {
    param([string]$Relative, [byte[]]$Bytes)
    $Relative = $Relative.Replace('\', '/')
    if ($Bytes.Length -gt 2097152) { return "over-2MiB" }
    if ($Relative -match '(?i)(^|/)(\.env($|\.)|credentials?($|\.)|secrets?($|\.))|\.(pem|key|p12|pfx|jks|keystore)$') { return "sensitive-name" }
    if ($Bytes -contains [byte]0) { return "binary" }
    try { $text = (New-Object Text.UTF8Encoding($false, $true)).GetString($Bytes) }
    catch { return "non-utf8" }
    if ($text -match '[\x00-\x08\x0B\x0C\x0E-\x1F]') { return "binary" }
    # 사용자 정책: 루트 명령문 폴더와 지정 상세 문서는 일반 문서로 취급한다.
    # 파일명 보호·크기·UTF-8·바이너리 검사를 통과한 뒤 내용 검사만 생략한다.
    $documentPath = $Relative
    $publicDocument = ($documentPath.StartsWith('명령문/', [StringComparison]::Ordinal) -or
        $documentPath -ceq 'docs/pixel-place-details.md') -and
        $documentPath -notmatch '(^|/)\.\.?(/|$)'
    # Java 구현과 테스트는 의미를 검토할 원문이다. 실제 비밀은 별도 설정으로 관리한다.
    # 폴더 전체가 아니라 지정 source root 아래 .java만 적용한다.
    $javaSource = $Relative -cmatch '^src/(main|test)/java/.+\.java$' -and
        $Relative -notmatch '(^|/)\.\.?(/|$)'
    if (-not ($publicDocument -or $javaSource) -and (Test-EvidenceSensitiveContent $text)) { return "sensitive-content" }
    return "text"
}

function New-EvidenceSnapshot {
    param([string]$Root, [string]$Directory)
    [IO.Directory]::CreateDirectory($Directory) | Out-Null
    $state = Get-EvidenceGitState $Root
    $entries = @{}
    foreach ($file in Get-EvidenceFiles $Root) {
        $relative = $file.FullName.Substring($Root.TrimEnd('\', '/').Length + 1).Replace('\', '/')
        $kind = if ($state.tracked.ContainsKey($relative)) { "tracked" } elseif ($state.untracked.ContainsKey($relative)) { "untracked" } else { "ignored" }
        $reason = "over-2MiB"
        $copy = ""
        if ($file.Length -le 2097152) {
            $bytes = [IO.File]::ReadAllBytes($file.FullName)
            $hash = Get-EvidenceHash $bytes
            $length = $bytes.Length
            $reason = Get-EvidenceTextReason $relative $bytes
            if ($reason -eq "text") {
                # 원본 경로는 manifest에만 사용하고 복사본은 해시 이름으로 경로 주입 방지
                $copy = Join-Path $Directory ($hash + ".txt")
                [IO.File]::WriteAllBytes($copy, $bytes)
            }
        }
        else {
            $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
            $length = $file.Length
        }
        $entries[$relative] = [pscustomobject]@{ path = $relative; kind = $kind; exists = $true; sha256 = $hash; length = $length; content = $copy; coverage = $reason }
    }
    foreach ($relative in $state.tracked.Keys) {
        if (-not (Get-EvidenceExclusion $relative) -and -not $entries.ContainsKey($relative)) {
            $entries[$relative] = [pscustomobject]@{ path = $relative; kind = "tracked"; exists = $false; sha256 = ""; length = 0; content = ""; coverage = "absent" }
        }
    }
    $fingerprint = @($entries.Keys | Sort-Object | ForEach-Object { $e = $entries[$_]; @($e.path,$e.kind,$e.exists,$e.sha256) | ConvertTo-Json -Compress }) -join "`n"
    return [pscustomobject]@{ git = $state; files = $entries; fingerprint = (Get-EvidenceHash ([Text.Encoding]::UTF8.GetBytes($fingerprint))) }
}

function New-EvidenceSeal {
    param([string]$Directory)
    $seal = @{}
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Recurse -File -Force) {
        $seal[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    return $seal
}

function Assert-EvidenceSeal {
    param($Seal)
    foreach ($path in $Seal.Keys) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $Seal[$path]) {
            throw "EVIDENCE_CHANGED: 보존한 증거 누락/변경"
        }
    }
}

function New-RunEvidence {
    param([string]$Root, [string]$RunDirectory, [string]$TaskPath)
    $directory = Join-Path $RunDirectory "baseline"
    $snapshot = New-EvidenceSnapshot $Root (Join-Path $directory "files")
    $relative = $TaskPath.Substring($Root.TrimEnd('\', '/').Length + 1).Replace('\', '/')
    if (-not $snapshot.files.ContainsKey($relative) -or $snapshot.files[$relative].coverage -ne "text") {
        $reason = if ($snapshot.files.ContainsKey($relative)) { $snapshot.files[$relative].coverage } else { "excluded-or-missing" }
        throw "EVIDENCE_CHANGED: 명령문은 증거 대상 UTF-8 text여야 함 (coverage=$reason; 제외 경로/비밀/대용량/바이너리 불가)"
    }
    $task = Join-Path $directory "task-frozen.md"
    [IO.File]::Copy($snapshot.files[$relative].content, $task, $false)
    $manifest = Join-Path $directory "baseline.json"
    $body = [ordered]@{ head = $snapshot.git.head; branch = $snapshot.git.branch; index_sha256 = $snapshot.git.index_sha256; index = $snapshot.git.tracked; task_source = $relative; task_sha256 = $snapshot.files[$relative].sha256; exclusions = "root .git/build/.gradle/.idea/.vscode/out/bin/node_modules/graphify-out/data/셀프백업; *.zip; *.wal"; files = @($snapshot.files.Keys | Sort-Object | ForEach-Object { $snapshot.files[$_] }) }
    Write-EvidenceJson $manifest $body
    return [pscustomobject]@{ root = $Root; snapshot = $snapshot; task_source = $TaskPath; task = $task; task_sha256 = $snapshot.files[$relative].sha256; manifest = $manifest; baseline_sha256 = (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash; seal = (New-EvidenceSeal $directory) }
}

function Assert-RunEvidence {
    param($Evidence)
    Assert-EvidenceSeal $Evidence.seal
    if (-not (Test-Path -LiteralPath $Evidence.task_source -PathType Leaf) -or
        (Get-FileHash -LiteralPath $Evidence.task_source -Algorithm SHA256).Hash -cne $Evidence.task_sha256) {
        throw "EVIDENCE_CHANGED: 실행 중 원본 명령문 변경/삭제"
    }
    $state = Get-EvidenceGitState $Evidence.root
    if ($state.head -cne $Evidence.snapshot.git.head -or $state.branch -cne $Evidence.snapshot.git.branch -or
        $state.index_sha256 -cne $Evidence.snapshot.git.index_sha256) {
        throw "EVIDENCE_CHANGED: 실행 중 HEAD/branch/index 변경"
    }
}

function New-ChangeEvidence {
    param($Evidence, [string]$AttemptDirectory, [string]$VerificationLog = "")
    Assert-RunEvidence $Evidence
    $directory = Join-Path $AttemptDirectory "review-evidence"
    $current = New-EvidenceSnapshot $Evidence.root (Join-Path $directory "files")
    $changes = New-Object 'System.Collections.Generic.List[object]'
    $unreviewable = New-Object 'System.Collections.Generic.List[string]'
    $keys = @(@($Evidence.snapshot.files.Keys) + @($current.files.Keys) | Sort-Object -Unique)
    $empty = Join-Path $directory "empty.txt"
    [IO.File]::WriteAllBytes($empty, [byte[]]@())
    foreach ($key in $keys) {
        $before = $Evidence.snapshot.files[$key]
        $after = $current.files[$key]
        $had = $null -ne $before -and $before.exists
        $has = $null -ne $after -and $after.exists
        if (-not $had -and -not $has) { continue }
        if ($had -and $has -and $before.sha256 -ceq $after.sha256 -and $before.kind -eq $after.kind) { continue }
        $status = if (-not $had) { "A" } elseif (-not $has) { "D" } else { "M" }
        $diff = ""
        if (($had -and $before.coverage -ne "text") -or ($has -and $after.coverage -ne "text")) { $unreviewable.Add($key) }
        else {
            $oldPath = if ($had) { $before.content } else { $empty }
            $newPath = if ($has) { $after.content } else { $empty }
            $diff = Join-Path $directory ("change-{0:D4}.diff" -f $changes.Count)
            $patch = Invoke-EvidenceGit $Evidence.root @("diff", "--no-index", "--no-ext-diff", "--no-textconv", "--", $oldPath, $newPath) @(0, 1)
            [IO.File]::WriteAllText($diff, $patch, (New-Object Text.UTF8Encoding($false)))
        }
        $changes.Add([pscustomobject]@{ path = $key; status = $status; before = $before; after = $after; diff = $diff })
    }
    $verification = $null
    if (-not [string]::IsNullOrWhiteSpace($VerificationLog)) {
        $verification = [ordered]@{ path = $VerificationLog; sha256 = (Get-FileHash -LiteralPath $VerificationLog -Algorithm SHA256 -ErrorAction Stop).Hash }
    }
    $manifest = Join-Path $directory "changes.json"
    Write-EvidenceJson $manifest ([ordered]@{ task_sha256 = $Evidence.task_sha256; baseline_sha256 = $Evidence.baseline_sha256; current_fingerprint = $current.fingerprint; verification = $verification; changes = @($changes.ToArray()); unreviewable = @($unreviewable.ToArray()); note = "시작 시 working tree 대비 누적 변화. 행위자 자동 식별 아님. 기존 index는 보존 확인." })
    Assert-RunEvidence $Evidence
    $seal = New-EvidenceSeal $directory
    if ($null -ne $verification) { $seal[$VerificationLog] = $verification.sha256 }
    return [pscustomobject]@{ manifest = $manifest; change_manifest_sha256 = (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash; task_sha256 = $Evidence.task_sha256; baseline_sha256 = $Evidence.baseline_sha256; fingerprint = $current.fingerprint; seal = $seal; unreviewable = @($unreviewable.ToArray()) }
}

function Assert-ReviewReceipt {
    param($Review, $Evidence)
    $property = $Review.PSObject.Properties["evidence"]
    if ($null -eq $property -or $null -eq $property.Value) { throw "Reviewer evidence 응답 누락" }
    foreach ($key in @("task_sha256", "baseline_sha256", "change_manifest_sha256")) {
        $value = $property.Value.PSObject.Properties[$key]
        if ($null -eq $value -or $value.Value -isnot [string] -or $value.Value -cne $Evidence.$key) {
            throw "Reviewer evidence hash 불일치: $key"
        }
    }
}

function Assert-ReviewState {
    param($RunEvidence, $ReviewEvidence, [string]$AttemptDirectory)
    Assert-RunEvidence $RunEvidence
    Assert-EvidenceSeal $ReviewEvidence.seal
    $check = New-EvidenceSnapshot $RunEvidence.root (Join-Path $AttemptDirectory "post-review-state")
    if ($check.fingerprint -cne $ReviewEvidence.fingerprint) { throw "EVIDENCE_CHANGED: 검토 중 작업 파일 변경, 기존 검토 결과 사용 불가" }
    Assert-RunEvidence $RunEvidence
}
