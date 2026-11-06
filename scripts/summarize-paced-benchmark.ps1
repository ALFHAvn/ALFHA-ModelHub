param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
$report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$cloud = @($report.models | Where-Object provider -CEQ 'openrouter')
if ($null -eq $report.schedule -or $report.scoringVersion -cne 'objective-v1' -or $cloud.Count -ne 1) {
    throw 'Expected a paced objective benchmark report with exactly one OpenRouter model.'
}

$rows = @($report.results | Where-Object { $_.execution.model -ceq $cloud[0].model })
$taskIds = @($report.dataset.tasks | ForEach-Object id)
$seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$skipped = @($report.skippedCloudTasks | Where-Object { $null -ne $_ })
foreach ($id in $skipped) {
    if ($id -cnotin $taskIds -or -not $seen.Add($id)) { throw "Invalid skipped task: $id" }
}

$firstSuccess = $firstRateLimited = $firstOtherFailure = $retried = $recovered = $terminalSuccess = 0
$firstPass = $terminalPass = $totalCalls = 0
foreach ($row in $rows) {
    if ($row.taskId -cnotin $taskIds -or -not $seen.Add($row.taskId)) {
        throw "Unknown, duplicate, or skipped cloud result: $($row.taskId)"
    }
    $attempts = @($row.attempts)
    if ($attempts.Count -eq 0 -or $attempts -contains $null -or
        $attempts.Count -gt (1 + $report.schedule.maxRetries) -or
        -not $row.execution.requestId -or $attempts[-1].requestId -cne $row.execution.requestId) {
        throw "Invalid attempt history for cloud task: $($row.taskId)"
    }
    $first = $attempts[0]
    $totalCalls += $attempts.Count
    if ($first.status -ceq 'SUCCESS') {
        $firstSuccess++
        if ($row.score -ceq 'PASS') { $firstPass++ }
    } elseif ($first.error.code -ceq 'RATE_LIMITED') {
        $firstRateLimited++
    } else {
        $firstOtherFailure++
    }
    if ($row.execution.status -ceq 'SUCCESS') {
        $terminalSuccess++
        if ($row.score -ceq 'PASS') { $terminalPass++ }
    }
    if ($attempts.Count -gt 1) {
        $retried++
        foreach ($attempt in $attempts[0..($attempts.Count - 2)]) {
            if ($attempt.status -cne 'FAILED' -or $attempt.error.code -cne 'RATE_LIMITED') {
                throw "Unexpected retry for cloud task: $($row.taskId)"
            }
        }
        if ($row.execution.status -ceq 'SUCCESS') { $recovered++ }
    }
}
if ($seen.Count -ne $taskIds.Count) { throw 'Cloud results and skips do not cover the dataset.' }

[pscustomobject]@{
    ReportId = $report.id
    Model = $cloud[0].model
    PlannedTasks = $taskIds.Count
    AttemptedTasks = $rows.Count
    SkippedTasks = $skipped.Count
    FirstAttemptSuccesses = $firstSuccess
    FirstAttemptPasses = $firstPass
    FirstAttemptRateLimits = $firstRateLimited
    FirstAttemptOtherFailures = $firstOtherFailure
    TasksRetried = $retried
    TasksRecovered = $recovered
    RetryCalls = $totalCalls - $rows.Count
    TerminalSuccesses = $terminalSuccess
    TerminalPasses = $terminalPass
    TotalCloudCalls = $totalCalls
}
