# 회귀 실행 전후의 실제 경로 집합과 bytes를 각각 수집. 기존 목록 재사용으로 신규 입력 누락 금지
function Get-Phase15InputPaths([string]$Root, [string]$Label) {
    $paths = @((Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -File).FullName)
    $relative = @('build.gradle','settings.gradle','pixel_place.sql','gradlew','gradlew.bat',
        'gradle/wrapper/gradle-wrapper.properties','gradle/wrapper/gradle-wrapper.jar',
        'scripts/test-phase15.ps1','scripts/phase15-inputs.ps1','scripts/Phase15RegressionDatabase.java',
        'scripts/phase15-c-budget.json')
    if ($Label -match '^phase15-C-[3-5]-') {
        $relative += @('scripts/measure-phase15.ps1','scripts/summarize-phase15.py','scripts/test-phase15-analysis.py',
            'scripts/phase15-c-input.json','scripts/phase15-c-matrix.json','scripts/start-phase15-c-mysql.ps1',
            'scripts/stop-phase15-c-mysql.ps1','scripts/Phase15OwnedDatabaseStop.java',
            'scripts/Phase15C4DatabaseAudit.java','scripts/harness-local-secrets.ps1',
            'scripts/phase15-c4-input.json','scripts/phase15-c-budget.ps1','scripts/test-phase15-c-budget.ps1',
            'scripts/report-phase15-c4.py','scripts/render-phase15-c4.py','scripts/decide-phase15-c4.py')
    }
    if ($Label -match '^phase15-C-5-') {
        # 새 C5 분석·검증·마감 도구 추가도 다음 수집에서 검출
        $paths += @((Get-ChildItem -LiteralPath (Join-Path $Root 'scripts') -File |
            Where-Object { $_.Name -match 'phase15-c5' -and $_.Extension -in @('.ps1','.py','.json','.java') }).FullName)
    }
    $paths += @($relative | ForEach-Object { Join-Path $Root $_ })
    @($paths | Where-Object { $_ } | ForEach-Object { [IO.Path]::GetFullPath($_) } | Sort-Object -Unique)
}

function Get-Phase15ExecutionInputs([string]$Root, [string]$Label) {
    @(Get-Phase15InputPaths $Root $Label | ForEach-Object {
        [pscustomobject]@{Path=$_;Sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256 -ErrorAction Stop).Hash}
    })
}

function Compare-Phase15ExecutionInputs($Before, $After) {
    $old=@{}; $new=@{}
    foreach ($row in $Before) { if ($old.ContainsKey($row.Path)) { throw 'Duplicate before input' }; $old[$row.Path]=$row.Sha256 }
    foreach ($row in $After) { if ($new.ContainsKey($row.Path)) { throw 'Duplicate after input' }; $new[$row.Path]=$row.Sha256 }
    $added=@($new.Keys | Where-Object { -not $old.ContainsKey($_) } | Sort-Object)
    $removed=@($old.Keys | Where-Object { -not $new.ContainsKey($_) } | Sort-Object)
    $changed=@($old.Keys | Where-Object { $new.ContainsKey($_) -and $old[$_] -cne $new[$_] } | Sort-Object)
    [pscustomobject]@{Count=$Before.Count;AfterCount=$After.Count;Added=$added;Removed=$removed;Changed=$changed;
        Exact=($added.Count -eq 0 -and $removed.Count -eq 0 -and $changed.Count -eq 0)}
}
