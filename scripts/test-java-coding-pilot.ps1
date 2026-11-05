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
var status = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/status"));
if (!status.lines().anyMatch(line -> line.matches("Uid:\\s+65534\\s+65534\\s+65534\\s+65534"))
    || !status.lines().anyMatch(line -> line.matches("CapEff:\\s+0+"))
    || !status.lines().anyMatch(line -> line.matches("NoNewPrivs:\\s+1"))) {
    throw new IllegalStateException("unexpected process privileges");
}
var mounts = java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/mounts"));
if (mounts.stream().noneMatch(line -> line.matches("\\S+ / \\S+ ro[, ].*"))
    || mounts.stream().noneMatch(line -> line.contains(" /tmp tmpfs ") && line.contains("noexec")
            && line.contains("nosuid") && line.contains("nodev"))) {
    throw new IllegalStateException("unexpected filesystem permissions");
}
var cgroup = java.nio.file.Path.of("/sys/fs/cgroup");
if (java.nio.file.Files.exists(cgroup.resolve("cgroup.controllers"))) {
    var cpu = java.nio.file.Files.readString(cgroup.resolve("cpu.max")).strip().split("\\s+");
    if (!cpu[0].equals(cpu[1])
        || !java.nio.file.Files.readString(cgroup.resolve("memory.max")).strip().equals("268435456")
        || !java.nio.file.Files.readString(cgroup.resolve("pids.max")).strip().equals("64")) {
        throw new IllegalStateException("unexpected resource limits");
    }
}
int[] values = java.util.Arrays.stream(args).mapToInt(Integer::parseInt).sorted().toArray();
System.out.println(values[1]);
'@ 'PASS'
    Assert-Outcome 'System.out.println(0);' 'WRONG_ANSWER'
    Assert-Outcome 'int broken = ;' 'COMPILATION_ERROR'
    Assert-Outcome 'throw new IllegalStateException();' 'EXECUTION_ERROR'
    Assert-Outcome 'System.exit(65);' 'EXECUTION_ERROR'
    Assert-Outcome 'while (true) Thread.sleep(100);' 'TIMEOUT' 2
    Assert-Outcome 'while (true) System.out.println("x".repeat(2048));' 'OUTPUT_LIMIT'
} finally {
    if (Test-Path -LiteralPath $sourcePath) { Remove-Item -LiteralPath $sourcePath }
    Remove-Item -LiteralPath $directory
}
