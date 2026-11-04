param(
    [ValidateSet('1', '2')][string]$DatasetVersion = '2',
    [ValidateRange(1, 5)][int]$Repetitions = 3,
    [ValidateRange(1024, 65535)][int]$Port = 18085
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$project = 'modelhub-repeated-baseline'
$savedPort = $env:APP_PORT
$savedEnabled = $env:OPENROUTER_ENABLED
$started = $false
Push-Location $root
try {
    $env:APP_PORT = "$Port"
    $env:OPENROUTER_ENABLED = 'false'
    $output = Join-Path 'target' ("local-baselines-v$DatasetVersion-" + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
    New-Item -ItemType Directory -Path $output | Out-Null
    docker compose -p $project config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Invalid Compose configuration' }
    $started = $true
    docker compose -p $project up --build --detach --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Baseline stack startup failed' }
    $base = "http://localhost:$Port"
    $modelResponse = Invoke-RestMethod "$base/api/models" -TimeoutSec 10
    $models = @($modelResponse)
    if ($models.Count -ne 1 -or $models[0].provider -ne 'ollama') { throw 'Expected local-only model configuration' }
    $model = $models[0].model

    function Read-LocalRuntime {
        $tags = docker compose -p $project exec -T app sh -c 'curl -fsS "$OLLAMA_BASE_URL/api/tags"'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local model metadata' }
        $version = docker compose -p $project exec -T app sh -c 'curl -fsS "$OLLAMA_BASE_URL/api/version"'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local runtime version' }
        $selected = @( (($tags -join "`n") | ConvertFrom-Json).models | Where-Object name -eq $model )
        if ($selected.Count -ne 1) { throw 'Configured local model must already be installed' }
        return [ordered]@{ ollamaVersion = (($version -join "`n") | ConvertFrom-Json).version; model = ($selected[0] | Select-Object name, digest, details) }
    }
    $before = Read-LocalRuntime
    $before | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'runtime-before.json') -Encoding utf8
    $reports = @()
    for ($iteration = 1; $iteration -le $Repetitions; $iteration++) {
        Write-Output "Starting local repetition $iteration/${Repetitions}: $model, basics v$DatasetVersion"
        $body = @{datasetId = 'basics'; datasetVersion = $DatasetVersion; models = @($model)} | ConvertTo-Json
        $created = Invoke-RestMethod "$base/api/benchmarks" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 1800
        $report = Invoke-RestMethod "$base/api/benchmarks/$($created.id)" -TimeoutSec 15
        if ($report.dataset.version -ne $DatasetVersion -or $report.results.Count -ne 10) { throw 'Unexpected report snapshot' }
        $report | ConvertTo-Json -Depth 40 | Set-Content (Join-Path $output "run-$iteration.json") -Encoding utf8
        $history = Invoke-RestMethod "$base/api/runs?page=0&size=100" -TimeoutSec 15
        foreach ($result in $report.results) {
            $matching = @($history.content | Where-Object requestId -eq $result.execution.requestId)
            if ($matching.Count -ne 1 -or $matching[0].response -cne $result.execution.response -or $matching[0].status -ne $result.execution.status) {
                throw 'Execution history did not match the saved report'
            }
        }
        $reports += $report
        $report.summaries | Select-Object model, attempted, failed, passed, averageSuccessfulDurationMs | ConvertTo-Json -Compress | Write-Output
    }
    $after = Read-LocalRuntime
    $after | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'runtime-after.json') -Encoding utf8
    if ($before.model.digest -ne $after.model.digest -or $before.ollamaVersion -ne $after.ollamaVersion) { throw 'Model/runtime changed during measurement' }
    if (@($reports.datasetSha256 | Select-Object -Unique).Count -ne 1) { throw 'Dataset changed during measurement' }
    $verification = [ordered]@{commit = (git rev-parse HEAD); verifiedAtUtc = [DateTime]::UtcNow.ToString('o');
        datasetVersion = $DatasetVersion; repetitions = $Repetitions; cloudEnabled = $false;
        historyMatches = 10 * $Repetitions; modelDigestUnchanged = $true; reportIds = @($reports.id)}
    $verification | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'verification.json') -Encoding utf8
    Write-Output "PASS: $Repetitions local runs and saved history verified. Evidence: $output"
} catch {
    Write-Output 'Local measurement did not complete; already exported reports are preserved under target/local-baselines-*. No automatic retries were made.'
    throw 'Inspect local runtime/configuration and partial evidence before rerunning.'
} finally {
    if ($started) { docker compose -p $project down }
    $env:APP_PORT = $savedPort
    $env:OPENROUTER_ENABLED = $savedEnabled
    Pop-Location
}
