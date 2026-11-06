#Requires -Version 7.2
param([Parameter(Mandatory)][string]$SourcePath)

$ErrorActionPreference = 'Stop'
$sourceFile = Get-Item -LiteralPath $SourcePath
if ($sourceFile -isnot [System.IO.FileInfo] -or $sourceFile.Length -gt 16384) {
    throw 'Supply a SQL source file of at most 16 KiB.'
}
$sourceBytes = [IO.File]::ReadAllBytes($sourceFile.FullName)
$query = [Text.UTF8Encoding]::new($false, $true).GetString($sourceBytes).Trim().TrimStart([char]0xfeff).Trim()
if ($query.EndsWith(';')) { $query = $query.Substring(0, $query.Length - 1) }
if ([string]::IsNullOrWhiteSpace($query) -or $query.Contains([char]0)) { throw 'Supply a SQL query.' }
$queryBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($query))
$taskPath = Join-Path $PSScriptRoot '../benchmarks/held-out/sql-window-v1.json'
$taskJson = (Get-Content -LiteralPath $taskPath -Raw).Replace("`r`n", "`n")
$task = $taskJson | ConvertFrom-Json
$imageTag = 'postgres:17-alpine'
$docker = (Get-Command docker -CommandType Application | Select-Object -First 1).Source
$image = & $docker image inspect --format '{{.Id}}' $imageTag
if ($LASTEXITCODE -ne 0) { throw "Pull $imageTag before running the SQL pilot." }

$bootstrap = @'
set -eu
set -o pipefail
export PGHOST=/tmp PGDATABASE=postgres
initdb -D /tmp/db --no-locale --encoding=UTF8 --auth-local=trust --auth-host=reject >/dev/null 2>&1 || exit 67
pg_ctl -D /tmp/db -l /tmp/postgres.log -o "-c listen_addresses='' -c unix_socket_directories=/tmp -c shared_buffers=16MB" -w start >/dev/null || exit 67
psql -X -U postgres -q -v ON_ERROR_STOP=1 -v fixture="$1" >/dev/null <<'SQL' || exit 67
CREATE ROLE candidate LOGIN;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMP ON DATABASE postgres FROM PUBLIC;
CREATE TABLE sales(team text, sale_id integer, amount integer);
INSERT INTO sales VALUES :fixture;
GRANT SELECT ON sales TO candidate;
CREATE FUNCTION grade_submission(submission text) RETURNS text
LANGUAGE plpgsql STABLE SECURITY INVOKER AS $body$
DECLARE result text;
BEGIN
  EXECUTE 'SELECT array_to_json(ARRAY(SELECT row_to_json(result_row) FROM (' ||
      submission || E'\n) AS result_row))::text' INTO result;
  RETURN result;
END
$body$;
REVOKE ALL ON FUNCTION grade_submission(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION grade_submission(text) TO candidate;
SQL
query=$(printf %s "$2" | base64 -d) || exit 67
export PGOPTIONS='-c statement_timeout=2000 -c default_transaction_read_only=on -c work_mem=1MB'
psql -X -U candidate -qAt -v ON_ERROR_STOP=1 -v submission="$query" 2>/dev/null <<'SQL' | head -c 4097
SELECT grade_submission(:'submission');
SQL
'@

$results = @(foreach ($case in $task.cases) {
    $container = "modelhub-sql-$([guid]::NewGuid().ToString('N'))"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $output = & $docker run --rm --name $container --pull never --network none --read-only --user postgres `
        --cap-drop ALL --security-opt no-new-privileges --cpus 1 --memory 256m --memory-swap 256m `
        --pids-limit 64 --log-driver none --tmpfs '/tmp:rw,noexec,nosuid,nodev,size=96m,mode=1777' `
        --tmpfs '/var/lib/postgresql/data:ro,noexec,nosuid,nodev,size=1m' `
        --entrypoint /bin/busybox $image timeout 25 /bin/sh -c $bootstrap pilot $case.rows $queryBase64
    $exitCode = $LASTEXITCODE
    if ($exitCode -notin @(0, 3, 124, 141, 143)) {
        throw "SQL sandbox could not complete case $($case.id) (exit $exitCode)."
    }
    $outcome = if ($exitCode -in @(124, 143)) { 'TIMEOUT' }
        elseif (($output -join "`n").Length -gt 4096) { 'OUTPUT_LIMIT' }
        elseif ($exitCode -ne 0) { 'QUERY_ERROR' }
        elseif (($output -join "`n") -ceq $case.expected) { 'PASS' }
        else { 'WRONG_ANSWER' }
    [ordered]@{ id = $case.id; outcome = $outcome; durationMs = $timer.ElapsedMilliseconds }
})

[ordered]@{
    taskId = $task.id
    taskVersion = $task.version
    taskSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($taskJson)))
    sourceSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($sourceBytes))
    imageId = $image
    results = $results
} | ConvertTo-Json -Depth 5
