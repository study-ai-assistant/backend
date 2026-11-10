package ru.studyassistant.backend.material;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** Внутренний процесс: не запускает Spring, не читает .env, не вызывает LLM. */
public final class PdfWorker {
    private PdfWorker() { }

    public static void main(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int maxPages = Integer.parseInt(args[2]);
        int maxText = Integer.parseInt(args[3]);
        long started = System.nanoTime();
        int status = 200;
        String text;
        try {
            text = parse(input, maxPages, maxText);
        } catch (PdfProblem problem) {
            status = problem.status;
            text = problem.getMessage();
        } catch (InvalidPasswordException exception) {
            status = 422;
            text = "PDF-файл защищён паролем. Снимите защиту и загрузите файл снова.";
        } catch (IOException | RuntimeException exception) {
            status = 422;
            text = "PDF-файл повреждён и не может быть прочитан. Проверьте файл и загрузите снова.";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (var result = new DataOutputStream(Files.newOutputStream(output))) {
            result.writeInt(status);
            result.writeInt(bytes.length);
            result.write(bytes);
            result.writeLong(System.nanoTime() - started);
        }
    }

    private static String parse(Path file, int maxPages, int maxText) throws IOException {
        try (var document = Loader.loadPDF(file.toFile())) {
            if (document.isEncrypted()) {
                throw new PdfProblem(422, "PDF-файл защищён паролем. Снимите защиту и загрузите файл снова.");
            }
            if (document.getNumberOfPages() > maxPages) {
                throw new PdfProblem(413, "PDF содержит слишком много страниц. Максимум: " + maxPages + " страниц.");
            }
            var text = new StringBuilder();
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.writeText(document, new Writer() {
                @Override
                public void write(char[] chars, int offset, int length) throws IOException {
                    text.append(chars, offset, length);
                    if (text.codePointCount(0, text.length()) > maxText) {
                        throw new PdfProblem(413, "Максимальная длина текста — " + maxText + " символов.");
                    }
                }
                @Override
                public void flush() { }
                @Override
                public void close() { }
            });
            if (text.toString().isBlank()) {
                throw new PdfProblem(422, "PDF-файл не содержит текстового слоя (возможно, это скан). "
                        + "Загрузите PDF с распознаваемым текстом или TXT-файл.");
            }
            return text.toString();
        }
    }

    private static final class PdfProblem extends IOException {
        private final int status;
        private PdfProblem(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
