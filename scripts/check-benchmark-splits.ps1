param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$DevelopmentReportPath,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$EvaluationReportPath
)

$ErrorActionPreference = 'Stop'
$reportIds = [System.Collections.Generic.HashSet[guid]]::new()
$executionIds = [System.Collections.Generic.HashSet[guid]]::new()
$promptSplits = [System.Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$runs = @(
    foreach ($split in @(
        @{ Name = 'development'; Paths = $DevelopmentReportPath }
        @{ Name = 'evaluation'; Paths = $EvaluationReportPath }
    )) {
        foreach ($path in $split.Paths) {
            $report = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            $reportId = [guid]$report.id
            if ($reportId -eq [guid]::Empty -or -not $reportIds.Add($reportId)) {
                throw "Missing or reused report ID: $path"
            }
            if (-not $report.startedAt -or -not $report.completedAt -or -not $report.dataset.tasks -or -not $report.results) {
                throw "Missing report timestamps, dataset tasks, or results: $path"
            }
            $start = [datetimeoffset]$report.startedAt
            $end = [datetimeoffset]$report.completedAt
            if ($end -le $start) { throw "Invalid report interval: $path" }

            foreach ($task in $report.dataset.tasks) {
                if ([string]::IsNullOrWhiteSpace($task.prompt)) { throw "Missing task prompt: $path" }
                $prompt = $task.prompt.Trim().Replace("`r`n", "`n")
                if ($promptSplits.ContainsKey($prompt) -and $promptSplits[$prompt] -ne $split.Name) {
                    throw "Task prompt appears in both splits: $path"
                }
                $promptSplits[$prompt] = $split.Name
            }

            foreach ($row in $report.results) {
                # Older exports store only the terminal execution.
                $attempts = @($row.attempts)
                if ($null -eq $row.attempts) { $attempts = @($row.execution) }
                if ($attempts.Count -eq 0 -or -not $row.execution.requestId -or
                    [guid]$attempts[-1].requestId -ne [guid]$row.execution.requestId) {
                    throw "Missing or inconsistent terminal execution: $path"
                }
                foreach ($attempt in $attempts) {
                    $executionId = [guid]$attempt.requestId
                    if ($executionId -eq [guid]::Empty -or -not $executionIds.Add($executionId)) {
                        throw "Missing or reused execution ID: $path"
                    }
                }
            }
            [pscustomobject]@{ Path = $path; Start = $start; End = $end }
        }
    }
)

$ordered = @($runs | Sort-Object Start, End)
for ($index = 1; $index -lt $ordered.Count; $index++) {
    $previous = $ordered[$index - 1]
    $current = $ordered[$index]
    if ($current.Start -lt $previous.End) {
        throw "Overlapping report intervals: $($previous.Path) and $($current.Path)"
    }
}

[pscustomobject]@{
    DevelopmentReports = $DevelopmentReportPath.Count
    EvaluationReports = $EvaluationReportPath.Count
    DistinctExecutions = $executionIds.Count
}
