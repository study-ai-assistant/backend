import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import ru.studyassistant.backend.llm.LlmPrompts;
import ru.studyassistant.backend.material.ProcessingMode;
import ru.studyassistant.backend.llm.LlmOutputFormatter;
import ru.studyassistant.backend.llm.TokenCounter;
import ru.studyassistant.backend.material.PdfProperties;
import ru.studyassistant.backend.material.PdfTextExtractor;
import ru.studyassistant.backend.material.PdfWorkerLauncher;
import ru.studyassistant.backend.material.TextSplitter;
import ru.studyassistant.backend.material.TextSplittingProperties;

/** Проверяет размер набора и применяет серверное оформление к сохранённым ответам; LLM не вызывает. */
public class PrepareExamples {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var splitter = new TextSplitter(new TextSplittingProperties());
        var pdf = new PdfTextExtractor(new PdfProperties(), new PdfWorkerLauncher());
        var inventory = new ArrayList<Object>();
        for (String name : List.of("benchmark-2-parts.txt", "long-lecture.txt", "long-lecture.pdf",
                "short-text.txt", "short-file.txt", "study-notes.pdf")) {
            String text = name.endsWith(".pdf") ? pdf.extract(Files.readAllBytes(root.resolve(name)), 24000)
                    : Files.readString(root.resolve(name));
            var item = new LinkedHashMap<String, Object>();
            item.put("file", name);
            item.put("characters", text.codePointCount(0, text.length()));
            item.put("tokens", TokenCounter.count(text));
            item.put("parts", splitter.split(text).size());
            long reserved = 0;
            for (ProcessingMode mode : ProcessingMode.values()) {
                reserved += TokenCounter.count(text) + TokenCounter.count(LlmPrompts.forMode(mode)) + 1600 + 64;
            }
            if (splitter.split(text).size() == 1) { item.put("all_modes_token_reserve", reserved); }
            inventory.add(item);
            System.out.printf("%s: %d characters, %d o200k tokens, %d parts%n", name,
                    text.codePointCount(0, text.length()), TokenCounter.count(text), splitter.split(text).size());
        }
        Files.writeString(root.resolve("INPUT_INVENTORY.json"),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(inventory) + "\n");
        Path output = root.resolve("examples");
        Files.createDirectories(output);
        // Более поздний повтор вопросов заменяет ранний пример; исходные ответы остаются в results.
        for (String group : List.of("long", "demo", "recheck")) {
            Path input = root.resolve("results").resolve(group);
            if (!Files.isDirectory(input)) { continue; }
            try (var paths = Files.list(input)) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".txt")).toList()) {
                    Files.writeString(output.resolve(file.getFileName()),
                            LlmOutputFormatter.format(Files.readString(file)) + "\n");
                }
            }
        }
    }
}
