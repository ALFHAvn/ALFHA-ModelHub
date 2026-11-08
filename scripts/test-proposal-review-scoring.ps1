#Requires -Version 7.5
$ErrorActionPreference = 'Stop'
$scorer = Join-Path $PSScriptRoot 'score-proposal-review.ps1'
$directory = New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot "../target/proposal-review-test-$([guid]::NewGuid())")
$path = Join-Path $directory.FullName 'workflow.json'

function New-Step([string]$Model, [string]$Response) {
    @{ model = $Model; response = $Response; requestId = [guid]::NewGuid(); durationMs = 25; status = 'SUCCESS'; error = $null }
}

function Get-Result([string]$TaskId, $Proposal, $Review) {
    @{ proposal = $Proposal; review = $Review } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $path
    & $scorer -TaskId $TaskId -WorkflowPath $path | ConvertFrom-Json
}

try {
    $taskId = 'java-positive-sum'
    $prompt = & $scorer -TaskId $taskId
    if ($prompt -cnotmatch 'Candidate findings:' -or $prompt -cnotmatch 'defectIds' -or $prompt -cmatch 'expectedDefectIds') {
        throw 'Invalid generated prompt.'
    }
    $wrong = New-Step 'proposer' '{"defectIds":["A","C"]}'
    $correct = New-Step 'reviewer' '{"defectIds":["B","A"]}'
    $result = Get-Result $taskId $wrong $correct
    if ($result.comparison -cne 'IMPROVED' -or $result.proposal.score.outcome -cne 'WRONG_ANSWER' -or
        ($result.proposal.score.truePositives -join ',') -cne 'A' -or
        ($result.proposal.score.falsePositives -join ',') -cne 'C' -or
        ($result.proposal.score.missedDefects -join ',') -cne 'B' -or
        $result.review.score.outcome -cne 'PASS' -or
        $result.promptSha256 -ine (Get-FileHash -InputStream ([IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($prompt)))).Hash -or
        $result.workflowSha256 -ine (Get-FileHash -LiteralPath $path).Hash) {
        throw 'Exact-set scoring or defect accounting failed.'
    }

    foreach ($invalid in @('```json {"defectIds":[]} ```', '{"defectIds":[]} {}', '{"defectIds":["A","A"]}',
            '{"defectIds":[],"defectIds":[]}', '{"defectIds":[],"extra":1}', '{"defectIds":["Z"]}',
            '{"defectIds":["a"]}', '{"defectIds":[1]}', '{"defectIds":"A"}', '{"defectIds":["\ud800"]}',
            '2026-11-08T15:00:00Z', '[]', '')) {
        $result = Get-Result $taskId $correct (New-Step 'other' $invalid)
        if ($result.comparison -cne 'REGRESSED' -or $result.review.score.outcome -cne 'FORMAT_ERROR') {
            throw "Accepted malformed response: $invalid"
        }
    }

    foreach ($example in @(@{ id = 'java-prefix-clean'; response = '{"defectIds":[]}' },
            @{ id = 'sql-empty-category-clean'; response = '{"defectIds":[]}' },
            @{ id = 'sql-access-precedence'; response = '{"defectIds":["A"]}' })) {
        $result = Get-Result $example.id (New-Step 'first' $example.response) (New-Step 'second' $example.response)
        if ($result.comparison -cne 'TIE' -or $result.proposal.score.outcome -cne 'PASS' -or
            $result.review.score.outcome -cne 'PASS') { throw "Unexpected control result for $($example.id)" }
    }

    $failure = @{ model = 'reviewer'; requestId = [guid]::NewGuid(); response = $null; durationMs = 42;
        status = 'FAILED'; error = @{ code = 'TIMEOUT' } }
    $result = Get-Result $taskId $wrong $failure
    if ($result.comparison -cne 'UNAVAILABLE' -or $result.proposal.score.outcome -cne 'WRONG_ANSWER' -or
        $result.review.errorCode -cne 'TIMEOUT') { throw 'Provider failure was scored as an answer.' }

    $skipped = @{ model = 'reviewer'; requestId = $null; response = $null; durationMs = 0; status = 'SKIPPED';
        error = @{ code = 'PROPOSAL_FAILED' } }
    $failure.model = 'proposer'
    $failure.error.code = 'HISTORY_UNAVAILABLE'
    $failure.response = '{"defectIds":["A","B"]}'
    $result = Get-Result $taskId $failure $skipped
    if ($result.comparison -cne 'UNAVAILABLE' -or $result.proposal.score.outcome -cne 'UNAVAILABLE' -or
        $result.proposal.errorCode -cne 'HISTORY_UNAVAILABLE' -or $null -ne $result.review.requestId) {
        throw 'Storage failure or skipped step was scored as an answer.'
    }

    $correct.requestId = $wrong.requestId
    try {
        $null = Get-Result $taskId $wrong $correct
        throw 'Accepted duplicate execution IDs.'
    } catch {
        if ($_.Exception.Message -cne 'Invalid proposal and review pairing.') { throw }
    }
    'Passed offline proposal and review scoring checks.'
} finally {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    Remove-Item -LiteralPath $directory.FullName
}
