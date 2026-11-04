param(
    [int]$Port = 18083,
    [string]$ProjectName = 'modelhub-cloud-check'
)

$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Use a port from 1024 to 65535.' }
if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]+$') { throw 'Invalid Compose project name.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) {
    throw 'Create .env from .env.example and set DATABASE_PASSWORD, OPENROUTER_API_KEY, and a free OPENROUTER_MODEL first.'
}

$savedPort = $env:APP_PORT
$savedEnabled = $env:OPENROUTER_ENABLED
$started = $false
$stage = 'Compose configuration'
Push-Location $projectRoot
try {
    $env:APP_PORT = "$Port"
    $env:OPENROUTER_ENABLED = 'true'
    $null = New-Item -ItemType Directory -Force target
    # Never print expanded Compose configuration: it contains credentials.
    docker compose -p $ProjectName config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
    $started = $true
    $stage = 'Compose startup'
    docker compose -p $ProjectName up --build --detach --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed.' }

    $base = "http://localhost:$Port"
    $stage = 'Model selection'
    $modelResponse = Invoke-RestMethod "$base/api/models" -TimeoutSec 10
    $models = @($modelResponse)
    $local = @($models | Where-Object provider -eq 'ollama')
    $cloud = @($models | Where-Object provider -eq 'openrouter')
    if ($local.Count -ne 1 -or $cloud.Count -ne 1) { throw 'Expected one configured local model and one free cloud model.' }
    $prompt = 'Reply with one short sentence defining REST. /no_think'
    $generationBody = @{model = $cloud[0].model; prompt = $prompt} | ConvertTo-Json
    $stage = 'OpenRouter generation'
    $generation = Invoke-RestMethod "$base/api/generate" -Method Post -ContentType 'application/json' -Body $generationBody -TimeoutSec 180
    if ($generation.status -ne 'SUCCESS' -or [string]::IsNullOrWhiteSpace($generation.response)) {
        throw 'Cloud generation did not return a successful text response.'
    }
    $comparisonBody = @{models = @($local[0].model, $cloud[0].model); prompt = $prompt} | ConvertTo-Json
    $stage = 'Real model comparison'
    $comparison = Invoke-RestMethod "$base/api/compare" -Method Post -ContentType 'application/json' -Body $comparisonBody -TimeoutSec 180
    if (@($comparison.results).Count -ne 2) { throw 'Expected two comparison results.' }
    foreach ($run in $comparison.results) {
        if ($run.status -ne 'SUCCESS' -or [string]::IsNullOrWhiteSpace($run.response)) {
            throw 'Both providers must succeed for the real comparison acceptance check.'
        }
    }
    $stage = 'History correlation'
    $history = Invoke-RestMethod "$base/api/runs?page=0&size=100" -TimeoutSec 15
    $verified = @($generation) + @($comparison.results)
    foreach ($run in $verified) {
        $saved = @($history.content | Where-Object requestId -eq $run.requestId)
        if ($saved.Count -ne 1 -or $saved[0].status -ne 'SUCCESS' -or $saved[0].response -ne $run.response) {
            throw 'History did not match the completed execution.'
        }
    }
    $report = [ordered]@{
        verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
        commit = (git rev-parse HEAD)
        cloudGeneration = 'SUCCESS'
        realComparison = 'SUCCESS'
        historyCorrelation = 'SUCCESS'
        executions = @($verified | Select-Object requestId, model, status, durationMs)
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath 'target/cloud-validation.json' -Encoding utf8
    Write-Output 'PASS: real cloud generation, local/cloud comparison, and saved history.'
    Write-Output 'Evidence: target/cloud-validation.json (contains no key or response text).'
} catch {
    # Avoid printing arbitrary upstream/command exceptions that could contain secrets.
    Write-Output "Cloud validation did not pass at stage: $stage. Check provider configuration, free-model availability, and local Ollama connectivity; do not share credentials."
    throw 'Acceptance checks remain pending; no release tag was created.'
} finally {
    if ($started) {
        docker compose -p $ProjectName down
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Test stack cleanup failed; stop this Compose project manually.' }
    }
    $env:APP_PORT = $savedPort
    $env:OPENROUTER_ENABLED = $savedEnabled
    Pop-Location
}
