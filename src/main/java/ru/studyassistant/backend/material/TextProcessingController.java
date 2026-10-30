package ru.studyassistant.backend.material;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.llm.LlmClient;

/** Принимает учебный текст в JSON и возвращает результат выбранного режима. */
@RestController
public class TextProcessingController {

    private static final int MAX_TEXT_LENGTH = 10000;
    private static final Pattern NON_WHITESPACE = Pattern.compile("\\S");
    private static final Set<String> REQUIRED_FIELDS = Set.of("text", "mode");

    private final LlmClient llmClient;

    public TextProcessingController(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @PostMapping(
            value = "/api/v1/text/process",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ProcessingResult process(@RequestBody Map<String, Object> request) {
        if (!request.keySet().equals(REQUIRED_FIELDS)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Запрос должен содержать только поля text и mode."
            );
        }

        if (!(request.get("text") instanceof String text)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Поле text должно быть строкой."
            );
        }

        // Считаем Unicode точки чтобы учитывать эмодзи как один символ
        if (text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.valueOf(413),
                    "Максимальная длина текста — 10000 символов."
            );
        }

        if (!NON_WHITESPACE.matcher(text).find()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Текст не должен быть пустым."
            );
        }

        if (!(request.get("mode") instanceof String modeName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Поле mode должно быть строкой."
            );
        }

        // Преобразуем название режима в enum. Неизвестный режим возвращает 400.
        ProcessingMode mode;
        try {
            mode = ProcessingMode.valueOf(modeName);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Неподдерживаемый режим обработки."
            );
        }

        String result = llmClient.generate(text, mode);
        return new ProcessingResult(mode, result);
    }
}
