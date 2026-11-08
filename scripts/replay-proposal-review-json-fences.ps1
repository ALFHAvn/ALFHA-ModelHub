#Requires -Version 7.5
param([Parameter(Mandatory)][string]$PilotDirectory)

$ErrorActionPreference = 'Stop'
$scorer = Join-Path $PSScriptRoot 'score-proposal-review.ps1'
$pilot = (Resolve-Path -LiteralPath $PilotDirectory).ProviderPath
$summary = Get-Content -LiteralPath (Join-Path $pilot 'summary.json') -Raw | ConvertFrom-Json -DateKind String
$version = $summary.tasks[0].score.datasetVersion
if ($version -cnotin '1', '2') { throw 'Unknown dataset version.' }
$fixturePath = Join-Path $PSScriptRoot "../benchmarks/proposal-review/defect-identification-v$version.json"
$fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
if (($summary.tasks.taskId -join ',') -cne ($fixture.tasks.id -join ',')) { throw "Expected a complete v$version pilot summary in fixture order." }
$roles = @('proposal', 'review')
if ($summary.selfReviewRequested -eq $true) { $roles += 'selfReview' }

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
    $options = @{}
    if ('selfReview' -cin $roles) { $options.SelfReviewPath = Join-Path $directory 'self-review.json' }
    $raw = & $scorer -TaskId $task.taskId -DatasetVersion $version -WorkflowPath $workflowPath @options | ConvertFrom-Json -DateKind String
    if (($raw | ConvertTo-Json -Compress -Depth 8) -cne ($task.score | ConvertTo-Json -Compress -Depth 8)) {
        throw "Raw verdict or workflow hash differs for $($task.taskId)."
    }
    $workflow = Get-Content -LiteralPath $workflowPath -Raw | ConvertFrom-Json -DateKind String
    $history = Get-Content -LiteralPath (Join-Path $directory 'history.json') -Raw | ConvertFrom-Json -DateKind String
    $request = Get-Content -LiteralPath (Join-Path $directory 'request.json') -Raw | ConvertFrom-Json -DateKind String
    $prompt = & $scorer -TaskId $task.taskId -DatasetVersion $version
    if ($request.allowCloud -ne $false -or $request.prompt -cne $prompt -or $history.proposal.prompt -cne $prompt -or
        $request.proposerModel -cne $summary.proposerModel -or $request.reviewerModel -cne $summary.reviewerModel -or
        $workflow.proposal.model -cne $summary.proposerModel -or $workflow.review.model -cne $summary.reviewerModel) {
        throw "Saved prompt or model provenance differs for $($task.taskId)."
    }
    $steps = @{ proposal = $workflow.proposal; review = $workflow.review }
    $savedSteps = @{ proposal = $history.proposal; review = $history.review }
    if ($options.SelfReviewPath) {
        $steps.selfReview = Get-Content -LiteralPath $options.SelfReviewPath -Raw | ConvertFrom-Json -DateKind String
        $savedSteps.selfReview = Get-Content -LiteralPath (Join-Path $directory 'self-review-history.json') -Raw | ConvertFrom-Json -DateKind String
        $controlRequest = Get-Content -LiteralPath (Join-Path $directory 'self-review-request.json') -Raw | ConvertFrom-Json -DateKind String
        if ($controlRequest.model -cne $summary.proposerModel -or $controlRequest.prompt -cne $history.review.prompt -or
            $savedSteps.selfReview.prompt -cne $history.review.prompt) { throw "Control prompt or model differs for $($task.taskId)." }
    }
    foreach ($role in $roles) {
        $step = $steps[$role]
        $saved = $savedSteps[$role]
        if ($step.status -cne 'SUCCESS' -or $step.error -or $saved.error -or -not $ids.Add([guid]$step.requestId)) {
            throw "Expected a unique successful execution for $($task.taskId) / $role."
        }
        foreach ($field in 'requestId', 'model', 'response', 'status', 'durationMs') {
            if ($step.$field -cne $saved.$field) { throw "Saved history differs for $($task.taskId) / $role / $field." }
        }
    }
    [pscustomobject]@{ TaskId = $task.taskId; Steps = $steps; Workflow = $workflow; Raw = $raw }
})

$target = Join-Path $PSScriptRoot '../target'
$output = (New-Item -ItemType Directory -Path (Join-Path $target "proposal-review-fence-diagnostic-$([guid]::NewGuid().ToString('N'))")).FullName
$results = @(foreach ($inputRecord in $inputs) {
    $extraction = @{}
    foreach ($role in $roles) {
        $step = $inputRecord.Steps[$role]
        $body = Get-JsonFence $step.response
        if ($null -ne $body) {
            $step.response = $body
            $extraction[$role] = 'EXTRACTED'
        } elseif ($version -ceq '2' -and $inputRecord.Raw.$role.score.outcome -cin 'PASS', 'WRONG_ANSWER') {
            $extraction[$role] = 'RAW_JSON'
        } else { $extraction[$role] = 'REJECTED' }
    }
    $derivedPath = Join-Path $output "$($inputRecord.TaskId).json"
    $inputRecord.Workflow | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $derivedPath
    $options = @{}
    if ('selfReview' -cin $roles) {
        $options.SelfReviewPath = Join-Path $output "$($inputRecord.TaskId)-self-review.json"
        $inputRecord.Steps.selfReview | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $options.SelfReviewPath
    }
    $derived = & $scorer -TaskId $inputRecord.TaskId -DatasetVersion $version -WorkflowPath $derivedPath @options | ConvertFrom-Json -DateKind String
    $result = [ordered]@{
        taskId = $inputRecord.TaskId
        raw = $inputRecord.Raw
        derivedWorkflowSha256 = $derived.workflowSha256
        proposalExtraction = $extraction.proposal
        reviewExtraction = $extraction.review
        proposalDiagnostic = if ($extraction.proposal -cne 'REJECTED') { $derived.proposal.score }
        reviewDiagnostic = if ($extraction.review -cne 'REJECTED') { $derived.review.score }
        comparison = if ('REJECTED' -cin $extraction.proposal, $extraction.review) { 'UNAVAILABLE' } else { $derived.comparison }
    }
    if ($options.SelfReviewPath) {
        $result.derivedSelfReviewSha256 = $derived.selfReviewSha256
        $result.selfReviewExtraction = $extraction.selfReview
        $result.selfReviewDiagnostic = if ($extraction.selfReview -cne 'REJECTED') { $derived.selfReview.score }
        $result.reviewVsSelfReview = if ('REJECTED' -cin $extraction.review, $extraction.selfReview) { 'UNAVAILABLE' } else { $derived.reviewVsSelfReview }
    }
    $result
})
$report = Join-Path $output 'diagnostic.json'
[ordered]@{ datasetId = $fixture.id; datasetVersion = $fixture.version; tasks = $results } |
    ConvertTo-Json -Depth 9 | Set-Content -LiteralPath $report
Get-Item -LiteralPath $report
