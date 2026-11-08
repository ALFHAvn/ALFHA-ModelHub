#Requires -Version 7.5
param(
    [Parameter(Mandatory, ParameterSetName = 'Text')][AllowEmptyString()][string]$Response,
    [Parameter(Mandatory, ParameterSetName = 'Replay')][string]$ComparisonDirectory
)

$ErrorActionPreference = 'Stop'

function Test-ProseWrappedJavaFence([string]$Text) {
    if ([Text.Encoding]::UTF8.GetByteCount($Text) -gt 16384 -or
        $Text.TrimStart() -notmatch '\A(?:(?:Certainly|Sure|Of course)[!,:]\s|Here(?:''s| is)\s)') {
        return $false
    }
    $lines = $Text -split '\r\n|\n|\r'
    $fences = @(for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -cmatch '^[ \t]*(?:\x60{3,}|~{3,})') { $i }
    })
    if ($fences.Count -ne 2) { return $false }
    $start, $end = $fences
    $opening = [regex]::Match($lines[$start], '(?i)^[ \t]*(?<marker>\x60{3}|~{3})[ \t]*java[ \t]*$')
    if (-not $opening.Success -or $lines[$end].Trim() -cne $opening.Groups['marker'].Value -or
        $end -le ($start + 1) -or $end -eq ($lines.Count - 1)) { return $false }

    $inside = $lines[($start + 1)..($end - 1)] -join "`n"
    $after = $lines[($end + 1)..($lines.Count - 1)] -join "`n"
    return -not [string]::IsNullOrWhiteSpace($inside) -and -not [string]::IsNullOrWhiteSpace($after)
}

if ($PSCmdlet.ParameterSetName -ceq 'Text') { return Test-ProseWrappedJavaFence $Response }

$directory = (Resolve-Path -LiteralPath $ComparisonDirectory).ProviderPath
$plan = Get-Content -LiteralPath (Join-Path $directory 'plan.json') -Raw | ConvertFrom-Json -DateKind String
$summary = Get-Content -LiteralPath (Join-Path $directory 'summary.json') -Raw | ConvertFrom-Json -DateKind String
if ($plan.protocol -cne 'raw-java-prompt-comparison-v1' -or $summary.protocol -cne $plan.protocol -or
    $plan.slots.Count -ne 4 -or $summary.resultCount -ne 4 -or $summary.results.Count -ne 4) {
    throw 'Expected the complete raw Java prompt comparison.'
}
$ids = [Collections.Generic.HashSet[guid]]::new()
$results = @(for ($i = 0; $i -lt $summary.results.Count; $i++) {
    $label = 'slot-' + ($i + 1)
    $slot = $plan.slots[$i]
    $record = $summary.results[$i]
    $report = Get-Content -LiteralPath (Join-Path $directory "$label-report.json") -Raw | ConvertFrom-Json -DateKind String
    $history = Get-Content -LiteralPath (Join-Path $directory "$label-history.json") -Raw | ConvertFrom-Json -DateKind String
    $row = $report.results[0]
    $execution = $row.execution
    if ($report.results.Count -ne 1 -or $report.id -cne $record.reportId -or $report.dataset.id -cne 'java-coding' -or
        $report.dataset.version -cne $slot.version -or $record.version -cne $slot.version -or
        $report.datasetSha256 -cne $plan.datasetHashes.($slot.version) -or $report.scoringVersion -cne 'java-coding-raw-v1' -or
        $execution.model -cne $slot.model -or $record.model -cne $slot.model -or
        $execution.status -cne 'SUCCESS' -or $record.generationStatus -cne 'SUCCESS' -or
        $execution.requestId -cne $record.executionId -or -not $ids.Add([guid]$execution.requestId) -or
        $row.score -cne $record.score -or $row.codingEvaluation.outcome -cne $record.verdict -or
        $report.dataset.tasks.Count -ne 1 -or $report.dataset.tasks[0].id -cne $row.taskId -or
        $report.dataset.tasks[0].prompt -cne $history.prompt) { throw "Saved benchmark metadata differs for $label." }
    foreach ($field in 'requestId', 'model', 'status', 'response', 'durationMs') {
        if ($execution.$field -cne $history.$field) { throw "Saved execution differs for $label / $field." }
    }
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($execution.response))).ToLowerInvariant()
    if ($hash -cne $row.codingEvaluation.sourceSha256) { throw "Saved response hash differs for $label." }
    [ordered]@{
        model = $slot.model; datasetVersion = $slot.version; requestId = $execution.requestId; rawSha256 = $hash
        originalOutcome = $record.verdict; originalScore = $record.score
        proseWrappedJavaFence = Test-ProseWrappedJavaFence $execution.response
    }
})
[ordered]@{ diagnostic = 'raw-java-prose-envelope-v1'; results = $results } | ConvertTo-Json -Depth 5
