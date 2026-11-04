package ru.studyassistant.backend.material;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.extraction.TextExtractor;
import ru.studyassistant.backend.llm.LlmClient;

@Service
public class MaterialService {
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 МБ
    private static final int MAX_TEXT_LENGTH = 10000;
    private static final Pattern NON_WHITESPACE = Pattern.compile("\\S");
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".txt");

    private final TextExtractor textExtractor;
    private final LlmClient llmClient;

    public MaterialService(TextExtractor textExtractor, LlmClient llmClient) {
        this.textExtractor = textExtractor;
        this.llmClient = llmClient;
    }

    public ProcessingResult process(MultipartFile file, ProcessingMode mode) {
        // 1. Проверка расширения файла
        String filename = file.getOriginalFilename();
        if (filename == null || !hasAllowedExtension(filename)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Принят неподдерживаемый формат файла. Ожидался TXT."
            );
        }

        // 2. Проверка размера файла
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Размер файла превышает максимальный лимит в 10 МБ."
            );
        }

        // 3. Извлечение текста из файла
        String text;
        try {
            text = textExtractor.extract(file.getInputStream(), filename);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Не удалось прочитать содержимое файла: " + e.getMessage()
            );
        }

        // 4. Проверка, что текст не пустой
        if (text == null || !NON_WHITESPACE.matcher(text).find()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Файл пуст или не содержит текста."
            );
        }

        // 5. Проверка длины текста (Unicode символы)
        if (text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Текст в файле превышает максимальную длину в 10000 символов."
            );
        }

        // 6. Обработка текста через LLM
        String result = llmClient.generate(text, mode);
        return new ProcessingResult(mode, result);
    }

    private boolean hasAllowedExtension(String filename) {
        String lowerFilename = filename.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
    }
}
