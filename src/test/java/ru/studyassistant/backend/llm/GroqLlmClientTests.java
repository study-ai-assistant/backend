package ru.studyassistant.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.studyassistant.backend.material.ProcessingMode;
import tools.jackson.databind.json.JsonMapper;

/** Проверяет клиент Groq с помощью локального сервера вместо настоящего API. */
class GroqLlmClientTests {

    private HttpServer server;
    private LlmProperties properties;
    private GroqLlmClient client;

    private final AtomicReference<ReceivedRequest> received = new AtomicReference<>();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        properties = new LlmProperties();
        properties.setProvider("groq");
        properties.setModel("test-model");
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        client = new GroqLlmClient(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Проверяет содержимое запроса и получение конспекта. */
    @Test
    void returnsSummary() throws Exception {
        respondWith(200, """
                {
                  "id": "test",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "  Конспект  "
                    }
                  }]
                }
                """);

        assertThat(client.generate("Текст", ProcessingMode.SHORT_SUMMARY))
                .isEqualTo("Конспект");

        ReceivedRequest request = received.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).isEqualTo("Bearer test-key");
        assertThat(request.body()).doesNotContain("test-key");

        var json = mapper.readTree(request.body());
        assertThat(json.path("model").asText()).isEqualTo("test-model");
        assertThat(json.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(json.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(json.path("messages").get(1).path("content").asText()).isEqualTo("Текст");
    }

    /** Ошибки провайдера не должны раскрывать его внутренний ответ. */
    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500, 503})
    void handlesProviderErrors(int status) {
        respondWith(status, "{\"error\":\"Internal provider information\"}");
        assertUnavailable();
    }

    /** Даже ответ 200 считается ошибкой, если в нём нет конспекта. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}",
            "{\"choices\":[{\"message\":null}]}",
            "{}",
            "not-json"
    })
    void handlesInvalidResponses(String body) {
        respondWith(200, body);
        assertUnavailable();
    }

    @Test
    void rejectsMissingKey() {
        properties.setApiKey("");
        respondWith(200, "{}");

        assertUnavailable();
        assertThat(received.get()).isNull();
    }

    /** Проверяет предел 60 секунд при непрерывном поступлении данных. */
    @Test
    void stopsWaitingAfterTimeout() {
        var finished = new CountDownLatch(1);

        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);

                // Начинаем JSON, но не завершаем его.
                var output = exchange.getResponseBody();
                output.write("{\"choices\":[".getBytes(StandardCharsets.UTF_8));
                output.flush();

                while (!finished.await(250, TimeUnit.MILLISECONDS)) {
                    output.write(' ');
                    output.flush();
                }
            } catch (IOException exception) {
                // После таймаута клиент может закрыть соединение.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        server.start();

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(65), () -> {
                long started = System.nanoTime();
                assertUnavailable();

                long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
                assertThat(seconds).isBetween(58L, 64L);
            });
        } finally {
            finished.countDown();
        }
    }

    /** Общая проверка безопасной ошибки клиента. */
    private void assertUnavailable() {
        assertThatThrownBy(() -> client.generate("Текст", ProcessingMode.SHORT_SUMMARY))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessage("Сервис обработки текста временно недоступен. Повторите запрос позже.")
                .hasNoCause();
    }

    /** Настраивает ответ локального сервера и записывает входящий запрос. */
    private void respondWith(int status, String body) {
        server.createContext("/chat/completions", exchange -> {
            try {
                received.set(new ReceivedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                ));

                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });

        server.start();
    }

    /** Данные запроса, записанные локальным сервером для проверки. */
    private record ReceivedRequest(String method, String authorization, String body) {}
}
