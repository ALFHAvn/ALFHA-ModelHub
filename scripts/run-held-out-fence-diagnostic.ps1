#Requires -Version 7.2
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$manifestPath = Join-Path $root 'benchmarks/held-out/local-campaign-v1.json'
$campaign = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$runDirectory = Join-Path $root "target/$($campaign.id)"

function Get-Sha256([string]$Text) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
}

function Get-NormalizedText([string]$Path) {
    (Get-Content -LiteralPath $Path -Raw).Replace("`r`n", "`n")
}

function Get-CodeBlock([string]$Response, [string]$Language) {
    $fences = [regex]::Matches($Response, '(?m)^[ \t]*(?:\x60{3,}|~{3,})[^\r\n]*')
    if ($fences.Count -eq 0) { return @{ Decision = 'NO_FENCE' } }
    if ($fences.Count -ne 2) { return @{ Decision = 'AMBIGUOUS_OR_INCOMPLETE_FENCE' } }
    $open, $close = $fences
    if ($open.Value -cnotmatch "^[ \t]*\x60{3}$Language[ \t]*$" -or $close.Value -cnotmatch '^[ \t]*\x60{3}[ \t]*$') {
        return @{ Decision = 'MISMATCHED_OR_MALFORMED_FENCE' }
    }
    $start = $open.Index + $open.Length
    if ($Response[$start] -eq "`r") { $start++ }
    if ($Response[$start] -ne "`n") { return @{ Decision = 'MISMATCHED_OR_MALFORMED_FENCE' } }
    $start++
    $code = $Response.Substring($start, $close.Index - $start)
    if ([string]::IsNullOrWhiteSpace($code) -or $code.Contains([char]0) -or
        [Text.Encoding]::UTF8.GetByteCount($code) -gt 16384 -or
        ($Language -ceq 'sql' -and [string]::IsNullOrWhiteSpace($code.Trim().TrimEnd(';')))) {
        return @{ Decision = 'INVALID_SOURCE' }
    }
    @{ Decision = 'EXTRACTED'; Code = $code }
}

$summary = & "$PSScriptRoot/summarize-held-out-campaign.ps1" -CampaignPath $manifestPath -RunDirectory $runDirectory | ConvertFrom-Json
if ($summary.recordedSlots -ne 24 -or $summary.evidenceReports -ne 24) { throw 'Expected the complete measured campaign.' }
foreach ($entry in $campaign.scriptSha256.PSObject.Properties) {
    $path = Join-Path $root $entry.Name
    if ((Get-Sha256 (Get-NormalizedText $path)) -cne $entry.Value) { throw "Pinned script changed: $path" }
}
$images = @{
    'eclipse-temurin:21-jdk-alpine' = $campaign.runtime.javaGraderImage
    'postgres:17-alpine' = $campaign.runtime.postgresImage
}
foreach ($image in $images.GetEnumerator()) {
    $actual = docker image inspect --format '{{.Id}}' $image.Key
    if ($LASTEXITCODE -ne 0 -or $actual -cne $image.Value) { throw "Pinned grader image changed: $($image.Key)" }
}

$inputs = @(foreach ($window in ($campaign.windows | Where-Object split -CEQ 'evaluation')) {
    $journal = Join-Path $runDirectory "$($window.id)/slots.jsonl"
    foreach ($line in (Get-Content -LiteralPath $journal)) {
        $slot = $line | ConvertFrom-Json
        $evidence = Get-Content -LiteralPath $slot.evidencePath -Raw | ConvertFrom-Json
        $artifactDir = Split-Path $slot.evidencePath -Parent
        $executionPath = Join-Path $artifactDir 'execution.json'
        $execution = Get-Content -LiteralPath $executionPath -Raw | ConvertFrom-Json
        $generation = Get-Content -LiteralPath (Join-Path $artifactDir 'generation.json') -Raw | ConvertFrom-Json
        $taskPath = Join-Path $root "benchmarks/held-out/$($slot.task)-v1.json"
        $startedAt = [datetimeoffset](Get-Content -LiteralPath (Join-Path $artifactDir 'started-at.txt') -Raw).Trim()
        $rebuilt = & "$PSScriptRoot/export-held-out-evidence.ps1" -TaskPath $taskPath -ExecutionPath $executionPath `
            -StartedAt $startedAt -GradePath (Join-Path $artifactDir 'grade.json') | ConvertFrom-Json
        if ($generation.requestId -cne $execution.requestId -or $generation.response -cne $execution.response -or
            ($rebuilt | ConvertTo-Json -Depth 12 -Compress) -cne ($evidence | ConvertTo-Json -Depth 12 -Compress)) {
            throw "Original execution or evidence mismatch: $($slot.evidencePath)"
        }
        [pscustomobject]@{ Slot = $slot; Execution = $execution; Evidence = $evidence }
    }
})

$directory = (New-Item -ItemType Directory -Path (Join-Path $runDirectory "fence-diagnostic-$([guid]::NewGuid().ToString('N'))")).FullName
$journal = Join-Path $directory 'results.jsonl'
foreach ($inputRecord in $inputs) {
    $slot = $inputRecord.Slot
    $language = if ($slot.task -ceq 'sql-window') { 'sql' } else { 'java' }
    $block = Get-CodeBlock $inputRecord.Execution.response $language
    $id = $inputRecord.Execution.requestId
    $result = [ordered]@{
        requestId = $id; model = $slot.model; task = $slot.task; repetition = $slot.repetition
        rawScore = $slot.score; rawSha256 = Get-Sha256 $inputRecord.Execution.response
        extraction = $block.Decision; extractedSha256 = $null; extractedScore = $null; grade = $null
    }
    if ($block.Decision -ceq 'EXTRACTED') {
        $source = Join-Path $directory "$id.$language"
        [IO.File]::WriteAllText($source, $block.Code, [Text.UTF8Encoding]::new($false))
        $result.extractedSha256 = Get-Sha256 $block.Code
        $grade = switch ($slot.task) {
            'sql-window' { & "$PSScriptRoot/run-sql-window-pilot.ps1" -SourcePath $source }
            'regression-test' { & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $source -RegressionTest }
            'config-propagation' { & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $source -ConfigPropagation }
            'concurrent-capacity' { & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $source -ConcurrentCapacity }
        }
        $result.grade = $grade | ConvertFrom-Json
        $rawGrade = $inputRecord.Evidence.results[0].grade
        if ($result.grade.imageId -cne $rawGrade.imageId -or $result.grade.taskSha256 -cne $rawGrade.taskSha256 -or
            $result.grade.sourceSha256 -ine $result.extractedSha256 -or
            $result.grade.results.Count -ne $rawGrade.results.Count -or
            ($result.grade.results.id -join ',') -cne ($rawGrade.results.id -join ',')) {
            throw "Diagnostic grader inputs changed: $id"
        }
        $result.extractedScore = if (@($result.grade.results | Where-Object outcome -CNE 'PASS').Count) { 'FAIL' } else { 'PASS' }
    }
    $result | ConvertTo-Json -Depth 10 -Compress | Add-Content -LiteralPath $journal
    Write-Output "$($slot.task) / $($slot.model) / repetition $($slot.repetition): $($result.extraction) $($result.extractedScore)"
}
Write-Output "Diagnostic journal: $journal"
