#Requires -Version 7.5
param([switch]$VerifyDocker)

$ErrorActionPreference = 'Stop'
$detector = Join-Path $PSScriptRoot 'check-raw-java-envelope.ps1'
$median = @'
public class Main {
    public static void main(String[] args) {
        int[] values = java.util.Arrays.stream(args).mapToInt(Integer::parseInt).sorted().toArray();
        System.out.println(values[1]);
    }
}
'@
$helpField = @'
    static final String HELP = """
            Certainly! Here is an example.
            ```java
            System.out.println("example");
            ```
            End of example.
            """;
'@
$legitimate = $median.Replace('public class Main {', "public class Main {`n$helpField")
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

$javadoc = @'
/**
Certainly! Here is a Java example.
```java
System.out.println("example");
```
This example is for documentation.
*/
'@
$escapedComment = @'
\u002f\u002a
Sure, an example follows.
~~~java
class Example {}
~~~
End of example.
\u002a\u002f
'@
$markdownField = @'
    static final String MARKDOWN = """
            Here is a nested example:
            ````java
            String sample = \""";
            ```java
            ~~~
            ````
            """;
'@
$ordinaryString = '    static final String MARKDOWN = "Here is \"an example\": ```java\nclass Demo {}\n```";'
$lineComments = "// Certainly! An example follows.`n// ~~~java`n// class Example {}`n// ~~~`n" + '@SuppressWarnings("unused")'
$valid = @(
    @{ id = 'text-block'; source = $legitimate }
    @{ id = 'javadoc'; source = "$javadoc`n$median" }
    @{ id = 'unicode-comment-delimiters'; source = "$escapedComment`n$median" }
    @{ id = 'nested-text-block'; source = $median.Replace('public class Main {', "public class Main {`n$markdownField") }
    @{ id = 'ordinary-string'; source = $median.Replace('public class Main {', "public class Main {`n$ordinaryString") }
    @{ id = 'crlf-line-comments-annotation'; source = ("$lineComments`n$median" -replace '\r?\n', "`r`n") }
)
foreach ($case in $valid) {
    if (& $detector -Response $case.source) { throw "Flagged valid Java fixture $($case.id)." }
}
"No flags among $($valid.Count) valid Java fixtures."

$unlabeled = $fenced.Replace('```java', '```')
$longFence = '````java' + "`nclass Main {}`n" + '````'
$wrappers = @(
    @{ id = 'sure'; text = "Sure, here is the source.`n$fenced`nBuild it with Java 21."; detected = $true }
    @{ id = 'of-course'; text = "Of course: code follows.`n`n$fenced`n`nThis is the implementation."; detected = $true }
    @{ id = 'contraction'; text = "Here's the code.`n$fenced`nExplanation follows."; detected = $true }
    @{ id = 'indented-tilde'; text = "Here is the source.`n  ~~~ java`nclass Main {}`n  ~~~`nExplanation."; detected = $true }
    @{ id = 'blank-leading-lines'; text = "`r`n `r`n$wrapped"; detected = $true }
    @{ id = 'below-is'; text = "Below is the implementation.`n$fenced`nExplanation follows."; detected = $false }
    @{ id = 'bold-introduction'; text = "**Here is the code.**`n$fenced`nExplanation follows."; detected = $false }
    @{ id = 'unlabeled'; text = "Certainly! Code follows.`n$unlabeled`nExplanation follows."; detected = $false }
    @{ id = 'long-fence'; text = "Certainly! Code follows.`n$longFence`nExplanation follows."; detected = $false }
    @{ id = 'curly-apostrophe'; text = "Here$([char]0x2019)s the code.`n$fenced`nExplanation follows."; detected = $false }
)
$misses = @(foreach ($case in $wrappers) {
    $actual = & $detector -Response $case.text
    if ($actual -ne $case.detected) { throw "Prose-wrapper baseline changed for $($case.id)." }
    if (-not $actual) { $case.id }
})
"Detected $($wrappers.Count - $misses.Count)/$($wrappers.Count) synthetic wrappers. Misses $($misses -join ', ')."

if ($VerifyDocker) {
    $target = (New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot '../target')).FullName
    $source = Join-Path $target "text-block-fixture-$([guid]::NewGuid().ToString('N')).java"
    try {
        foreach ($case in $valid) {
            [IO.File]::WriteAllText($source, $case.source, [Text.UTF8Encoding]::new($false))
            $report = & "$PSScriptRoot/run-java-coding-pilot.ps1" -SourcePath $source | ConvertFrom-Json
            if ($report.results.Count -ne 6 -or @($report.results | Where-Object outcome -CNE 'PASS').Count) {
                throw "Java fixture $($case.id) did not pass the existing sandbox cases."
            }
        }
        "All $($valid.Count) Java fixtures passed all six restricted Docker cases each."
    } finally {
        if (Test-Path -LiteralPath $source) { Remove-Item -LiteralPath $source }
    }
}
