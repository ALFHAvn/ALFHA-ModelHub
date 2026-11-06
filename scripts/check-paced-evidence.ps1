param(
    [Parameter(Mandatory = $true)]
    [ValidateCount(1, 20)]
    [string[]]$ReportPath
)

$ErrorActionPreference = 'Stop'
$reportIds = [System.Collections.Generic.HashSet[guid]]::new()
$executionIds = [System.Collections.Generic.HashSet[guid]]::new()
$signature = $null
$deploymentId = $null
$planned = $attempted = $skipped = $firstSuccess = $firstPass = 0
$providerFailures = $operationalErrors = $recovered = $terminalPass = $retryCalls = 0
foreach ($path in $ReportPath) {
    $report = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if (-not $report.id -or -not $reportIds.Add([guid]$report.id)) {
        throw "Missing or duplicate report ID: $path"
    }
    $local = @($report.models | Where-Object provider -CEQ 'ollama')
    $cloud = @($report.models | Where-Object provider -CEQ 'openrouter')
    if (@($report.models).Count -ne 2 -or $local.Count -ne 1 -or $cloud.Count -ne 1 -or
        $cloud[0].model -cnotmatch '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:free$' -or
        $report.dataset.id -cne 'basics' -or $report.dataset.version -cne '2' -or
        @($report.dataset.tasks).Count -ne 10 -or
        $report.datasetSha256 -cnotmatch '^[a-f0-9]{64}$') {
        throw "Expected paired local and fixed-free cloud evidence for basics v2: $path"
    }
    $summary = & "$PSScriptRoot/summarize-paced-benchmark.ps1" -Report $report
    $key = [ordered]@{
        dataset = @($report.dataset.id, $report.dataset.version, $report.datasetSha256)
        tasks = @($report.dataset.tasks | ForEach-Object id)
        scorer = $report.scoringVersion
        schedule = @($report.schedule.minIntervalMs, $report.schedule.maxRetries)
        local = @($local[0].model, $local[0].timeout, $local[0].inferenceSettings)
        cloud = @($cloud[0].model, $cloud[0].timeout, $cloud[0].inferenceSettings)
        deployment = $report.deploymentId
    } | ConvertTo-Json -Compress -Depth 4
    if ($null -eq $signature) {
        $signature = $key
        $deploymentId = $report.deploymentId
    } elseif ($signature -cne $key) {
        throw "Incompatible paced cohort report: $path"
    }

    $localRows = @($report.results | Where-Object { $_.execution.model -ceq $local[0].model })
    $localTasks = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($row in $localRows) {
        if ($row.taskId -cnotin $report.dataset.tasks.id -or -not $localTasks.Add($row.taskId) -or
            @($row.attempts).Count -ne 1 -or $row.attempts[0].requestId -cne $row.execution.requestId) {
            throw "Invalid local pairing: $path"
        }
    }
    if ($localTasks.Count -ne @($report.dataset.tasks).Count -or
        @($report.results).Count -ne ($localRows.Count + $summary.AttemptedTasks)) {
        throw "Incomplete or unexpected paired results: $path"
    }
    foreach ($row in $report.results) {
        foreach ($attempt in $row.attempts) {
            if (-not $attempt.requestId -or -not $executionIds.Add([guid]$attempt.requestId)) {
                throw "Missing or duplicate execution ID: $path"
            }
        }
    }
    $planned += $summary.PlannedTasks
    $attempted += $summary.AttemptedTasks
    $skipped += $summary.SkippedTasks
    $firstSuccess += $summary.FirstAttemptSuccesses
    $firstPass += $summary.FirstAttemptPasses
    $providerFailures += $summary.FirstAttemptProviderFailures
    $operationalErrors += $summary.FirstAttemptOperationalErrors
    $recovered += $summary.TasksRecovered
    $terminalPass += $summary.TerminalPasses
    $retryCalls += $summary.RetryCalls
}

$reasons = @(
    if (-not $deploymentId) { 'DEPLOYMENT_ID_MISSING' }
    if ($reportIds.Count -lt 3 -or $attempted -lt 30) { 'INSUFFICIENT_SAMPLE' }
    if ($skipped) { 'SKIPPED_TASKS' }
    if ($operationalErrors) { 'FIRST_ATTEMPT_OPERATIONAL_ERRORS' }
    if ($firstSuccess -lt 24) { 'INSUFFICIENT_FIRST_ATTEMPT_RESPONSES' }
    if ($firstSuccess + $providerFailures -gt 0 -and
        100 * $firstSuccess -lt 80 * ($firstSuccess + $providerFailures)) { 'LOW_FIRST_ATTEMPT_RELIABILITY' }
)

[pscustomobject]@{
    Policy = 'paced-cohort-v1'
    ReportCount = $reportIds.Count
    DeploymentId = $deploymentId
    PlannedCloudTasks = $planned
    FirstAttemptSuccesses = $firstSuccess
    FirstAttemptPasses = $firstPass
    FirstAttemptProviderFailures = $providerFailures
    FirstAttemptOperationalErrors = $operationalErrors
    SkippedTasks = $skipped
    RetryCalls = $retryCalls
    TasksRecovered = $recovered
    TerminalPasses = $terminalPass
    MeetsFirstAttemptGates = $reasons.Count -eq 0
    Reasons = $reasons
}
