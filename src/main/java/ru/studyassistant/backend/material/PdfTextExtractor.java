package ru.studyassistant.backend.material;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Извлекает только текстовый слой PDF, без OCR. */
@Component
public class PdfTextExtractor {
    private final Duration timeout;

    public PdfTextExtractor() {
        this(Duration.ofSeconds(10));
    }

    PdfTextExtractor(Duration timeout) {
        this.timeout = timeout;
    }

    public String extract(byte[] bytes) {
        var task = new FutureTask<String>(() -> readPdf(bytes));
        Thread.startVirtualThread(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Превышено время обработки файла. Попробуйте файл меньшего размера или сложности.");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Обработка файла прервана.");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ResponseStatusException response) {
                throw response;
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF-файл повреждён и не может быть прочитан. Проверьте файл и загрузите снова.");
        }
    }

    String readPdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "PDF-файл защищён паролем. Снимите защиту и загрузите файл снова.");
            }
            if (document.getNumberOfPages() > 50) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "PDF содержит слишком много страниц. Максимум: 50 страниц.");
            }
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            var output = new StringBuilder();
            // Останавливаем извлечение при достижении лимита, не обрезаем результат.
            stripper.writeText(document, new Writer() {
                @Override
                public void write(char[] chars, int offset, int length) throws IOException {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("PDF parsing interrupted");
                    }
                    output.append(chars, offset, length);
                    if (output.codePointCount(0, output.length()) > 10000) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "Максимальная длина текста — 10000 символов.");
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            String text = output.toString();
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "PDF-файл не содержит текстового слоя (возможно, это скан). "
                                + "Загрузите PDF с распознаваемым текстом или TXT-файл.");
            }
            return text;
        } catch (InvalidPasswordException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF-файл защищён паролем. Снимите защиту и загрузите файл снова.");
        }
    }
}
