package ru.studyassistant.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.studyassistant.backend.material.PdfTextExtractor;
import ru.studyassistant.backend.material.ProcessingMode;
import ru.studyassistant.backend.material.TextSplitter;
import ru.studyassistant.backend.material.TextSplittingProperties;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "manual.groq", matches = "true")
class LongMaterialPromptManualTest {

    private static final long REQUEST_PAUSE_MILLIS = 65_000;

    @Autowired
    private GroqLlmClient groqClient;

    @Autowired
    private PdfTextExtractor pdfExtractor;

    @Test
    void processesLongMaterial() throws Exception {
        Path file = Path.of(System.getProperty("manual.file"));
        var mode = ProcessingMode.valueOf(System.getProperty("manual.mode"));
        String text = file.toString().endsWith(".pdf")
                ? pdfExtractor.extract(Files.readAllBytes(file))
                : Files.readString(file);

        // Понижаем порог только в тесте, чтобы проверить оба этапа.
        var settings = new TextSplittingProperties();
        settings.setSingleRequestTextTokens(1_000);
        settings.setPartTextTokens(1_000);

        var parts = new TextSplitter(settings).split(text);
        assertThat(parts.size()).isGreaterThan(1);

        var answers = new ArrayList<String>();
        for (String part : parts) {
            if (!answers.isEmpty()) {
                System.out.println("Жду 65 секунд перед следующей частью.");
                Thread.sleep(REQUEST_PAUSE_MILLIS);
            }

            System.out.printf("Отправляю часть %d из %d.%n", answers.size() + 1, parts.size());
            answers.add(groqClient.generateChunk(part, mode));
        }

        System.out.println("Все части обработаны. Жду 65 секунд перед объединением.");
        Thread.sleep(REQUEST_PAUSE_MILLIS);

        System.out.println("Отправляю запрос на объединение.");
        String result = groqClient.mergeResults(String.join("\n\n", answers), mode);
        assertThat(result).isNotBlank();

        System.out.printf("%s | %s | частей: %d%n%s%n", file, mode, parts.size(), result);
    }
}
