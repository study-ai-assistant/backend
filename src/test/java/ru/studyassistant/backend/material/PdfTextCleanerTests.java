package ru.studyassistant.backend.material;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Проверяет очистку текста PDF на примерах до и после обработки. */
class PdfTextCleanerTests {
    private final PdfTextCleaner cleaner = new PdfTextCleaner();

    @Test
    void joinsWrappedLinesAndPreservesParagraphs() {
        String input = "Клетка\u00A0  получает\r\nэнергию.\r\n\r\nВ опыте участвовало 10\u202F000 клеток.";

        assertEquals("Клетка получает энергию.\n\nВ опыте участвовало 10 000 клеток.",
                cleaner.clean(List.of(input)));
    }

    @Test
    void removesSoftHyphenAtLineBreakAndInsideWord() {
        String input = "Длин\u00AD\nная инфор\u00ADмация.";

        assertEquals("Длинная информация.", cleaner.clean(List.of(input)));
    }

    @Test
    void removesHyphenWhenJoinedWordOccursInDocument() {
        String input = "Здесь информация.\nДругая информа-\nция.";

        assertEquals("Здесь информация. Другая информация.", cleaner.clean(List.of(input)));
    }

    @Test
    void preservesHyphenInCompoundWord() {
        String input = "Это научно-\nтехнический текст.";

        assertEquals("Это научно-технический текст.", cleaner.clean(List.of(input)));
    }

    @Test
    void preservesHyphenWhenJoinedSpellingIsUnknown() {
        assertEquals("Новая информа-ция.", cleaner.clean(List.of("Новая информа-\nция.")));
    }

    @Test
    void preservesListAndTableLayout() {
        String input = "Пункты:\n1. Первый\n2. Второй\n\nПланета  Спутники\nЗемля    1";

        assertEquals(input, cleaner.clean(List.of(input)));
    }

    @Test
    void removesRepeatedHeadersAndFootersFromPageEdges() {
        var pages = List.of(
                "Конспект\nКлетка получает энергию.\nУниверситет",
                "Конспект\nДалее рассмотрим клеточное дыхание.\nУниверситет",
                "Конспект\nВ тексте есть слово «Конспект».\nУниверситет"
        );

        assertEquals("Клетка получает энергию.\n\nДалее рассмотрим клеточное дыхание.\n\n"
                + "В тексте есть слово «Конспект».", cleaner.clean(pages));
    }

    @Test
    void removesConsecutiveNumbersAtBottomOfPages() {
        var pages = List.of("Текст 1\n12", "Текст 2\n13", "Текст 3\n14");

        assertEquals("Текст 1\n\nТекст 2\n\nТекст 3", cleaner.clean(pages));
    }

    @Test
    void removesConsecutivePageNumbersAboveRepeatedFooter() {
        var pages = List.of(
                "Текст 1\n12\nУниверситет",
                "Текст 2\n13\nУниверситет",
                "Текст 3\n14\nУниверситет"
        );

        assertEquals("Текст 1\n\nТекст 2\n\nТекст 3", cleaner.clean(pages));
    }

    @Test
    void preservesNumbersWhenSequenceIsTooShort() {
        var pages = List.of("Задание 1\n12", "Задание 2\n13");

        assertEquals("Задание 1 12\n\nЗадание 2 13", cleaner.clean(pages));
    }

    @Test
    void returnsEmptyTextForBlankPages() {
        assertEquals("", cleaner.clean(List.of("", " \t\n")));
    }
}
