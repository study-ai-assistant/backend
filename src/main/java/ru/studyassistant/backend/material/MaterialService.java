package ru.studyassistant.backend.material;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
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
    
    // ДОБАВИЛИ .pdf в список разрешенных
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".txt", ".pdf");

    private final TextExtractor textExtractor;
    private final LlmClient llmClient;
    private final PdfTextExtractor pdfTextExtractor; // ДОБАВИЛИ

    public MaterialService(TextExtractor textExtractor, LlmClient llmClient, PdfTextExtractor pdfTextExtractor) {
        this.textExtractor = textExtractor;
        this.llmClient = llmClient;
        this.pdfTextExtractor = pdfTextExtractor; // ДОБАВИЛИ
    }

    public ProcessingResult process(MultipartFile file, ProcessingMode mode) {
        // 1. Проверка расширения файла
        String filename = file.getOriginalFilename();
        if (filename == null || !hasAllowedExtension(filename)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Принят неподдерживаемый формат файла. Ожидался TXT или PDF." // ИЗМЕНИЛИ
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
            if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                // НОВАЯ ЛОГИКА ДЛЯ PDF
                text = pdfTextExtractor.extractText(file);
            } else {
                // СТАРАЯ ЛОГИКА ДЛЯ TXT
                try (InputStream input = file.getInputStream()) {
                    text = textExtractor.extract(input, filename);
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Не удалось прочитать файл."
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
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
    }
}