package ru.studyassistant.backend.material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Убирает колонтитулы, номера страниц и лишние переносы из текста PDF. */
public class PdfTextCleaner {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern SPACES = Pattern.compile("\\h+");
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,5}");
    private static final Pattern WORD = Pattern.compile("\\p{L}+(?:-\\p{L}+)*");
    private static final Pattern WRAPPED_WORD = Pattern.compile("(?<!\\p{L})(\\p{L}++)-\\n(\\p{Ll}\\p{L}*)");
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-–—•*]|\\d+[.)])\\h");
    private static final Pattern TABLE_ROW = Pattern.compile("\\S(?: {2,}|\\t+)\\S");

    /**
     * Очищает извлеченный текст, сохраняя порядок страниц и абзацев.
     *
     * @param pages текст по страницам, включая пустые страницы
     * @return одна строка для передачи в LLM
     */
    public String clean(List<String> pages) {
        var lines = pages.stream().map(page -> new ArrayList<>(LINE_BREAK // замена разных пробелов на обычный
                .splitAsStream(page.replace('\u00A0', ' ').replace('\u202F', ' ').strip())
                .map(String::strip).toList())).toList();

        removePageNumbers(lines);
        removeRepeatedEdges(lines);

        Set<String> words = lines.stream().flatMap(List::stream)
                .flatMap(line -> WORD.matcher(line).results())
                .map(match -> match.group().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return lines.stream().map(page -> joinLines(page, words))
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }
    // если на страницах в конце номер то удаляет их
    private static void removePageNumbers(List<ArrayList<String>> pages) {
        var footers = countEdges(pages, false);
        int[] numbers = new int[pages.size()];
        int[] positions = new int[pages.size()];

        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            int last = page.size() - 1;
            if (last > 0 && repeats(page.get(last), footers, pages.size())) {
                last--;
                while (last > 0 && page.get(last).isEmpty()) {
                    last--;
                }
            }
            if (last > 0 && NUMBER.matcher(page.get(last)).matches()) {
                positions[i] = last;
                numbers[i] = Integer.parseInt(page.get(last));
            }
        }

        // Номера должны идти подряд на трёх или более соседних страницах.
        for (int start = 0; start < pages.size(); ) {
            int end = start + 1;
            while (end < pages.size() && numbers[end - 1] > 0 && numbers[end] == numbers[end - 1] + 1) {
                end++;
            }
            if (end - start >= 3) {
                for (int i = start; i < end; i++) {
                    var page = pages.get(i);
                    page.remove(positions[i]);
                    while (!page.isEmpty() && page.getLast().isEmpty()) {
                        page.removeLast();
                    }
                }
            }
            start = end;
        }
    }

    // удаляет повторяющиеся начала и концы страниц - колонтитулы
    private static void removeRepeatedEdges(List<ArrayList<String>> pages) {
        var headers = countEdges(pages, true);
        var footers = countEdges(pages, false);
        for (var page : pages) {
            if (page.size() < 2) {
                continue;
            }
            boolean header = repeats(page.getFirst(), headers, pages.size());
            boolean footer = repeats(page.getLast(), footers, pages.size());

            // Если между двумя кандидатами нет основного текста, сохраняем обе строки.
            if (header && footer && page.subList(1, page.size() - 1).stream().allMatch(String::isEmpty)) {
                continue;
            }
            if (header) {
                page.removeFirst();
            }
            if (footer) {
                page.removeLast();
            }
        }
    }

    // считает сколько раз строка встречается на краю страницы
    private static Map<String, Integer> countEdges(List<ArrayList<String>> pages, boolean top) {
        var counts = new HashMap<String, Integer>();
        for (var page : pages) {
            if (!page.isEmpty()) {
                String line = top ? page.getFirst() : page.getLast();
                counts.merge(SPACES.matcher(line).replaceAll(" "), 1, Integer::sum);
            }
        }
        return counts;
    }

    // проверка - более половины страниц и не менее двух раз
    private static boolean repeats(String line, Map<String, Integer> counts, int pageCount) {
        int count = counts.getOrDefault(SPACES.matcher(line).replaceAll(" "), 0);
        return count >= 2 && count > pageCount / 2 && !NUMBER.matcher(line).matches();
    }

    // Соединяем строки абзаца пробелами, сохраняя пустые строки, пункты списков и таблицы.
    // Мягкий перенос удаляем. Обычный дефис убираем, если в документе есть слитное написание слова.
    private static String joinLines(List<String> page, Set<String> words) {
        var text = new StringBuilder();
        String previous = "";
        boolean previousTable = false;

        for (int i = 0; i < page.size(); i++) {
            String line = page.get(i);
            boolean table = TABLE_ROW.matcher(line).find()
                    && ((i > 0 && TABLE_ROW.matcher(page.get(i - 1)).find())
                    || (i + 1 < page.size() && TABLE_ROW.matcher(page.get(i + 1)).find()));

            if (line.isEmpty()) {
                if (!previous.isEmpty()) {
                    text.append("\n\n");
                }
            } else {
                if (!previous.isEmpty()) {
                    if (table || previousTable || LIST_ITEM.matcher(line).find()) {
                        text.append('\n');
                    } else if (previous.endsWith("\u00AD")) {
                        text.setLength(text.length() - 1);
                    } else if (!previous.endsWith("-") || !joinWord(previous, line, words, text)) {
                        text.append(' ');
                    }
                }
                text.append(table ? line : SPACES.matcher(line).replaceAll(" "));
            }
            previous = line;
            previousTable = table;
        }
        return text.toString().replace("\u00AD", "").strip();
    }

    private static boolean joinWord(String previous, String line, Set<String> words, StringBuilder text) {
        var wrap = WRAPPED_WORD.matcher(previous + "\n" + line);
        if (!wrap.find()) {
            return false;
        }
        String left = wrap.group(1).toLowerCase(Locale.ROOT);
        String right = wrap.group(2).toLowerCase(Locale.ROOT);

        // Обычный дефис удаляем, если в документе есть слитное написание слова.
        if (words.contains(left + right) && !words.contains(left + "-" + right)) {
            text.setLength(text.length() - 1);
        }
        return true;
    }
}
