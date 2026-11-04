param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:free$')][string]$CloudModel,
    [ValidateSet('1', '2')][string]$DatasetVersion = '2',
    [ValidateRange(1, 3)][int]$Repetitions = 3,
    [ValidateRange(1024, 65535)][int]$Port = 18086
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$project = 'modelhub-fixed-cloud-comparison'
$savedPort = $env:APP_PORT
$savedEnabled = $env:OPENROUTER_ENABLED
$savedModel = $env:OPENROUTER_MODEL
$started = $false
$apiKey = $null
$stage = 'preflight'
Push-Location $root
try {
    $env:APP_PORT = "$Port"
    $env:OPENROUTER_ENABLED = 'true'
    $env:OPENROUTER_MODEL = $CloudModel
    $output = Join-Path 'target' ('cloud-comparison-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
    New-Item -ItemType Directory -Path $output | Out-Null
    # Expanded Compose configuration contains credentials; capture it without printing.
    $configuration = (docker compose -p $project config --format json) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Invalid Compose configuration' }
    $apiKey = $configuration.services.app.environment.OPENROUTER_API_KEY
    $configuration = $null
    if ([string]::IsNullOrWhiteSpace($apiKey)) { throw 'Configure an OpenRouter key in ignored .env' }
    $catalog = Invoke-RestMethod 'https://openrouter.ai/api/v1/models' -TimeoutSec 30
    $selected = @($catalog.data | Where-Object id -eq $CloudModel)
    if ($selected.Count -ne 1 -or [decimal]$selected[0].pricing.prompt -ne 0 -or [decimal]$selected[0].pricing.completion -ne 0 -or
            [decimal]$selected[0].pricing.request -ne 0 -or $selected[0].architecture.output_modalities -notcontains 'text') {
        throw 'Select a currently listed zero-priced text model'
    }
    $selected[0] | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $output 'model-catalog-entry.json') -Encoding utf8
    function Read-FreeQuota {
        $state = Invoke-RestMethod 'https://openrouter.ai/api/v1/key' -Headers @{Authorization = ('Bearer ' + $apiKey)} -TimeoutSec 20
        if ($null -eq $state.data.free_model_daily_requests.remaining) { throw 'Could not confirm remaining free quota' }
        return $state.data.free_model_daily_requests
    }
    $quotaBefore = Read-FreeQuota
    $quotaBefore | ConvertTo-Json | Set-Content (Join-Path $output 'quota-before.json') -Encoding utf8
    if ($quotaBefore.remaining -lt 10 * $Repetitions) { throw 'Insufficient free quota for requested repetitions' }
    $started = $true
    $stage = 'Compose startup'
    docker compose -p $project up --build --detach --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Comparison stack startup failed' }
    $base = "http://localhost:$Port"
    $modelResponse = Invoke-RestMethod "$base/api/models" -TimeoutSec 10
    $models = @($modelResponse)
    $local = @($models | Where-Object provider -eq 'ollama')
    $cloud = @($models | Where-Object provider -eq 'openrouter')
    if ($local.Count -ne 1 -or $cloud.Count -ne 1 -or $cloud[0].model -ne $CloudModel) { throw 'Unexpected model configuration' }
    function Read-LocalRuntime {
        $tags = docker compose -p $project exec -T app sh -c 'curl -fsS "$OLLAMA_BASE_URL/api/tags"'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local model metadata' }
        $version = docker compose -p $project exec -T app sh -c 'curl -fsS "$OLLAMA_BASE_URL/api/version"'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local runtime version' }
        $model = @( (($tags -join "`n") | ConvertFrom-Json).models | Where-Object name -eq $local[0].model )
        if ($model.Count -ne 1) { throw 'Local model must already be installed' }
        return [ordered]@{ollamaVersion = (($version -join "`n") | ConvertFrom-Json).version; model = ($model[0] | Select-Object name, digest, details)}
    }
    $before = Read-LocalRuntime
    $before | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'runtime-before.json') -Encoding utf8
    $reports = @()
    for ($iteration = 1; $iteration -le $Repetitions; $iteration++) {
        $stage = "comparison repetition $iteration"
        Write-Output "Starting paired repetition $iteration/${Repetitions}: $($local[0].model) and $CloudModel"
        $body = @{datasetId = 'basics'; datasetVersion = $DatasetVersion; models = @($local[0].model, $CloudModel)} | ConvertTo-Json
        $created = Invoke-RestMethod "$base/api/benchmarks" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 1800
        $report = Invoke-RestMethod "$base/api/benchmarks/$($created.id)" -TimeoutSec 15
        if ($report.dataset.version -ne $DatasetVersion -or $report.results.Count -ne 20) { throw 'Unexpected report snapshot' }
        $report | ConvertTo-Json -Depth 40 | Set-Content (Join-Path $output "run-$iteration.json") -Encoding utf8
        $history = Invoke-RestMethod "$base/api/runs?page=0&size=100" -TimeoutSec 15
        foreach ($result in $report.results) {
            $matching = @($history.content | Where-Object requestId -eq $result.execution.requestId)
            if ($matching.Count -ne 1 -or $matching[0].response -cne $result.execution.response -or $matching[0].status -ne $result.execution.status) {
                throw 'History did not match the saved report'
            }
        }
        $reports += $report
        $report.summaries | Select-Object model, attempted, successful, failed, passed, scoringFailures, averageSuccessfulDurationMs | ConvertTo-Json -Compress | Write-Output
    }
    $stage = 'final verification'
    $after = Read-LocalRuntime
    $after | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'runtime-after.json') -Encoding utf8
    if ($before.model.digest -ne $after.model.digest -or $before.ollamaVersion -ne $after.ollamaVersion) { throw 'Local model/runtime changed during measurement' }
    if (@($reports.datasetSha256 | Select-Object -Unique).Count -ne 1) { throw 'Dataset changed during measurement' }
    $quotaAfter = Read-FreeQuota
    $quotaAfter | ConvertTo-Json | Set-Content (Join-Path $output 'quota-after.json') -Encoding utf8
    $verification = [ordered]@{commit = (git rev-parse HEAD); verifiedAtUtc = [DateTime]::UtcNow.ToString('o');
        datasetVersion = $DatasetVersion; repetitions = $Repetitions; fixedCloudModel = $CloudModel;
        cloudAttempts = 10 * $Repetitions; historyMatches = 20 * $Repetitions; localModelDigestUnchanged = $true;
        zeroCatalogPricingAtStart = $true; automaticRetries = $false; paidFallbacks = $false; reportIds = @($reports.id)}
    $verification | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $output 'verification.json') -Encoding utf8
    Write-Output "PASS: $Repetitions paired reports and saved history verified. Evidence: $output"
} catch {
    Write-Output "Comparison did not complete at stage: $stage. Preserve partial reports under target/cloud-comparison-*; no automatic retries were made."
    throw 'Inspect sanitized execution errors and quota before rerunning; do not share credentials.'
} finally {
    $apiKey = $null
    if ($started) { docker compose -p $project down }
    $env:APP_PORT = $savedPort
    $env:OPENROUTER_ENABLED = $savedEnabled
    $env:OPENROUTER_MODEL = $savedModel
    Pop-Location
}
