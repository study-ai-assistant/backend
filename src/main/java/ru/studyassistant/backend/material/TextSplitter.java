package ru.studyassistant.backend.material;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Проверяет размер материала и разбивает длинный текст на части для LLM. */
@Component
public class TextSplitter {

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\R\\h*\\R(?:\\h*\\R)*");

    private final TextSplittingProperties properties;

    public TextSplitter(TextSplittingProperties properties) {
        this.properties = properties;
    }

    public List<String> split(String text) {
        if (text.codePointCount(0, text.length()) > properties.getMaxCharacters()) {
            throw new IllegalArgumentException("Материал превышает лимит символов.");
        }

        int tokens = (text.codePoints().map(TextSplitter::utf8Bytes).sum() + 3) / 4;
        if (tokens > properties.getMaxEstimatedTokens()) {
            throw new IllegalArgumentException("Материал превышает лимит токенов.");
        }
        if (tokens <= properties.getSingleRequestTextTokens()) {
            return List.of(text);
        }

        var sentences = BreakIterator.getSentenceInstance(Locale.forLanguageTag("ru"));
        sentences.setText(text);

        var parts = new ArrayList<String>();
        int start = 0;

        while (start < text.length()) { // Разбиваем на части проверяя чтобы они попадали в лимит
            if (parts.size() == properties.getMaxParts()) {
                throw new IllegalArgumentException("Материал превышает лимит количества частей.");
            }

            int limit = findPartLimit(text, start);
            int end = limit == text.length() ? limit : chooseBoundary(text, start, limit, sentences);

            parts.add(text.substring(start, end));
            start = end;
        }

        return parts;
    }

    private int findPartLimit(String text, int start) {
        long remainingBytes = properties.getPartTextTokens() * 4L;
        int end = start;

        while (end < text.length()) { // находим лимит оставшейся части
            int character = text.codePointAt(end);
            remainingBytes -= utf8Bytes(character);
            if (remainingBytes < 0) {
                break;
            }
            end += Character.charCount(character);
        }

        return end;
    }

    private static int chooseBoundary(String text, int start, int limit, BreakIterator sentences) {
        // Берём последний целый абзац который помещается в часть.
        var paragraphs = PARAGRAPH_BREAK.matcher(text).region(start, text.length());
        int paragraphEnd = start;

        while (paragraphs.find() && paragraphs.start() <= limit) {
            int boundary = paragraphs.end() <= limit ? paragraphs.end() : paragraphs.start();
            if (boundary > paragraphEnd) {
                paragraphEnd = boundary;
            }
        }

        if (paragraphEnd > start) {
            return paragraphEnd;
        }

        // Одно слово может быть длиннее целой части, например длинная ссылка.
        int sentenceEnd = sentences.isBoundary(limit) ? limit : sentences.preceding(limit);
        if (sentenceEnd > start) {
            return sentenceEnd;
        }

        for (int end = limit; end > start; ) {
            int character = text.codePointBefore(end);
            if (Character.isWhitespace(character)) {
                return end;
            }
            end -= Character.charCount(character);
        }

        // Одно слово может быть длиннее целой части, например длинная ссылка.
        return limit;
    }

    /** Четыре байта UTF-8 на токен используем как грубую оценку. */
    private static int utf8Bytes(int character) {
        if (character <= 0x7F) {
            return 1;
        }
        if (character <= 0x7FF) {
            return 2;
        }
        if (character <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
