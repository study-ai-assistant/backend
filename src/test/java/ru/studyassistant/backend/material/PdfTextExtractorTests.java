package ru.studyassistant.backend.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class PdfTextExtractorTests {
    @TempDir
    Path root;

    @Test
    void killsUncooperativeProcessCleansFilesAndReleasesSlot() throws Exception {
        var properties = new PdfProperties();
        properties.setTimeout(Duration.ofSeconds(1));
        properties.setMaxConcurrent(1);
        properties.setTempDirectory(root);
        var started = new CountDownLatch(1);
        var child = new AtomicReference<Process>();
        var launcher = new PdfWorkerLauncher() {
            @Override
            public Process start(Path directory, PdfProperties ignored) throws IOException {
                var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("surefire.test.class.path"), SleepProcess.class.getName())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                child.set(process);
                started.countDown();
                return process;
            }
        };
        var extractor = new PdfTextExtractor(properties, launcher);
        var request = new FutureTask<Integer>(() -> assertThrows(ResponseStatusException.class,
                () -> extractor.extract(new byte[] {1})).getStatusCode().value());
        Thread.startVirtualThread(request);
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(assertThrows(ResponseStatusException.class, () -> extractor.extract(new byte[] {2}))
                .getStatusCode().value()).isEqualTo(503);
        assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(504);
        assertThat(child.get().isAlive()).isFalse();
        try (var files = Files.list(root)) {
            assertThat(files.toList()).isEmpty();
        }
        // Второй раз слот доступен, поэтому получаем timeout, а не busy.
        assertThat(assertThrows(ResponseStatusException.class, () -> extractor.extract(new byte[] {3}))
                .getStatusCode().value()).isEqualTo(504);
    }

    @Test
    void cleansResourcesWhenProcessCannotStart() throws Exception {
        var properties = new PdfProperties();
        properties.setTempDirectory(root);
        var extractor = new PdfTextExtractor(properties, new PdfWorkerLauncher() {
            @Override
            public Process start(Path directory, PdfProperties ignored) throws IOException {
                throw new IOException("Test failure");
            }
        });
        assertThat(assertThrows(ResponseStatusException.class, () -> extractor.extract(new byte[] {1}))
                .getStatusCode().value()).isEqualTo(503);
        try (var files = Files.list(root)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    public static class SleepProcess {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(30000);
        }
    }
}
