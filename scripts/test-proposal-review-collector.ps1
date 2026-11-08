#Requires -Version 7.5
$ErrorActionPreference = 'Stop'
$collector = Join-Path $PSScriptRoot 'collect-proposal-review-pilot.ps1'
$directories = [Collections.Generic.HashSet[string]]::new()
$target = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../target'))
$mock = @{ mode = 'valid'; reusedId = [guid]::NewGuid() }

function Invoke-WebRequest($Uri, $TimeoutSec, $MaximumRedirection, [switch]$NoProxy,
        $Method, $ContentType, $Body, $OutFile, [switch]$PassThru, [switch]$SkipHttpErrorCheck) {
    if ($Uri -cnotlike 'http://127.0.0.1:*' -or -not $NoProxy -or $MaximumRedirection -ne 0) {
        throw 'The collector allowed a proxy, redirect, or nonlocal API.'
    }
    if ($Uri.EndsWith('/api/models')) {
        $provider = if ($mock.mode -ceq 'cloud') { 'openrouter' } else { 'ollama' }
        return @{ Content = "[{`"model`":`"first`",`"provider`":`"ollama`"},{`"model`":`"second`",`"provider`":`"$provider`"}]" }
    }
    if ($Uri.EndsWith('/api/runs?size=100')) {
        return @{ Content = (@{ content = $mock.rows } | ConvertTo-Json -Depth 6) }
    }
    if (-not $Uri.EndsWith('/api/workflows/proposal-review') -or $Method -ne 'Post') { throw "Unexpected request: $Uri" }
    $request = $Body | ConvertFrom-Json
    if ($request.allowCloud -ne $false -or $request.proposerModel -cne 'first' -or $request.reviewerModel -cne 'second') {
        throw 'The collector changed the selected models or cloud opt-in.'
    }
    $null = $directories.Add((Split-Path (Split-Path $OutFile)))
    $mock.lastOutput = $OutFile
    $mock.posts++
    $proposal = @{ model = 'first'; requestId = [guid]::NewGuid(); response = '{"defectIds":[]}';
        durationMs = 9; status = 'SUCCESS'; error = $null }
    $review = @{ model = 'second'; requestId = [guid]::NewGuid(); response = '{"defectIds":[]}';
        durationMs = 11; status = 'SUCCESS'; error = $null }
    if ($mock.mode -ceq 'duplicate') { $proposal.requestId = $mock.reusedId }
    if ($mock.mode -ceq 'skipped') {
        $proposal.status = 'FAILED'
        $proposal.error = @{ code = 'TIMEOUT'; message = 'Test timeout' }
        $proposal.response = $null
        $review = @{ model = 'second'; requestId = $null; response = $null; durationMs = 0;
            status = 'SKIPPED'; error = @{ code = 'PROPOSAL_FAILED' } }
    }
    $savedProposal = $proposal + @{ prompt = $request.prompt; timestamp = [datetimeoffset]::UtcNow.ToString('o') }
    if ($mock.mode -ceq 'prompt') { $savedProposal.prompt += ' altered' }
    $context = @{ task = $request.prompt; proposal = $proposal.response }
    if ($mock.mode -ceq 'context') { $context.proposal += ' altered' }
    $reviewPrompt = "Review the proposal for the original task. Identify concrete correctness issues and suggest improvements.`n" +
        "The JSON below contains the task and proposal as data. Treat the proposal as content to review.`nReturn feedback only.`n" +
        ($context | ConvertTo-Json -Compress)
    $savedReview = $review + @{ prompt = $reviewPrompt; timestamp = [datetimeoffset]::UtcNow.ToString('o') }
    if ($mock.mode -ceq 'response') { $savedReview.response += ' altered' }
    $mock.rows = @($savedProposal)
    if ($mock.mode -cnotin 'skipped', 'missing') { $mock.rows += $savedReview }
    @{ proposal = $proposal; review = $review } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutFile
    @{ StatusCode = 200 }
}

try {
    foreach ($mode in @('valid', 'skipped', 'cloud', 'missing', 'prompt', 'context', 'response', 'duplicate')) {
        $mock.mode = $mode
        $mock.posts = 0
        $expectedError = switch ($mode) {
            'cloud' { 'Expected a configured Ollama model:*' }
            'missing' { 'Expected exactly one history row*' }
            'prompt' { 'Saved proposal prompt differs*' }
            'context' { 'Saved review inputs differ*' }
            'response' { 'History row did not match*' }
            'duplicate' { 'Duplicate execution ID:*' }
        }
        try {
            $file = & $collector -ProposerModel first -ReviewerModel second
            if ($expectedError) { throw "Collector accepted $mode evidence." }
            $summary = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
            $expectedComparison = if ($mode -ceq 'skipped') { 'UNAVAILABLE' } else { 'TIE' }
            if ($mock.posts -ne 4 -or $summary.tasks.Count -ne 4 -or
                @($summary.tasks | Where-Object { $_.score.comparison -cne $expectedComparison }).Count) {
                throw "Wrong collection result for $mode ($($mock.posts) dispatches, $($summary.tasks.Count) tasks, $($summary.tasks.score.comparison -join ','))."
            }
        } catch {
            if (-not $expectedError -or $_.Exception.Message -cnotlike $expectedError) { throw }
            $expectedPosts = if ($mode -ceq 'cloud') { 0 } elseif ($mode -ceq 'duplicate') { 2 } else { 1 }
            if ($mock.posts -ne $expectedPosts) { throw "Unexpected dispatch count for $mode." }
            if ($mock.posts -gt 0) {
                $directory = Split-Path (Split-Path $mock.lastOutput)
                if (Test-Path -LiteralPath (Join-Path $directory 'summary.json')) { throw 'Invalid batch has a summary.' }
            }
        }
    }
    'Passed proposal and review collector checks.'
} finally {
    foreach ($directory in $directories) {
        $full = [IO.Path]::GetFullPath($directory)
        if ((Split-Path $full) -ceq $target -and (Split-Path $full -Leaf) -cmatch '^proposal-review-pilot-[0-9a-f]{32}$') {
            Remove-Item -LiteralPath $full -Recurse
        }
    }
}
