#Requires -Version 7.2
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run-java-coding-pilot.ps1'
$directory = Join-Path $PSScriptRoot ('../target/coding-pilot-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $directory | Out-Null
$sourcePath = Join-Path $directory 'Main.java'

function Assert-Outcome([string]$body, [string]$expected, [int]$timeout = 8) {
    Set-Content -LiteralPath $sourcePath -Encoding utf8NoBOM -Value @"
public class Main {
    public static void main(String[] args) throws Exception {
        $body
    }
}
"@
    $report = & $runner -SourcePath $sourcePath -TimeoutSeconds $timeout | ConvertFrom-Json
    if ($report.results.Count -ne 6 -or @($report.results | Where-Object outcome -ne $expected).Count) {
        throw "Expected $expected for every case. Observed: $($report.results.outcome -join ', ')"
    }
    Write-Output "Verified $expected on six cases."
}

try {
    Assert-Outcome @'
if (java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"))
    || java.net.NetworkInterface.networkInterfaces().anyMatch(n -> !n.getName().equals("lo"))) {
    throw new IllegalStateException("unexpected container access");
}
int[] values = java.util.Arrays.stream(args).mapToInt(Integer::parseInt).sorted().toArray();
System.out.println(values[1]);
'@ 'PASS'
    Assert-Outcome 'System.out.println(0);' 'WRONG_ANSWER'
    Assert-Outcome 'throw new IllegalStateException();' 'EXECUTION_ERROR'
    Assert-Outcome 'while (true) Thread.sleep(100);' 'TIMEOUT' 2
    Assert-Outcome 'while (true) System.out.println("x".repeat(2048));' 'OUTPUT_LIMIT'
} finally {
    if (Test-Path -LiteralPath $sourcePath) { Remove-Item -LiteralPath $sourcePath }
    Remove-Item -LiteralPath $directory
}
