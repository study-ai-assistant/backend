package ru.studyassistant.backend.material;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".txt", ".pdf");

    private final TextExtractor textExtractor;
    private final LlmClient llmClient;
    private final PdfTextExtractor pdfTextExtractor;

    public MaterialService(TextExtractor textExtractor, LlmClient llmClient, PdfTextExtractor pdfTextExtractor) {
        this.textExtractor = textExtractor;
        this.llmClient = llmClient;
        this.pdfTextExtractor = pdfTextExtractor;
    }

    public ProcessingResult process(MultipartFile file, ProcessingMode mode) {
        // 1. Проверка расширения файла
        String filename = file.getOriginalFilename();
        if (filename == null || !hasAllowedExtension(filename)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Принят неподдерживаемый формат файла. Ожидался TXT или PDF."
            );
        }

        // 2. Проверка размера файла
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Размер файла превышает максимальный лимит в 10 МБ."
            );
        }

        // Проверяем содержимое до выбора экстрактора, MIME клиента не считаем доказательством.
        String text;
        try {
            byte[] bytes = file.getBytes();
            boolean pdfContent = new String(bytes, 0, Math.min(bytes.length, 5),
                    StandardCharsets.US_ASCII).equals("%PDF-");
            boolean pdfName = filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
            if (pdfName != pdfContent || (!pdfName && hasBinaryContent(bytes))) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Содержимое файла не соответствует формату TXT или PDF.");
            }
            if (pdfContent) {
                text = pdfTextExtractor.extract(bytes);
            } else {
                try (var input = new ByteArrayInputStream(bytes)) {
                    text = textExtractor.extract(input, filename);
                }
                if (text != null && text.codePoints().anyMatch(c -> Character.isISOControl(c)
                        && c != '\n' && c != '\r' && c != '\t')) {
                    throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "Файл не содержит обычный текст TXT.");
                }
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не удалось прочитать файл.");
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

    private boolean hasBinaryContent(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8))) {
            return true; // ZIP/DOCX нельзя выдавать за TXT.
        }
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAllowedExtension(String filename) {
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
    }
}
