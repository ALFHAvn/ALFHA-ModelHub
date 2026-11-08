#Requires -Version 7.2
param(
    [Parameter(Mandatory)][string]$TaskId,
    [string]$WorkflowPath
)

$ErrorActionPreference = 'Stop'
$fixturePath = Join-Path $PSScriptRoot '../benchmarks/proposal-review/defect-identification-v1.json'
$fixtureText = [IO.File]::ReadAllText($fixturePath).Replace("`r`n", "`n")
$fixture = ConvertFrom-Json -InputObject $fixtureText -AsHashtable
$tasks = @($fixture.tasks | Where-Object id -CEQ $TaskId)
if ($tasks.Count -ne 1) { throw "Unknown or duplicate task ID: $TaskId" }
$task = $tasks[0]
$allowed = [string[]]@($task.findings.Keys)
[Array]::Sort($allowed, [StringComparer]::Ordinal)
$expected = @($task.expectedDefectIds)

function Get-Score($Step) {
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
            if ($id -cnotin $allowed -or -not $selected.Add($id)) { return $formatError }
        }
    } catch [System.Text.Json.JsonException], [InvalidOperationException] {
        return $formatError
    } finally {
        if ($null -ne $document) { $document.Dispose() }
    }

    $truePositives = @($expected | Where-Object { $selected.Contains($_) })
    $falsePositives = @($allowed | Where-Object { $_ -cnotin $expected -and $selected.Contains($_) })
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
if (-not $WorkflowPath) { return $prompt }

$workflowBytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $WorkflowPath).ProviderPath)
$workflowText = [Text.UTF8Encoding]::new($false, $true).GetString($workflowBytes).TrimStart([char]0xFEFF)
$workflow = ConvertFrom-Json -InputObject $workflowText -AsHashtable
$proposal = Read-Step $workflow.proposal $false
$review = Read-Step $workflow.review $true
if ($proposal.model -ceq $review.model -or $proposal.requestId -eq $review.requestId -or
    ($proposal.status -ceq 'FAILED' -and $review.status -cne 'SKIPPED')) {
    throw 'Invalid proposal and review pairing.'
}

$comparison = if ($proposal.score.outcome -ceq 'UNAVAILABLE' -or $review.score.outcome -ceq 'UNAVAILABLE') { 'UNAVAILABLE' }
elseif ($proposal.score.outcome -cne 'PASS' -and $review.score.outcome -ceq 'PASS') { 'IMPROVED' }
elseif ($proposal.score.outcome -ceq 'PASS' -and $review.score.outcome -cne 'PASS') { 'REGRESSED' }
else { 'TIE' }

function Get-Sha256([string]$Text) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
}

[ordered]@{
    datasetId = $fixture.id
    datasetVersion = $fixture.version
    datasetSha256 = Get-Sha256 $fixtureText
    taskId = $TaskId
    promptSha256 = Get-Sha256 $prompt
    workflowSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($workflowBytes)).ToLowerInvariant()
    proposal = $proposal
    review = $review
    comparison = $comparison
} | ConvertTo-Json -Depth 6
