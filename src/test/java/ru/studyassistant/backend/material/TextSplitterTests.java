package ru.studyassistant.backend.material;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TextSplitterTests {

    private final TextSplittingProperties properties = new TextSplittingProperties();
    private final TextSplitter splitter = new TextSplitter(properties);

    @Test
    void keepsShortTextUnchanged() {
        String text = "Первый абзац.\n\nВторой абзац.";

        assertThat(splitter.split(text)).containsExactly(text);
    }

    @Test
    void packsWholeParagraphsInOrder() {
        properties.setSingleRequestTextTokens(4);
        properties.setPartTextTokens(4);
        String text = "One.\n\nTwo.\n\nThree.\n\nFour.";

        List<String> parts = splitter.split(text);

        assertThat(parts).containsExactly("One.\n\nTwo.\n\n", "Three.\n\nFour.");
        assertThat(String.join("", parts)).isEqualTo(text);
    }

    @Test
    void keepsWholeParagraphWhenSeparatorDoesNotFit() {
        properties.setSingleRequestTextTokens(4);
        properties.setPartTextTokens(4);
        String text = "First paragraph.\n\nNext.";

        assertThat(splitter.split(text))
                .containsExactly("First paragraph.", "\n\nNext.");
    }

    @Test
    void splitsLongParagraphBySentences() {
        properties.setSingleRequestTextTokens(4);
        properties.setPartTextTokens(4);
        String text = "One is here. Two is here.";

        assertThat(splitter.split(text))
                .containsExactly("One is here. ", "Two is here.");
    }

    @Test
    void splitsLongSentenceByWords() {
        properties.setSingleRequestTextTokens(3);
        properties.setPartTextTokens(3);
        String text = "alpha beta gamma delta";

        assertThat(splitter.split(text))
                .containsExactly("alpha beta ", "gamma delta");
    }

    @Test
    void rejectsTextOverCharacterLimit() {
        String text = "a".repeat(24_001);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> splitter.split(text))
                .withMessageContaining("лимит символов");
    }

    @Test
    void rejectsTextOverEstimatedTokenLimit() {
        // В UTF-8 каждый эмодзи занимает 4 байта.
        String text = "😀".repeat(10_001);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> splitter.split(text))
                .withMessageContaining("лимит токенов");
    }

    @Test
    void rejectsTextThatNeedsMoreThanSixParts() {
        properties.setSingleRequestTextTokens(1);
        properties.setPartTextTokens(1);
        String text = "😀".repeat(7);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> splitter.split(text))
                .withMessageContaining("лимит количества частей");
    }
}
