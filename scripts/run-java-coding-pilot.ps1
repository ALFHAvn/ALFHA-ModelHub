#Requires -Version 7.2
param(
    [Parameter(Mandatory)][string]$SourcePath,
    [ValidateRange(1, 30)][int]$TimeoutSeconds = 8
)

$ErrorActionPreference = 'Stop'
$imageTag = 'eclipse-temurin:21-jdk-alpine'
$taskPath = Join-Path $PSScriptRoot '../benchmarks/coding/median-of-three-v1.json'
$task = Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
$sourceFile = Get-Item -LiteralPath $SourcePath
if ($sourceFile -isnot [System.IO.FileInfo] -or $sourceFile.Length -gt 16384) {
    throw 'Supply a Java source file of at most 16 KiB.'
}
$source = [System.IO.File]::ReadAllText($sourceFile.FullName)
if ([Text.Encoding]::UTF8.GetByteCount($source) -gt 16384) {
    throw 'The UTF-8 source exceeds 16 KiB.'
}
$docker = (Get-Command docker -CommandType Application | Select-Object -First 1).Source
$image = & $docker image inspect --format '{{.Id}}' $imageTag
if ($LASTEXITCODE -ne 0) { throw "Pull $imageTag before running the pilot." }

function Invoke-IsolatedCase($case) {
    $container = & $docker create --pull never --interactive --network none --read-only --user '65534:65534' `
        --cap-drop ALL --security-opt no-new-privileges --cpus 1 --memory 256m --memory-swap 256m `
        --pids-limit 64 --log-driver none --tmpfs '/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777' `
        --entrypoint /bin/sh $image -c 'cat > /tmp/Main.java && exec java -Xmx96m -XX:ActiveProcessorCount=1 -XX:-UsePerfData /tmp/Main.java "$@"' pilot @($case.args)
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the evaluation container.' }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo.FileName = $docker
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in @('start', '--attach', '--interactive', $container)) {
        $process.StartInfo.ArgumentList.Add($argument)
    }

    $started = $false
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Could not start the Docker client.' }
        $stdout = [char[]]::new(4097)
        $stderr = [char[]]::new(4097)
        $readOut = $process.StandardOutput.ReadBlockAsync($stdout, 0, $stdout.Length)
        $readErr = $process.StandardError.ReadBlockAsync($stderr, 0, $stderr.Length)
        $process.StandardInput.AutoFlush = $true
        $write = $process.StandardInput.WriteAsync($source)
        $inputClosed = $false
        $outcome = 'TIMEOUT'
        while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            if (-not $inputClosed -and $write.IsCompleted) {
                $null = $write.GetAwaiter().GetResult()
                $process.StandardInput.Close()
                $inputClosed = $true
            }
            if (($readOut.IsCompleted -and $readOut.GetAwaiter().GetResult() -eq $stdout.Length) -or
                ($readErr.IsCompleted -and $readErr.GetAwaiter().GetResult() -eq $stderr.Length)) {
                $outcome = 'OUTPUT_LIMIT'
                break
            }
            if ($process.HasExited -and $readOut.IsCompleted -and $readErr.IsCompleted) {
                $state = (& $docker inspect --format '{{json .State}}' $container) | ConvertFrom-Json
                if ($LASTEXITCODE -ne 0 -or $state.Status -ne 'exited' -or $state.Error) {
                    throw 'Docker did not complete the evaluation process.'
                }
                $actual = [string]::new($stdout, 0, $readOut.GetAwaiter().GetResult())
                $outcome = if ($state.ExitCode -ne 0) { 'EXECUTION_ERROR' }
                    elseif ($actual -ceq ($case.expected + "`n") -or $actual -ceq ($case.expected + "`r`n")) { 'PASS' }
                    else { 'WRONG_ANSWER' }
                break
            }
            Start-Sleep -Milliseconds 20
        }
        return [ordered]@{id = $case.id; outcome = $outcome; durationMs = $timer.ElapsedMilliseconds}
    } finally {
        try {
            if ($started -and -not $process.HasExited) { $process.Kill() }
        } finally {
            $process.Dispose()
            & $docker rm --force $container | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Could not remove evaluation container $container." }
        }
    }
}

$results = @($task.cases | ForEach-Object { Invoke-IsolatedCase $_ })
[ordered]@{
    taskId = $task.id
    taskVersion = $task.version
    taskSha256 = (Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash
    sourceSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($source)))
    imageId = $image
    timeoutSeconds = $TimeoutSeconds
    results = $results
} | ConvertTo-Json -Depth 5
