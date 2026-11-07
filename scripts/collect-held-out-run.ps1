#Requires -Version 7.2
param(
    [Parameter(Mandatory)]
    [ValidateSet('regression-test', 'config-propagation', 'sql-window', 'concurrent-capacity')]
    [string]$Task,
    [Parameter(Mandatory)][string]$Model,
    [ValidateRange(1, 65535)][int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port"
$models = Invoke-RestMethod "$base/api/models" -TimeoutSec 5 -MaximumRedirection 0
$local = @($models | Where-Object { $_.provider -ceq 'ollama' -and $_.model -ceq $Model })
if ($local.Count -ne 1) { throw 'The requested model must be configured as Ollama on this ModelHub instance.' }

$taskPath = Join-Path $PSScriptRoot "../benchmarks/held-out/$Task-v1.json"
$fixture = Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
$directory = Join-Path $PSScriptRoot "../target/held-out-$Task-$([guid]::NewGuid().ToString('N'))"
$directory = (New-Item -ItemType Directory -Path $directory).FullName
$startedAt = [datetimeoffset]::UtcNow
$startedAt.ToString('o') | Set-Content -LiteralPath (Join-Path $directory 'started-at.txt')
$request = @{ model = $Model; prompt = $fixture.prompt } | ConvertTo-Json -Compress
$generation = Invoke-RestMethod "$base/api/generate" -Method Post -ContentType 'application/json' `
    -Body $request -SkipHttpErrorCheck -TimeoutSec 180 -MaximumRedirection 0
if (-not $generation.requestId) { throw "ModelHub did not return an execution ID. See $directory." }
$generation | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $directory 'generation.json')

$page = Invoke-RestMethod "$base/api/runs?size=100" -TimeoutSec 5 -MaximumRedirection 0
$matches = @($page.content | Where-Object requestId -CEQ $generation.requestId)
if ($matches.Count -ne 1 -or $matches[0].model -cne $Model -or $matches[0].status -cne $generation.status -or
    $matches[0].response -cne $generation.response) {
    throw "The saved execution did not match the generation response. See $directory."
}
$saved = $matches[0]
$executionPath = Join-Path $directory 'execution.json'
$saved | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $executionPath

$exportArgs = @{ TaskPath = $taskPath; ExecutionPath = $executionPath; StartedAt = $startedAt }
if ($saved.status -ceq 'SUCCESS') {
    $extension = if ($Task -ceq 'sql-window') { 'sql' } else { 'java' }
    $sourcePath = Join-Path $directory "answer.$extension"
    [IO.File]::WriteAllText($sourcePath, $saved.response, [Text.UTF8Encoding]::new($false))
    $grade = switch ($Task) {
        'sql-window' { & "$PSScriptRoot/run-sql-window-pilot.ps1" -SourcePath $sourcePath }
        'regression-test' { & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $sourcePath -RegressionTest }
        'config-propagation' { & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $sourcePath -ConfigPropagation }
        'concurrent-capacity' { & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $sourcePath -ConcurrentCapacity }
    }
    $gradePath = Join-Path $directory 'grade.json'
    $grade | Set-Content -LiteralPath $gradePath
    $exportArgs.GradePath = $gradePath
}
$evidencePath = Join-Path $directory 'evidence.json'
& "$PSScriptRoot/export-held-out-evidence.ps1" @exportArgs | Set-Content -LiteralPath $evidencePath
Get-Item -LiteralPath $evidencePath
