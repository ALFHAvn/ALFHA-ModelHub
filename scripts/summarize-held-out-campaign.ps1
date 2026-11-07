#Requires -Version 7.2
param(
    [string]$CampaignPath = (Join-Path $PSScriptRoot '../benchmarks/held-out/local-campaign-v1.json'),
    [string]$RunDirectory,
    [string]$WindowId
)

$ErrorActionPreference = 'Stop'
$campaign = Get-Content -LiteralPath $CampaignPath -Raw | ConvertFrom-Json
if (-not $RunDirectory) { $RunDirectory = Join-Path $PSScriptRoot "../target/$($campaign.id)" }
$windows = @($campaign.windows | Where-Object { $_.split -ceq 'evaluation' -and
    (-not $WindowId -or $_.id -ceq $WindowId) })
if (-not $windows.Count) { throw 'No matching evaluation window.' }
$reportIds = [Collections.Generic.HashSet[guid]]::new()
$previousEnd = [datetimeoffset]::MinValue
$providerFailureCodes = @('RATE_LIMITED', 'TIMEOUT', 'CONNECTION_FAILURE', 'PROVIDER_ERROR', 'INVALID_PROVIDER_RESPONSE')
$rows = @(
    foreach ($window in $windows) {
        $journal = Join-Path $RunDirectory "$($window.id)/slots.jsonl"
        $slots = @(Get-Content -LiteralPath $journal | ForEach-Object { $_ | ConvertFrom-Json })
        $expected = @(
            foreach ($repetition in $window.repetitions) {
                $pair = 0
                foreach ($task in $campaign.taskOrder) {
                    $order = if ($pair % 2 -eq 0) { @(0, 1) } else { @(1, 0) }
                    foreach ($index in $order) {
                        [pscustomobject]@{ Repetition = $repetition; Task = $task; Model = $campaign.models[$index].id }
                    }
                    $pair++
                }
            }
        )
        if ($slots.Count -ne $expected.Count) { throw "Incomplete slot journal: $journal" }
        for ($index = 0; $index -lt $expected.Count; $index++) {
            $slot = $slots[$index]
            $planned = $expected[$index]
            if ($slot.repetition -ne $planned.Repetition -or $slot.task -cne $planned.Task -or $slot.model -cne $planned.Model) {
                throw "Unexpected slot order at row $($index + 1): $journal"
            }
            if ([datetimeoffset]$slot.startedAt -lt [datetimeoffset]$window.startedAt -or
                [datetimeoffset]$slot.startedAt -ge [datetimeoffset]$window.completedAt) {
                throw "Slot falls outside its collection window: $journal"
            }
            $row = [ordered]@{
                Window = $window.id; Repetition = $slot.repetition; Task = $slot.task; Model = $slot.model
                Score = $slot.score; Outcome = 'TOOLING_ERROR'; DurationMs = $null; RequestId = $null
            }
            if ($slot.score -ceq 'TOOLING_ERROR' -and -not $slot.evidencePath) {
                [pscustomobject]$row
                continue
            }
            $report = Get-Content -LiteralPath $slot.evidencePath -Raw | ConvertFrom-Json
            $result = $report.results[0]
            $execution = $result.execution
            $requestId = [guid]$report.id
            $start = [datetimeoffset]$report.startedAt
            $end = [datetimeoffset]$report.completedAt
            $fixturePath = Join-Path $PSScriptRoot "../benchmarks/held-out/$($planned.Task)-v1.json"
            $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
            $dataset = $campaign.datasets | Where-Object id -CEQ $fixture.id
            if (@($report.results).Count -ne 1 -or $requestId -eq [guid]::Empty -or
                $requestId -ne [guid]$execution.requestId -or -not $reportIds.Add($requestId) -or
                $execution.model -cne $planned.Model -or $result.score -cne $slot.score -or
                $result.taskId -cne $fixture.id -or $report.dataset.version -cne $fixture.version -or
                $report.datasetSha256 -cne $dataset.sha256 -or $report.scoringVersion -cne $campaign.scoringVersion -or
                $start -lt [datetimeoffset]$slot.startedAt -or $start -lt $previousEnd -or
                $end -le $start -or $end -gt [datetimeoffset]$window.completedAt) {
                throw "Unexpected or reused evidence: $($slot.evidencePath)"
            }
            $previousEnd = $end
            $row.RequestId = $requestId
            if ($execution.status -ceq 'SUCCESS' -and $slot.score -cin @('PASS', 'FAIL')) {
                $image = if ($slot.task -ceq 'sql-window') { $campaign.runtime.postgresImage } else { $campaign.runtime.javaGraderImage }
                if ($result.grade.imageId -cne $image -or $null -eq $execution.durationMs -or $execution.durationMs -lt 0) {
                    throw "Unexpected grader image or latency: $($slot.evidencePath)"
                }
                $row.Outcome = 'RESPONSE'
                $row.DurationMs = $execution.durationMs
            } elseif ($execution.status -ceq 'FAILED' -and $slot.score -ceq 'NOT_SCORED' -and $execution.error.code) {
                $row.Outcome = if ($execution.error.code -cin $providerFailureCodes) { 'PROVIDER_FAILURE' } else { 'OPERATIONAL_ERROR' }
            } else {
                throw "Inconsistent execution outcome: $($slot.evidencePath)"
            }
            [pscustomobject]$row
        }
    }
)

function Measure-Slots($slots) {
    $latencies = @($slots | Where-Object Outcome -CEQ 'RESPONSE' | ForEach-Object DurationMs | Sort-Object)
    $median = $null
    if ($latencies.Count) {
        $left = [int][math]::Floor(($latencies.Count - 1) / 2)
        $right = [int][math]::Floor($latencies.Count / 2)
        $median = ($latencies[$left] + $latencies[$right]) / 2
    }
    [ordered]@{
        slots = @($slots).Count
        passed = @($slots | Where-Object Score -CEQ 'PASS').Count
        failed = @($slots | Where-Object Score -CEQ 'FAIL').Count
        providerFailures = @($slots | Where-Object Outcome -CEQ 'PROVIDER_FAILURE').Count
        operationalErrors = @($slots | Where-Object Outcome -CEQ 'OPERATIONAL_ERROR').Count
        toolingErrors = @($slots | Where-Object Outcome -CEQ 'TOOLING_ERROR').Count
        successfulCalls = $latencies.Count
        medianSuccessfulLatencyMs = $median
    }
}

[ordered]@{
    campaignId = $campaign.id
    windows = @($windows.id)
    recordedSlots = $rows.Count
    evidenceReports = $reportIds.Count
    models = @(foreach ($model in $campaign.models) {
        $modelRows = @($rows | Where-Object Model -CEQ $model.id)
        [ordered]@{
            id = $model.id
            overall = Measure-Slots $modelRows
            tasks = @(foreach ($task in $campaign.taskOrder) {
                [ordered]@{ task = $task; metrics = Measure-Slots @($modelRows | Where-Object Task -CEQ $task) }
            })
        }
    })
    pairs = @(foreach ($pair in ($rows | Group-Object Repetition,Task)) {
        $baseline = $pair.Group | Where-Object Model -CEQ $campaign.models[0].id
        $challenger = $pair.Group | Where-Object Model -CEQ $campaign.models[1].id
        [ordered]@{ repetition = $baseline.Repetition; task = $baseline.Task; baseline = $baseline.Score; challenger = $challenger.Score }
    })
} | ConvertTo-Json -Depth 10
