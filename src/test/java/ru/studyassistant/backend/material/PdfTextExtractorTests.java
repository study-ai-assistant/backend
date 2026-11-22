package ru.studyassistant.backend.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PdfTextExtractorTests {
    @Test
    void returns504WhenParsingExceedsDeadline() {
        var stopped = new CountDownLatch(1);
        var extractor = new PdfTextExtractor(Duration.ofMillis(50)) {
            @Override
            String readPdf(byte[] bytes) throws IOException {
                try {
                    new CountDownLatch(1).await();
                    return "";
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                } finally {
                    stopped.countDown();
                }
            }
        };
        var exception = assertThrows(ResponseStatusException.class, () -> extractor.extract(new byte[0]));
        assertThat(exception.getStatusCode().value()).isEqualTo(504);
        try {
            assertThat(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
