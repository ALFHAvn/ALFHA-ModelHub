#Requires -Version 7.2
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run-sql-window-pilot.ps1'
$source = Join-Path $PSScriptRoot "../target/sql-window-test-$([guid]::NewGuid()).sql"
New-Item -ItemType Directory -Force (Split-Path $source) | Out-Null

function Assert-Query([string]$query, [string]$expected) {
    Set-Content -LiteralPath $source -Encoding utf8NoBOM -Value $query
    $report = & $runner -SourcePath $source | ConvertFrom-Json
    $actual = $report.results.outcome -join ','
    if ($actual -cne $expected) { throw "Expected $expected. Observed $actual." }
    Write-Output "Verified $actual."
}

$queryTemplate = @'
WITH ranked AS (
    SELECT team, sale_id, amount, {0} OVER (PARTITION BY team ORDER BY amount DESC{1}) AS position
    FROM sales
)
SELECT team, sale_id, amount FROM ranked WHERE position <= 2
ORDER BY team, amount DESC, sale_id;
'@
try {
    Assert-Query ($queryTemplate -f 'row_number()', ', sale_id') 'PASS,PASS'
    Assert-Query ($queryTemplate -f 'row_number()', '') 'WRONG_ANSWER,WRONG_ANSWER'
    Assert-Query ($queryTemplate -f 'rank()', '') 'WRONG_ANSWER,WRONG_ANSWER'
    Assert-Query 'WITH changed AS (DELETE FROM sales RETURNING *) SELECT * FROM changed' 'QUERY_ERROR,QUERY_ERROR'
    Assert-Query 'SELECT pg_read_file(''/tmp/db/PG_VERSION'')' 'QUERY_ERROR,QUERY_ERROR'
} finally {
    if (Test-Path -LiteralPath $source) { Remove-Item -LiteralPath $source }
}
exit 0
