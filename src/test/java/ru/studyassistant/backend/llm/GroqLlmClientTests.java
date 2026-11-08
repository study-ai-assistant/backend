package ru.studyassistant.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import ru.studyassistant.backend.llm.LlmUnavailableException.Reason;
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

    /** Проверяет отправку промпта выбранного режима и получение результата. */
    @ParameterizedTest
    @EnumSource(ProcessingMode.class)
    void sendsPromptForEachMode(ProcessingMode mode) throws Exception {
        respondWith(200, """
                {
                  "id": "test",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "  Результат обработки  "
                    }
                  }]
                }
                """);

        assertThat(client.generate("Текст", mode))
                .isEqualTo("Результат обработки");

        ReceivedRequest request = received.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).isEqualTo("Bearer test-key");
        assertThat(request.body()).doesNotContain("test-key");

        var json = mapper.readTree(request.body());
        assertThat(json.path("model").asText()).isEqualTo("test-model");

        var messages = json.path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        String expectedInstruction = switch (mode) {
            case SHORT_SUMMARY -> "Сделай краткий конспект";
            case DETAILED_SUMMARY -> "Сделай подробный конспект";
            case SIMPLE_EXPLANATION -> "простыми словами для новичка";
            case SELF_CHECK_QUESTIONS -> "Составь вопросы для самопроверки";
            case KEY_TERMS -> "Выдели ключевые термины";
        };
        assertThat(messages.get(0).path("content").asText())
                .contains(expectedInstruction, "Отвечай на русском языке")
                .isEqualTo(LlmPrompts.forMode(mode));
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("Текст");
    }

    /** Ошибки провайдера не должны раскрывать его внутренний ответ. */
    @ParameterizedTest
    @CsvSource({
            "401, UNAVAILABLE",
            "408, TIMEOUT",
            "429, RATE_LIMIT",
            "500, UNAVAILABLE",
            "503, UNAVAILABLE",
            "504, TIMEOUT"
    })
    void handlesProviderErrors(int status, Reason reason) {
        respondWith(status, "{\"error\":\"Internal provider information\"}");
        assertFailure(reason);
    }

    @Test
    void logsOnlySafeDiagnosticsEvenAtDebugLevel() {
        var restLogger = (Logger) LoggerFactory.getLogger("org.springframework.web.client.DefaultRestClient");
        var groqLogger = (Logger) LoggerFactory.getLogger(GroqLlmClient.class);
        Level previousLevel = restLogger.getLevel();

        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        restLogger.addAppender(appender);
        groqLogger.addAppender(appender);
        restLogger.setLevel(Level.DEBUG);

        try {
            respondWith(429, "{\"error\":\"private-provider-response\"}");
            assertFailure(Reason.RATE_LIMIT);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("Writing [GroqRequest]"))
                    .anySatisfy(message -> assertThat(message).contains("RATE_LIMIT", "http_status=429"))
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("Текст", "test-key", "private-provider-response"));

            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            restLogger.setLevel(previousLevel);
            restLogger.detachAppender(appender);
            groqLogger.detachAppender(appender);
            appender.stop();
        }
    }

    /** Даже ответ 200 считается ошибкой, если в нём нет результата. */
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
        assertFailure(Reason.UNAVAILABLE);
    }

    @Test
    void rejectsMissingKey() {
        properties.setApiKey("");
        respondWith(200, "{}");

        assertFailure(Reason.UNAVAILABLE);
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
                assertFailure(Reason.TIMEOUT);

                long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
                assertThat(seconds).isBetween(58L, 64L);
            });
        } finally {
            finished.countDown();
        }
    }

    /** Общая проверка безопасной ошибки клиента. */
    private void assertFailure(Reason reason) {
        assertThatThrownBy(() -> client.generate("Текст", ProcessingMode.SHORT_SUMMARY))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessage(new LlmUnavailableException(reason).getMessage())
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
