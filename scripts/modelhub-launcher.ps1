param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Start', 'Stop')]
    [string] $Action
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $root 'compose.yaml'
$compose = @('compose', '--project-directory', $root, '--file', $composeFile)

function Assert-ModelHubContainers {
    $containers = & $docker.Source @compose ps --all --quiet app db
    if ($LASTEXITCODE -ne 0) { throw 'Could not check the ModelHub containers.' }
    foreach ($id in $containers) {
        $json = & $docker.Source inspect --format '{{json .Config.Labels}}' $id
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the ownership of a Compose container.' }
        $labels = $json | ConvertFrom-Json
        if ($labels.'com.docker.compose.project.working_dir' -ne $root -or
            $labels.'com.docker.compose.project.config_files' -ne $composeFile) {
            throw 'A different Compose configuration owns containers with this project name. Check COMPOSE_PROJECT_NAME before proceeding.'
        }
    }
}

function Confirm-ModelHubReady([string] $url) {
    try {
        $health = Invoke-RestMethod -Uri "$url/api/health" -UseBasicParsing -TimeoutSec 5 -MaximumRedirection 0
        if ($health.status -cne 'UP' -or $health.application -cne 'ALFHA ModelHub') {
            throw 'The health response does not identify a ready ModelHub backend.'
        }
    } catch {
        throw "ModelHub backend did not pass the readiness check at $url/api/health. Run docker compose logs --tail=100 app db from the repository."
    }
    try {
        $page = Invoke-WebRequest -Uri "$url/" -UseBasicParsing -TimeoutSec 5 -MaximumRedirection 0
        if ($page.Content -notmatch '<title>ALFHA ModelHub</title>' -or
            $page.Content -notmatch 'src="(/assets/[A-Za-z0-9_-]+\.js)"') {
            throw 'The packaged frontend is missing.'
        }
        $asset = Invoke-WebRequest -Uri "$url$($Matches[1])" -UseBasicParsing -TimeoutSec 5 -MaximumRedirection 0
        if ($asset.RawContentLength -le 0 -or $asset.Headers['Content-Type'] -notmatch 'javascript') {
            throw 'The frontend JavaScript is unavailable.'
        }
    } catch {
        throw "ModelHub frontend did not pass the readiness check at $url/. Check the image build output and app logs."
    }
}

function Test-OllamaFromApp([string] $url) {
    $ErrorActionPreference = 'Continue'
    $response = & $docker.Source @compose exec -T app curl --fail --silent --max-time 4 -- "$url/api/tags" 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    try { return ($null -ne ($response | ConvertFrom-Json -ErrorAction Stop).models) } catch { return $false }
}

function Confirm-Ollama {
    $ErrorActionPreference = 'Continue'
    $configuredUrl = & $docker.Source @compose exec -T app printenv OLLAMA_BASE_URL 2>$null
    if ($LASTEXITCODE -ne 0 -or !$configuredUrl) { return $false }
    $url = $configuredUrl.TrimEnd('/')
    if (Test-OllamaFromApp $url) { return $true }
    if ($url -ne 'http://host.docker.internal:11434') { return $false }

    $ollama = Get-Command ollama -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$ollama) { return $false }
    if (!(Get-Process -Name ollama -ErrorAction SilentlyContinue)) {
        Write-Host 'Starting the installed Ollama server on localhost...'
        $previousHost = $env:OLLAMA_HOST
        try {
            $env:OLLAMA_HOST = '127.0.0.1:11434'
            Start-Process -FilePath $ollama.Source -ArgumentList 'serve' -WindowStyle Hidden -ErrorAction Stop
        } finally {
            $env:OLLAMA_HOST = $previousHost
        }
    }
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        Start-Sleep -Seconds 1
        if (Test-OllamaFromApp $url) { return $true }
    }
    return $false
}

$mutex = [System.Threading.Mutex]::new($false, 'Local\ALFHA-ModelHub-Launcher')
$locked = $false
$locationChanged = $false
$exitCode = 0
try {
    try { $locked = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw 'Another ModelHub start or stop is in progress. Wait for it to finish.' }
    Push-Location -LiteralPath $root
    $locationChanged = $true

    $docker = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$docker) { throw 'Docker was not found. Install and start Docker Desktop with Docker Compose support.' }
    & $docker.Source compose version --short | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is unavailable. Check your Docker Desktop installation.' }
    $engine = & $docker.Source info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable. Start Docker Desktop and wait for it to be ready.' }
    if ($engine -ne 'linux') { throw 'Switch Docker Desktop to Linux containers and retry.' }

    & $docker.Source @compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid. Check .env and set DATABASE_PASSWORD as described in the README.' }
    Assert-ModelHubContainers

    if ($Action -eq 'Stop') {
        & $docker.Source @compose stop app db
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the ModelHub services. Run docker compose ps from the repository.' }
        Write-Host 'ModelHub stopped. Saved data is retained.'
    } else {
        Write-Host 'Starting ModelHub. The first image build can take several minutes...'
        & $docker.Source @compose up --detach --build --wait --wait-timeout 120 app
        if ($LASTEXITCODE -ne 0) {
            throw 'ModelHub services did not start or become healthy. Check Docker output above and run docker compose logs --tail=100 app db.'
        }
        $binding = & $docker.Source @compose port app 8080
        if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:[0-9]{1,5}$') {
            throw 'ModelHub does not have the expected localhost port. Check APP_PORT and the Compose configuration.'
        }
        $url = "http://$binding"
        Confirm-ModelHubReady $url
        $ollamaReady = $false
        try { $ollamaReady = Confirm-Ollama } catch { }
        if (!$ollamaReady) {
            Write-Warning 'Ollama is unreachable from ModelHub. The interface and saved reports are available. Check Ollama and OLLAMA_BASE_URL before using a local model.'
        }
        Write-Host "ModelHub is ready at $url/"
        Start-Process -FilePath "$url/"
    }
} catch {
    Write-Host "ModelHub $Action failed. $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
} finally {
    if ($locationChanged) { Pop-Location }
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
exit $exitCode
