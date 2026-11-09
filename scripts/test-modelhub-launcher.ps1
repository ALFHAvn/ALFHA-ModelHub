param([int] $AppPort = 18080)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $PSScriptRoot 'modelhub-launcher.ps1'
$project = "modelhub-launcher-test-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$compose = @('compose', '--project-name', $project, '--project-directory', $root, '--file', (Join-Path $root 'compose.yaml'))
$testEnvironment = @{
    COMPOSE_PROJECT_NAME = $project
    DATABASE_PASSWORD = [guid]::NewGuid().ToString('N')
    APP_PORT = "$AppPort"
    OLLAMA_BASE_URL = 'http://127.0.0.1:1'
    OPENROUTER_ENABLED = 'false'
}
$originalEnvironment = @{}
$browserOpens = [System.Collections.Generic.List[string]]::new()
$rejectHealth = $false
$rejectFrontend = $false

function Start-Process([string] $FilePath) { $browserOpens.Add($FilePath) }
function Invoke-RestMethod($Uri, [switch] $UseBasicParsing, $TimeoutSec, $MaximumRedirection) {
    if ($rejectHealth) { throw 'Simulated backend health failure.' }
    Microsoft.PowerShell.Utility\Invoke-RestMethod @PSBoundParameters
}
function Invoke-WebRequest($Uri, [switch] $UseBasicParsing, $TimeoutSec, $MaximumRedirection) {
    if ($rejectFrontend) { throw 'Simulated frontend readiness failure.' }
    Microsoft.PowerShell.Utility\Invoke-WebRequest @PSBoundParameters
}

function Assert-Launcher([bool] $condition, [string] $message) {
    if (!$condition) { throw $message }
}

$unrelated = "$project-unrelated"
$unrelatedCreated = $false
try {
    foreach ($key in $testEnvironment.Keys) {
        $originalEnvironment[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, $testEnvironment[$key])
    }
    docker run --detach --name $unrelated --entrypoint sleep postgres:17-alpine 900 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the unrelated test container.' }
    $unrelatedCreated = $true
    $url = "http://127.0.0.1:$AppPort/"

    Push-Location -LiteralPath (Join-Path $root 'web')
    try {
        $startup = & $launcher Start 3>&1 6>&1
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    $startup | Write-Host
    Assert-Launcher ($code -eq 0 -and $browserOpens.Count -eq 1 -and $browserOpens[0] -eq $url) 'Cold start did not hand off the ready URL.'
    Assert-Launcher (($startup -join ' ') -match 'Ollama is unreachable') 'Expected a visible warning for unreachable Ollama.'
    $started = @(docker @compose ps --quiet app db | Sort-Object)
    Assert-Launcher ($started.Count -eq 2) 'Expected one app and one database container.'

    & $launcher Start
    Assert-Launcher ($LASTEXITCODE -eq 0 -and $browserOpens.Count -eq 2) 'Second start did not succeed.'
    $restarted = @(docker @compose ps --quiet app db | Sort-Object)
    Assert-Launcher (($started -join ',') -eq ($restarted -join ',')) 'Second start replaced or duplicated containers.'

    $rejectHealth = $true
    $failed = & $launcher Start 6>&1
    Assert-Launcher ($LASTEXITCODE -ne 0 -and $browserOpens.Count -eq 2 -and
        ($failed -join ' ') -match 'backend did not pass the readiness check') 'Backend failure must prevent the browser from opening.'
    $rejectHealth = $false

    $rejectFrontend = $true
    $failed = & $launcher Start 6>&1
    Assert-Launcher ($LASTEXITCODE -ne 0 -and $browserOpens.Count -eq 2 -and
        ($failed -join ' ') -match 'frontend did not pass the readiness check') 'Frontend failure must prevent the browser from opening.'
    $rejectFrontend = $false

    $originalDockerHost = $env:DOCKER_HOST
    $originalDockerContext = $env:DOCKER_CONTEXT
    try {
        $env:DOCKER_CONTEXT = $null
        $env:DOCKER_HOST = "npipe:////./pipe/$project-unavailable"
        $failed = & $launcher Start 6>&1
        Assert-Launcher ($LASTEXITCODE -ne 0 -and $browserOpens.Count -eq 2 -and
            ($failed -join ' ') -match 'Docker is unavailable') 'Docker failure must prevent the browser from opening.'
    } finally {
        $env:DOCKER_HOST = $originalDockerHost
        $env:DOCKER_CONTEXT = $originalDockerContext
    }

    & $launcher Stop
    Assert-Launcher ($LASTEXITCODE -eq 0) 'Stop launcher failed.'
    $running = @(docker @compose ps --quiet app db)
    Assert-Launcher ($LASTEXITCODE -eq 0 -and $running.Count -eq 0) 'ModelHub containers are still running.'
    $unrelatedRunning = docker inspect --format '{{.State.Running}}' $unrelated
    Assert-Launcher ($unrelatedRunning -eq 'true') 'The unrelated container stopped.'
    docker volume inspect "${project}_postgres-data" --format '{{.Name}}' | Out-Null
    Assert-Launcher ($LASTEXITCODE -eq 0) 'The database volume was removed.'
    Write-Host 'Launcher checks passed. Browser handoffs were captured without opening tabs.'
} finally {
    docker @compose down --volumes --rmi local | Out-Null
    if ($unrelatedCreated) { docker rm --force $unrelated | Out-Null }
    foreach ($key in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $originalEnvironment[$key])
    }
}
