#Requires -Version 7.5
param([Parameter(Mandatory)][string]$PilotDirectory)

$ErrorActionPreference = 'Stop'
$scorer = Join-Path $PSScriptRoot 'score-proposal-review.ps1'
$fixturePath = Join-Path $PSScriptRoot '../benchmarks/proposal-review/defect-identification-v1.json'
$fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
$pilot = (Resolve-Path -LiteralPath $PilotDirectory).ProviderPath
$summary = Get-Content -LiteralPath (Join-Path $pilot 'summary.json') -Raw | ConvertFrom-Json -DateKind String
if (($summary.tasks.taskId -join ',') -cne ($fixture.tasks.id -join ',')) { throw 'Expected a complete v1 pilot summary in fixture order.' }

function Get-JsonFence([string]$Text) {
    $match = [regex]::Match($Text, '(?s)\A[ \t\r\n]*\x60{3}json[ \t]*\r?\n(?<body>.*?)\r?\n\x60{3}[ \t\r\n]*\z')
    if ($match.Success -and $match.Groups['body'].Value -cnotmatch '(?m)^[ \t]*(?:\x60{3,}|~{3,})') {
        return $match.Groups['body'].Value
    }
    return $null
}

$ids = [Collections.Generic.HashSet[guid]]::new()
$inputs = @(foreach ($task in $summary.tasks) {
    $directory = Join-Path $pilot $task.taskId
    $workflowPath = Join-Path $directory 'workflow.json'
    $raw = & $scorer -TaskId $task.taskId -WorkflowPath $workflowPath | ConvertFrom-Json -DateKind String
    if (($raw | ConvertTo-Json -Compress -Depth 8) -cne ($task.score | ConvertTo-Json -Compress -Depth 8)) {
        throw "Raw verdict or workflow hash differs for $($task.taskId)."
    }
    $workflow = Get-Content -LiteralPath $workflowPath -Raw | ConvertFrom-Json -DateKind String
    $history = Get-Content -LiteralPath (Join-Path $directory 'history.json') -Raw | ConvertFrom-Json -DateKind String
    $request = Get-Content -LiteralPath (Join-Path $directory 'request.json') -Raw | ConvertFrom-Json -DateKind String
    $prompt = & $scorer -TaskId $task.taskId
    if ($request.allowCloud -ne $false -or $request.prompt -cne $prompt -or $history.proposal.prompt -cne $prompt -or
        $request.proposerModel -cne $summary.proposerModel -or $request.reviewerModel -cne $summary.reviewerModel -or
        $workflow.proposal.model -cne $summary.proposerModel -or $workflow.review.model -cne $summary.reviewerModel) {
        throw "Saved prompt or model provenance differs for $($task.taskId)."
    }
    foreach ($role in 'proposal', 'review') {
        $step = $workflow.$role
        $saved = $history.$role
        if ($step.status -cne 'SUCCESS' -or $step.error -or $saved.error -or -not $ids.Add([guid]$step.requestId)) {
            throw "Expected a unique successful execution for $($task.taskId) / $role."
        }
        foreach ($field in 'requestId', 'model', 'response', 'status', 'durationMs') {
            if ($step.$field -cne $saved.$field) { throw "Saved history differs for $($task.taskId) / $role / $field." }
        }
    }
    [pscustomobject]@{ TaskId = $task.taskId; Workflow = $workflow; Raw = $raw }
})

$target = Join-Path $PSScriptRoot '../target'
$output = (New-Item -ItemType Directory -Path (Join-Path $target "proposal-review-fence-diagnostic-$([guid]::NewGuid().ToString('N'))")).FullName
$results = @(foreach ($inputRecord in $inputs) {
    $extraction = @{}
    foreach ($role in 'proposal', 'review') {
        $body = Get-JsonFence $inputRecord.Workflow.$role.response
        $extraction[$role] = if ($null -eq $body) { 'REJECTED' } else { 'EXTRACTED' }
        if ($null -ne $body) { $inputRecord.Workflow.$role.response = $body }
    }
    $derivedPath = Join-Path $output "$($inputRecord.TaskId).json"
    $inputRecord.Workflow | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $derivedPath
    $derived = & $scorer -TaskId $inputRecord.TaskId -WorkflowPath $derivedPath | ConvertFrom-Json -DateKind String
    [ordered]@{
        taskId = $inputRecord.TaskId
        raw = $inputRecord.Raw
        derivedWorkflowSha256 = $derived.workflowSha256
        proposalExtraction = $extraction.proposal
        reviewExtraction = $extraction.review
        proposalDiagnostic = if ($extraction.proposal -ceq 'EXTRACTED') { $derived.proposal.score }
        reviewDiagnostic = if ($extraction.review -ceq 'EXTRACTED') { $derived.review.score }
        comparison = if ('REJECTED' -cin $extraction.Values) { 'UNAVAILABLE' } else { $derived.comparison }
    }
})
$report = Join-Path $output 'diagnostic.json'
[ordered]@{ datasetId = $fixture.id; datasetVersion = $fixture.version; tasks = $results } |
    ConvertTo-Json -Depth 9 | Set-Content -LiteralPath $report
Get-Item -LiteralPath $report
