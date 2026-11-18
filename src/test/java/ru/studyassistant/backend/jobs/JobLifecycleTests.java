package ru.studyassistant.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.llm.LlmClient;
import ru.studyassistant.backend.material.ProcessingMode;
import ru.studyassistant.backend.material.TextSplitter;
import ru.studyassistant.backend.material.TextSplittingProperties;

class JobLifecycleTests {
    @Test
    void expiresResultsAndReleasesCapacityWithoutKeepingText() throws Exception {
        var properties = new JobProperties();
        properties.setCapacity(1);
        properties.setRetention(Duration.ofMillis(40));
        var splitting = new TextSplittingProperties();
        splitting.setSingleRequestTextTokens(1);
        splitting.setPartTextTokens(4);
        var gate = new CountDownLatch(1);
        LlmClient llm = (text, mode) -> {
            try { gate.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "result";
        };
        try (var service = new Managed(new JobService(properties, new TextSplitter(splitting), llm))) {
            var first = service.jobs.submit("first sentence. second sentence.", ProcessingMode.SHORT_SUMMARY);
            assertThatThrownBy(() -> service.jobs.submit("another sentence.", ProcessingMode.SHORT_SUMMARY))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
            gate.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (service.jobs.get(first.id()).status() != JobView.Status.SUCCEEDED) {
                assertThat(System.nanoTime()).isLessThan(deadline); Thread.sleep(2);
            }
            Thread.sleep(60);
            assertThatThrownBy(() -> service.jobs.get(first.id()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThat(service.jobs.submit("new sentence. last sentence.", ProcessingMode.KEY_TERMS).id())
                    .isNotEqualTo(first.id());
        }
    }
    private record Managed(JobService jobs) implements AutoCloseable {
        public void close() { jobs.close(); }
    }
}
