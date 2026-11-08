#Requires -Version 7.5
param(
    [Parameter(Mandatory)][string]$TaskId,
    [string]$WorkflowPath,
    [ValidateSet('1', '2')][string]$DatasetVersion = '1',
    [string]$SelfReviewPath,
    [switch]$StructuredComparisonV1
)

$ErrorActionPreference = 'Stop'
$fixturePath = Join-Path $PSScriptRoot "../benchmarks/proposal-review/defect-identification-v$DatasetVersion.json"
$fixtureText = [IO.File]::ReadAllText($fixturePath).Replace("`r`n", "`n")
$fixture = ConvertFrom-Json -InputObject $fixtureText -AsHashtable
$tasks = @($fixture.tasks | Where-Object id -CEQ $TaskId)
if ($tasks.Count -ne 1) { throw "Unknown or duplicate task ID: $TaskId" }
$task = $tasks[0]
$allowed = [string[]]@($task.findings.Keys)
[Array]::Sort($allowed, [StringComparer]::Ordinal)
$expected = @($task.expectedDefectIds)

function Get-Score($Step) {
    if ($StructuredComparisonV1 -and $Step.status -ceq 'FAILED' -and $Step.error.code -ceq 'OUTPUT_FORMAT_ERROR') {
        if ($Step.response -isnot [string]) { throw 'Missing raw text for an output format failure.' }
        return [ordered]@{ outcome = 'FORMAT_ERROR'; truePositives = $null; falsePositives = $null; missedDefects = $null }
    }
    if ($Step.status -cne 'SUCCESS') {
        return [ordered]@{ outcome = 'UNAVAILABLE'; truePositives = $null; falsePositives = $null; missedDefects = $null }
    }

    $formatError = [ordered]@{ outcome = 'FORMAT_ERROR'; truePositives = $null; falsePositives = $null; missedDefects = $null }
    $document = $null
    try {
        $document = [System.Text.Json.JsonDocument]::Parse([string]$Step.response)
        if ($document.RootElement.ValueKind -ne 'Object') { return $formatError }
        $fields = @($document.RootElement.EnumerateObject())
        if ($fields.Count -ne 1 -or $fields[0].Name -cne 'defectIds' -or $fields[0].Value.ValueKind -ne 'Array') {
            return $formatError
        }
        $selected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($item in $fields[0].Value.EnumerateArray()) {
            if ($item.ValueKind -ne 'String') { return $formatError }
            $id = $item.GetString()
            if ((-not $StructuredComparisonV1 -and $id -cnotin $allowed) -or -not $selected.Add($id)) { return $formatError }
        }
    } catch [System.Text.Json.JsonException], [InvalidOperationException] {
        return $formatError
    } finally {
        if ($null -ne $document) { $document.Dispose() }
    }

    $truePositives = @($expected | Where-Object { $selected.Contains($_) })
    $falsePositives = @(if ($StructuredComparisonV1) {
        [string[]]$falseIds = @($selected | Where-Object { $_ -cnotin $expected })
        [Array]::Sort($falseIds, [StringComparer]::Ordinal)
        $falseIds
    } else { $allowed | Where-Object { $_ -cnotin $expected -and $selected.Contains($_) } })
    $missedDefects = @($expected | Where-Object { -not $selected.Contains($_) })
    [ordered]@{
        outcome = if ($falsePositives.Count -or $missedDefects.Count) { 'WRONG_ANSWER' } else { 'PASS' }
        truePositives = $truePositives
        falsePositives = $falsePositives
        missedDefects = $missedDefects
    }
}

