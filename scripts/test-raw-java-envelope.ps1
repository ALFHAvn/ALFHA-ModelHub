#Requires -Version 7.5
param([switch]$VerifyDocker)

$ErrorActionPreference = 'Stop'
$detector = Join-Path $PSScriptRoot 'check-raw-java-envelope.ps1'
$legitimate = @'
public class Main {
    static final String HELP = """
            Certainly! Here is an example.
            ```java
            System.out.println("example");
            ```
            End of example.
            """;

    public static void main(String[] args) {
        int[] values = java.util.Arrays.stream(args).mapToInt(Integer::parseInt).sorted().toArray();
        System.out.println(values[1]);
    }
}
'@
$fenced = '```java' + "`nclass Main {}`n" + '```'
$wrapped = "Certainly! Here is the Java source.`n$fenced`nExplanation follows."
$cases = @(
    @{ name = 'backtick wrapper'; text = $wrapped; expected = $true }
    @{ name = 'CRLF tilde wrapper'; text = "Here is the source:`r`n~~~JAVA`r`nclass Main {}`r`n~~~`r`nExplanation."; expected = $true }
    @{ name = 'bare CR wrapper'; text = $wrapped.Replace("`n", "`r"); expected = $true }
    @{ name = 'Java text block'; text = $legitimate; expected = $false }
    @{ name = 'Java comment'; text = "/*`n$wrapped`n*/`nclass Main {}"; expected = $false }
    @{ name = 'boundary only'; text = $fenced; expected = $false }
    @{ name = 'no trailing prose'; text = "Certainly! Java follows.`n$fenced`n "; expected = $false }
    @{ name = 'unknown introduction'; text = "Implementation:`n$fenced`nExplanation."; expected = $false }
    @{ name = 'two fenced blocks'; text = "$wrapped`n$fenced"; expected = $false }
    @{ name = 'mismatched fences'; text = $wrapped.Replace("`n" + '```' + "`n", "`n~~~`n"); expected = $false }
    @{ name = 'other language'; text = $wrapped.Replace('```java', '```sql'); expected = $false }
    @{ name = 'blank block'; text = $wrapped.Replace('class Main {}', ' '); expected = $false }
    @{ name = 'oversize response'; text = $wrapped + ('x' * 16384); expected = $false }
)
foreach ($case in $cases) {
    $actual = & $detector -Response $case.text
    if ($actual -ne $case.expected) { throw "Unexpected decision for $($case.name)." }
}
"Passed $($cases.Count) envelope classification cases."

if ($VerifyDocker) {
    $target = (New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot '../target')).FullName
    $source = Join-Path $target "text-block-fixture-$([guid]::NewGuid().ToString('N')).java"
    try {
        [IO.File]::WriteAllText($source, $legitimate, [Text.UTF8Encoding]::new($false))
        $report = & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $source | ConvertFrom-Json
        if ($report.results.Count -ne 6 -or @($report.results | Where-Object outcome -CNE 'PASS').Count) {
            throw 'The Java text block example did not pass the existing sandbox cases.'
        }
        'The Java text block example passed all six restricted Docker cases.'
    } finally {
        if (Test-Path -LiteralPath $source) { Remove-Item -LiteralPath $source }
    }
}
