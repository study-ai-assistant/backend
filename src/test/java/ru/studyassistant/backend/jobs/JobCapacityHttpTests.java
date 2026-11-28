package ru.studyassistant.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ru.studyassistant.backend.llm.LlmClient;
import tools.jackson.databind.json.JsonMapper;

/** Проверяет HTTP-контракт переполнения и удаления задания, не обращаясь к Groq. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "app.jobs.capacity=1", "app.jobs.retention=500ms"})
class JobCapacityHttpTests {
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private Gate gate;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void returns503WhenFullAnd404AfterRetentionThenAcceptsNewJob() throws Exception {
        var first = submit();
        assertThat(first.statusCode()).isEqualTo(202);
        String location = first.headers().firstValue("Location").orElseThrow();
        assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var full = submit();
            assertThat(full.statusCode()).isEqualTo(503);
            assertThat(full.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/problem+json");
            var problem = json.readTree(full.body());
            assertThat(problem.path("status").asInt()).isEqualTo(503);
            assertThat(problem.path("instance").asText()).isEqualTo("/api/v1/jobs/text");
            assertThat(problem.path("detail").asText()).isNotBlank();
        } finally {
            gate.release.countDown();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        HttpResponse<String> result;
        do {
            result = get(location);
            assertThat(System.nanoTime()).isLessThan(deadline);
            if (result.statusCode() == 404) { break; }
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
            Thread.sleep(20);
        } while (true);
        var expired = json.readTree(result.body());
        assertThat(expired.path("status").asInt()).isEqualTo(404);
        assertThat(expired.path("instance").asText()).isEqualTo(location);
        assertThat(submit().statusCode()).isEqualTo(202);
    }

    private HttpResponse<String> submit() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("text",
                "Algorithms preserve ordering and validate input. ".repeat(300), "mode", "SHORT_SUMMARY"));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/jobs/text"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary Gate gate() { return new Gate(); }
        @Bean @Primary LlmClient client(Gate gate) {
            return (text, mode) -> {
                gate.entered.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Gate timeout"); }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return "Результат";
            };
        }
    }

    static class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }
}
