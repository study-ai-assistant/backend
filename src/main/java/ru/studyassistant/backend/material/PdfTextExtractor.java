package ru.studyassistant.backend.material;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Ограничивает память и время PDFBox и освобождает процесс и файлы при любом исходе. */
@Component
public class PdfTextExtractor {
    private static final Logger LOG = LoggerFactory.getLogger(PdfTextExtractor.class);
    private final PdfProperties properties;
    private final PdfWorkerLauncher launcher;
    private final Semaphore slots;

    public PdfTextExtractor(PdfProperties properties, PdfWorkerLauncher launcher) {
        this.properties = properties;
        this.launcher = launcher;
        this.slots = new Semaphore(properties.getMaxConcurrent());
    }

    public String extract(byte[] bytes) {
        if (!slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Сервис разбора PDF занят. Повторите запрос позже.");
        }
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory(properties.getTempDirectory(), "study-pdf-");
            Files.write(directory.resolve("input.pdf"), bytes);
            process = launcher.start(directory, properties);
            process.getOutputStream().close();
            if (!process.waitFor(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "Превышено время обработки файла. Попробуйте файл меньшего размера или сложности.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(directory.resolve("result.bin"))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "PDF-файл повреждён или слишком сложен для обработки.");
            }
            return readResult(directory.resolve("result.bin"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Обработка файла прервана.");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Не удалось запустить обработку PDF. Повторите запрос позже.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                // Не освобождаем слот и файлы, пока worker действительно не завершился.
                boolean interrupted = Thread.interrupted();
                while (true) {
                    try {
                        process.waitFor();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            cleanup(directory);
            slots.release();
        }
    }

    private String readResult(Path file) throws IOException {
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            int status = input.readInt();
            int length = input.readInt();
            if (length < 0 || length > 40000) {
                throw new IOException("Invalid worker response");
            }
            byte[] data = input.readNBytes(length);
            if (data.length != length) {
                throw new IOException("Incomplete worker response");
            }
            String text = new String(data, StandardCharsets.UTF_8);
            if (status != 200) {
                throw new ResponseStatusException(HttpStatus.valueOf(status), text);
            }
            return text;
        }
    }

    private void cleanup(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            // Только событие: не записываем имя исходного файла, содержимое или исключение PDFBox.
            LOG.warn("PDF temporary resource cleanup failed");
        }
    }
}
