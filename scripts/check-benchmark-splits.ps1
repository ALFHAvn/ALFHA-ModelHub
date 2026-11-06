param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$DevelopmentReportPath,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$EvaluationReportPath,
    [string]$CampaignPath
)

$ErrorActionPreference = 'Stop'
$reportIds = [System.Collections.Generic.HashSet[guid]]::new()
$executionIds = [System.Collections.Generic.HashSet[guid]]::new()
$promptSplits = [System.Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$familySplits = [System.Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$datasets = @{}
$windows = @()
if ($CampaignPath) {
    $campaign = Get-Content -LiteralPath $CampaignPath -Raw | ConvertFrom-Json
    if (-not $campaign.id -or -not $campaign.policyFrozenAt -or -not $campaign.windows -or -not $campaign.datasets) {
        throw 'Campaign requires an ID, policy freeze time, windows, and dataset family maps.'
    }
    $freeze = [datetimeoffset]$campaign.policyFrozenAt
    $windowIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $windows = @(foreach ($window in $campaign.windows) {
        if (-not $window.id -or -not $windowIds.Add($window.id) -or
            $window.split -cnotin @('development', 'evaluation') -or -not $window.startedAt -or -not $window.completedAt) {
            throw "Invalid or repeated collection window: $($window.id)"
        }
        $start = [datetimeoffset]$window.startedAt
        $end = [datetimeoffset]$window.completedAt
        if ($end -le $start -or ($window.split -ceq 'development' -and $end -gt $freeze) -or
            ($window.split -ceq 'evaluation' -and $start -lt $freeze)) {
            throw "Invalid collection window or policy freeze boundary: $($window.id)"
        }
        [pscustomobject]@{ Id = $window.id; Split = $window.split; Start = $start; End = $end }
    })
    $windows = @($windows | Sort-Object Start, End)
    for ($index = 1; $index -lt $windows.Count; $index++) {
        if ($windows[$index].Start -lt $windows[$index - 1].End) {
            throw "Overlapping collection windows: $($windows[$index - 1].Id) and $($windows[$index].Id)"
        }
    }
    foreach ($dataset in $campaign.datasets) {
        if ($dataset.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $datasets.ContainsKey($dataset.sha256) -or
            -not $dataset.id -or -not $dataset.version -or -not $dataset.families) {
            throw "Invalid or repeated dataset family map: $($dataset.id)"
        }
        $datasets[$dataset.sha256] = $dataset
    }
}

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

            $windowId = $null
            if ($CampaignPath) {
                $window = $windows | Where-Object { $_.Split -ceq $split.Name -and $_.Start -le $start -and $_.End -ge $end }
                if (-not $window) { throw "Report falls outside its split's collection windows: $path" }
                $windowId = $window.Id
                if ($report.datasetSha256 -cnotmatch '^[a-f0-9]{64}$') { throw "Missing or invalid dataset hash: $path" }
                $dataset = $datasets[$report.datasetSha256]
                if (-not $dataset -or $dataset.id -cne $report.dataset.id -or $dataset.version -cne $report.dataset.version) {
                    throw "Missing matching dataset family map: $path"
                }
            }
            foreach ($task in $report.dataset.tasks) {
                if ([string]::IsNullOrWhiteSpace($task.prompt)) { throw "Missing task prompt: $path" }
                $prompt = $task.prompt.Trim().Replace("`r`n", "`n")
                if ($promptSplits.ContainsKey($prompt) -and $promptSplits[$prompt] -ne $split.Name) {
                    throw "Task prompt appears in both splits: $path"
                }
                $promptSplits[$prompt] = $split.Name
                if ($CampaignPath) {
                    if (-not $task.id) { throw "Missing dataset task ID: $path" }
                    $family = $dataset.families.PSObject.Properties[$task.id].Value
                    if ($family -cnotmatch '^[a-z0-9][a-z0-9-]*$') { throw "Missing or invalid scenario family for $($task.id): $path" }
                    if ($familySplits.ContainsKey($family) -and $familySplits[$family] -ne $split.Name) {
                        throw "Scenario family appears in both splits: $family"
                    }
                    $familySplits[$family] = $split.Name
                }
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
            [pscustomobject]@{ Path = $path; Split = $split.Name; Start = $start; End = $end; WindowId = $windowId }
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

$result = [ordered]@{
    DevelopmentReports = $DevelopmentReportPath.Count
    EvaluationReports = $EvaluationReportPath.Count
    DistinctExecutions = $executionIds.Count
}
if ($CampaignPath) {
    $result.CampaignId = $campaign.id
    foreach ($split in @('development', 'evaluation')) {
        $observed = @($runs | Where-Object Split -CEQ $split | ForEach-Object WindowId | Sort-Object -Unique -CaseSensitive)
        if ($observed.Count -lt 2) { throw "Expected reports from at least two collection windows for $split." }
        $result["${split}Windows"] = $observed
    }
}
[pscustomobject]$result
