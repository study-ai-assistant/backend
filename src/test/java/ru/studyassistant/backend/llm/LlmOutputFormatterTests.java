package ru.studyassistant.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmOutputFormatterTests {
    @Test
    void stripsPresentationWithoutChangingExponentOrMath() {
        assertThat(LlmOutputFormatter.format("# Заголовок\n**Определение**: x**2 + y**2, a * b, C#."))
                .isEqualTo("Заголовок\nОпределение: x**2 + y**2, a * b, C#.");
    }

    @Test
    void keepsCodeAndUnicodeInsideFences() {
        assertThat(LlmOutputFormatter.format("```python\n# Комментарий к коду\nx = 2**3\n```\nТермин — определение."))
                .isEqualTo("# Комментарий к коду\nx = 2**3\nТермин — определение.");
    }
}
