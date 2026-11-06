$ErrorActionPreference = 'Stop'
$fixture = Join-Path $PSScriptRoot '../benchmarks/comparisons/2026-11-06-qwen3-vs-lfm25-v2-paced/run-1.json'
$checker = Join-Path $PSScriptRoot 'check-paced-evidence.ps1'
$raw = Get-Content -LiteralPath $fixture -Raw
$single = & $checker -ReportPath $fixture
if ($single.MeetsFirstAttemptGates -or $single.FirstAttemptPasses -ne 4 -or
    $single.TasksRecovered -ne 5 -or $single.TerminalPasses -ne 9) {
    throw 'Unexpected result for the checked-in paced report.'
}

$directory = Join-Path $PSScriptRoot "../target/paced-evidence-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $directory | Out-Null
$paths = @(1..3 | ForEach-Object { Join-Path $directory "report-$_.json" })
try {
    foreach ($path in $paths) {
        $report = $raw | ConvertFrom-Json
        $report.id = [guid]::NewGuid()
        $report | Add-Member -NotePropertyName deploymentId -NotePropertyValue 'fixture-r1'
        foreach ($row in $report.results) {
            foreach ($attempt in $row.attempts) { $attempt.requestId = [guid]::NewGuid() }
            $row.execution.requestId = $row.attempts[-1].requestId
        }
        $report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $path
    }
    $recovery = & $checker -ReportPath $paths
    if ($recovery.MeetsFirstAttemptGates -or $recovery.FirstAttemptSuccesses -ne 12 -or
        $recovery.TerminalPasses -ne 27 -or 'LOW_FIRST_ATTEMPT_RELIABILITY' -cnotin $recovery.Reasons) {
        throw 'Retries inflated first-attempt eligibility.'
    }

    foreach ($path in $paths) {
        $report = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        $recovered = @($report.results | Where-Object { $_.attempts.Count -gt 1 -and $_.score -ceq 'PASS' })
        foreach ($row in $recovered[0..3]) { $row.attempts = @($row.execution) }
        $report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $path
    }
    $boundary = & $checker -ReportPath $paths
    if (-not $boundary.MeetsFirstAttemptGates -or $boundary.FirstAttemptSuccesses -ne 24) {
        throw 'A matched cohort at the 80 percent first-attempt boundary did not qualify.'
    }

    $report.schedule.minIntervalMs++
    $report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $paths[-1]
    try {
        & $checker -ReportPath $paths
        throw 'Accepted incompatible schedules.'
    } catch {
        if ($_.Exception.Message -notlike 'Incompatible paced cohort report:*') { throw }
    }
    try {
        & $checker -ReportPath @($paths[0], $paths[0])
        throw 'Accepted duplicate report IDs.'
    } catch {
        if ($_.Exception.Message -notlike 'Missing or duplicate report ID:*') { throw }
    }
    'Passed paced evidence checks.'
} finally {
    foreach ($path in $paths) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
    Remove-Item -LiteralPath $directory
}
