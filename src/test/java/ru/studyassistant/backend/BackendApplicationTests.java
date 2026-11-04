package ru.studyassistant.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=")
class BackendApplicationTests {
    @Value("${local.server.port}")
    private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void healthAndModesAreAvailableWithoutLlmCredentials() throws Exception {
        var publicHealth = get("/health");
        assertThat(publicHealth.statusCode()).isEqualTo(200);
        assertThat(publicHealth.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(publicHealth.body()).isEqualTo("{\"status\":\"ok\"}");
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        var modes = get("/api/v1/modes");
        assertThat(modes.statusCode()).isEqualTo(200);
        assertThat(modes.body()).contains("SHORT_SUMMARY", "DETAILED_SUMMARY",
                "SIMPLE_EXPLANATION", "SELF_CHECK_QUESTIONS", "KEY_TERMS");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
