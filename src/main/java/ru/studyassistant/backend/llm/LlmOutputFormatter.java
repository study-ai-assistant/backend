package ru.studyassistant.backend.llm;

import java.util.regex.Pattern;

/** Удаляет оформление Markdown, сохраняя текст и математические операции. */
public final class LlmOutputFormatter {
    private static final Pattern BOLD = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])\\*\\*(?=\\S)([^\\n]+?)(?<=\\S)\\*\\*(?![\\p{L}\\p{N}_])");
    private static final Pattern HEADINGS = Pattern.compile("(?m)^\\h{0,3}#{1,6}\\h+");
    private LlmOutputFormatter() { }
    public static String format(String text) {
        var result = new StringBuilder();
        boolean code = false;
        for (String line : text.lines().toList()) {
            if (line.stripLeading().startsWith("```")) { code = !code; continue; }
            if (!code) {
                line = BOLD.matcher(line).replaceAll("$1");
                line = HEADINGS.matcher(line).replaceAll("");
            }
            result.append(line).append('\n');
        }
        return result.toString().strip();
    }
}
