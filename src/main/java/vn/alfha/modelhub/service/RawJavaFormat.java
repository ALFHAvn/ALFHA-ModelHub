package vn.alfha.modelhub.service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

enum RawJavaFormat {
    STANDARD("java-coding-v1"), BOUNDARY("java-coding-raw-v1"), ENVELOPE("java-coding-raw-v2");

    private static final Pattern INTRO = Pattern.compile(
            "(?iuU)^(?:(?:Certainly|Sure|Of course)[!,:]|Here(?:['’]s|[ \\t]+(?:is|are))|Below[ \\t]+(?:is|are))(?:\\s|$)");
    private static final Pattern FENCE = Pattern.compile("^[ \\t]*(?:`{3,}|~{3,})");
    private static final Pattern OPEN = Pattern.compile("(?i)^[ \\t]*(?<marker>`{3,}|~{3,})[ \\t]*(?:(?<label>java)[ \\t]*)?$");
    private static final Pattern MAIN = Pattern.compile("^(?:public[ \\t]+)?(?:final[ \\t]+)?class[ \\t]+Main[ \\t]*(?:\\n[ \\t]*)?\\{");

    final String scoringVersion;

    RawJavaFormat(String scoringVersion) { this.scoringVersion = scoringVersion; }

    boolean rejects(String source) {
        if (this == STANDARD) return false;
        var lines = source.strip().lines().toList();
        return startsFence(lines.getFirst()) || startsFence(lines.getLast().stripLeading())
                || (this == ENVELOPE && hasProseEnvelope(lines));
    }

    private static boolean startsFence(String line) {
        return line.startsWith("```") || line.startsWith("~~~");
    }

    private static boolean hasProseEnvelope(List<String> lines) {
        String intro = lines.getFirst().stripTrailing();
        if (intro.length() > 4 && intro.startsWith("**") && intro.endsWith("**"))
            intro = intro.substring(2, intro.length() - 2).strip();
        if (!INTRO.matcher(intro).lookingAt()) return false;

        int[] fences = IntStream.range(0, lines.size()).filter(i -> FENCE.matcher(lines.get(i)).lookingAt()).limit(3).toArray();
        if (fences.length != 2) return false;
        int start = fences[0];
        int end = fences[1];
        var opening = OPEN.matcher(lines.get(start));
        if (!opening.matches() || !lines.get(end).strip().equals(opening.group("marker"))
                || end <= start + 1 || end == lines.size() - 1) return false;

        String inside = String.join("\n", lines.subList(start + 1, end)).stripLeading();
        return !inside.isBlank() && (opening.group("label") != null || MAIN.matcher(inside).lookingAt());
    }
}