function Read-Step($Step, [bool]$CanSkip) {
    if ($Step.model -isnot [string] -or [string]::IsNullOrWhiteSpace($Step.model) -or
        ($Step.durationMs -isnot [long] -and $Step.durationMs -isnot [int]) -or $Step.durationMs -lt 0) {
        throw 'Invalid step model or latency.'
    }
    $executionId = [guid]::Empty
    switch -CaseSensitive ($Step.status) {
        'SKIPPED' {
            if (-not $CanSkip -or $null -ne $Step.requestId -or $Step.durationMs -ne 0 -or $null -ne $Step.response) {
                throw 'Invalid skipped step.'
            }
        }
        { $_ -cin 'SUCCESS', 'FAILED' } {
            if ($Step.requestId -isnot [string] -or -not [guid]::TryParse($Step.requestId, [ref]$executionId) -or
                $executionId -eq [guid]::Empty) { throw 'Invalid execution ID.' }
        }
        default { throw 'Unknown step status.' }
    }
    if ($Step.status -ceq 'SUCCESS') {
        if ($Step.response -isnot [string] -or $null -ne $Step.error) { throw 'Invalid successful step.' }
    } elseif ($Step.error.code -isnot [string] -or [string]::IsNullOrWhiteSpace($Step.error.code)) {
        throw 'Missing step error code.'
    }
    [ordered]@{
        requestId = if ($executionId -ne [guid]::Empty) { $executionId }
        model = $Step.model
        durationMs = $Step.durationMs
        status = $Step.status
        errorCode = $Step.error.code
        score = Get-Score $Step
    }
}

$findings = $allowed | ForEach-Object { "$_`: $($task.findings[$_])" }
$prompt = @($task.task, "$($task.language) source:", $task.code, 'Candidate findings:', ($findings -join "`n"), $fixture.outputContract) -join "`n`n"
if ($SelfReviewPath -and (-not $WorkflowPath -or $DatasetVersion -cne '2')) {
    throw 'A same-model control requires a v2 workflow.'
}
if ($StructuredComparisonV1 -and ($DatasetVersion -cne '2' -or $SelfReviewPath)) {
    throw 'Structured-output comparison v1 requires v2 fixtures without the same-model control.'
}
if (-not $WorkflowPath) { return $prompt }

$workflowBytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $WorkflowPath).ProviderPath)
$workflowText = [Text.UTF8Encoding]::new($false, $true).GetString($workflowBytes).TrimStart([char]0xFEFF)
$workflow = ConvertFrom-Json -InputObject $workflowText -AsHashtable -DateKind String
$proposal = Read-Step $workflow.proposal $false
$review = Read-Step $workflow.review $true
if ($proposal.model -ceq $review.model -or $proposal.requestId -eq $review.requestId -or
    ($proposal.status -ceq 'FAILED' -and $review.status -cne 'SKIPPED')) {
    throw 'Invalid proposal and review pairing.'
}

function Compare-Outcome([string]$Baseline, [string]$Candidate) {
    if ($Baseline -ceq 'UNAVAILABLE' -or $Candidate -ceq 'UNAVAILABLE') { return 'UNAVAILABLE' }
    if ($Baseline -cne 'PASS' -and $Candidate -ceq 'PASS') { return 'IMPROVED' }
    if ($Baseline -ceq 'PASS' -and $Candidate -cne 'PASS') { return 'REGRESSED' }
    'TIE'
}

function Get-Sha256([string]$Text) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
}

$report = [ordered]@{
    datasetId = $fixture.id
    datasetVersion = $fixture.version
    datasetSha256 = Get-Sha256 $fixtureText
    taskId = $TaskId
    promptSha256 = Get-Sha256 $prompt
    workflowSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($workflowBytes)).ToLowerInvariant()
    proposal = $proposal
    review = $review
    comparison = Compare-Outcome $proposal.score.outcome $review.score.outcome
}
if ($SelfReviewPath) {
    $controlBytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $SelfReviewPath).ProviderPath)
    $controlText = [Text.UTF8Encoding]::new($false, $true).GetString($controlBytes).TrimStart([char]0xFEFF)
    $control = Read-Step (ConvertFrom-Json -InputObject $controlText -AsHashtable -DateKind String) $false
    if ($control.model -cne $proposal.model -or $control.requestId -in $proposal.requestId, $review.requestId -or
        $review.status -ceq 'SKIPPED') { throw 'Invalid same-model control pairing.' }
    $report.selfReviewSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($controlBytes)).ToLowerInvariant()
    $report.selfReview = $control
    $report.reviewVsSelfReview = Compare-Outcome $control.score.outcome $review.score.outcome
}
if ($StructuredComparisonV1) { $report.scoringProtocol = 'structured-output-comparison-v1' }
$report | ConvertTo-Json -Depth 6
