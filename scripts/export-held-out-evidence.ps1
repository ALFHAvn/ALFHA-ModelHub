#Requires -Version 7.2
param(
    [Parameter(Mandatory)][string]$TaskPath,
    [Parameter(Mandatory)][string]$ExecutionPath,
    [Parameter(Mandatory)][datetimeoffset]$StartedAt,
    [string]$GradePath
)

$ErrorActionPreference = 'Stop'
$taskJson = (Get-Content -LiteralPath $TaskPath -Raw).Replace("`r`n", "`n")
$task = $taskJson | ConvertFrom-Json
$execution = Get-Content -LiteralPath $ExecutionPath -Raw | ConvertFrom-Json
$requestId = [guid]$execution.requestId
if ($requestId -eq [guid]::Empty -or -not $execution.timestamp -or -not $execution.model -or
    -not $task.id -or -not $task.version -or -not $task.prompt -or -not $task.cases -or
    $execution.prompt -cne $task.prompt) {
    throw 'Expected a saved execution for the exact held-out task prompt.'
}
$completedAt = [datetimeoffset]$execution.timestamp
if ($StartedAt -ge $completedAt) { throw 'The recorded request start must precede the saved execution timestamp.' }

function Get-Utf8Sha256([string]$value) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($value))).ToLowerInvariant()
}

$taskHash = Get-Utf8Sha256 $taskJson
$grade = $null
$score = 'NOT_SCORED'
if ($execution.status -ceq 'SUCCESS') {
    if (-not $GradePath -or $null -eq $execution.response -or $execution.error) {
        throw 'A successful execution requires its grader output.'
    }
    $grade = Get-Content -LiteralPath $GradePath -Raw | ConvertFrom-Json
    $sourceHash = Get-Utf8Sha256 $execution.response
    if ($grade.taskId -cne $task.id -or $grade.taskVersion -cne $task.version -or
        $grade.taskSha256 -ine $taskHash -or $grade.sourceSha256 -ine $sourceHash -or -not $grade.imageId) {
        throw 'Grader output does not match the task and saved response.'
    }
    $caseIds = @($grade.results | ForEach-Object id)
    if ($caseIds.Count -ne $task.cases.Count -or ($caseIds -join ',') -cne ($task.cases.id -join ',') -or
        @($grade.results | Where-Object { $_.outcome -cnotin @(
            'PASS', 'WRONG_ANSWER', 'COMPILATION_ERROR', 'EXECUTION_ERROR', 'QUERY_ERROR', 'TIMEOUT', 'OUTPUT_LIMIT'
        ) }).Count) {
        throw 'Grader output has missing, reordered, or invalid case results.'
    }
    $score = if (@($grade.results | Where-Object outcome -CNE 'PASS').Count) { 'FAIL' } else { 'PASS' }
} elseif ($execution.status -cne 'FAILED' -or -not $execution.error.code -or $GradePath) {
    throw 'A failed execution requires an error code and has no grader output.'
}

[ordered]@{
    id = $requestId
    startedAt = $StartedAt.ToString('o')
    completedAt = $completedAt.ToString('o')
    datasetSha256 = $taskHash
    scoringVersion = 'held-out-offline-v1'
    dataset = [ordered]@{
        id = $task.id
        version = $task.version
        tasks = @([ordered]@{ id = $task.id; prompt = $task.prompt })
    }
    results = @([ordered]@{
        taskId = $task.id
        score = $score
        execution = $execution | Select-Object requestId, model, durationMs, status, error
        grade = $grade
    })
} | ConvertTo-Json -Depth 12
