package ru.studyassistant.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.studyassistant.backend.llm.LlmClient;
import ru.studyassistant.backend.llm.LlmUnavailableException;
import ru.studyassistant.backend.material.ProcessingMode;
import tools.jackson.databind.json.JsonMapper;

/** Проверяет API обработки текста без настоящих запросов к LLM. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TextProcessingTests {

    private static final String ENDPOINT = "/api/v1/text/process";
    private static final String VALID_REQUEST = """
            {"text":"Учебный текст","mode":"SHORT_SUMMARY"}
            """;

    // Spring запускает сервер на свободном порту и передаёт его сюда
    @Value("${local.server.port}")
    private int port;

    // Подменяем настоящий клиент объектом с заданными в тестах ответами
    @MockitoBean
    private LlmClient llmClient;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** Корректный запрос должен вернуть результат LLM и статус 200. */
    @Test
    void returnsSummary() throws Exception {
        when(llmClient.generate("Учебный текст", ProcessingMode.SHORT_SUMMARY))
                .thenReturn("Краткий конспект");

        var response = post(VALID_REQUEST);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");

        var json = mapper.readTree(response.body());
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.path("mode").asText()).isEqualTo("SHORT_SUMMARY");
        assertThat(json.path("result").asText()).isEqualTo("Краткий конспект");

        // Проверяем, что контроллер передал клиенту нужный текст и режим.
        verify(llmClient).generate("Учебный текст", ProcessingMode.SHORT_SUMMARY);
    }

    /** Неверные запросы должны отклоняться до обращения к LLM. */
    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequests(String body, int expectedStatus) throws Exception {
        assertProblem(post(body), expectedStatus);
        verifyNoInteractions(llmClient);
    }

    /** Набор запросов и ожидаемых статусов для параметризованного теста. */
    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                // Пустой текст.
                Arguments.of("{\"text\":\"   \",\"mode\":\"SHORT_SUMMARY\"}", 400),

                // Известный, но пока неподдерживаемый режим; неизвестный режим.
                Arguments.of("{\"text\":\"Текст\",\"mode\":\"DETAILED_SUMMARY\"}", 400),
                Arguments.of("{\"text\":\"Текст\",\"mode\":\"UNKNOWN\"}", 400),

                // Отсутствуют обязательные поля.
                Arguments.of("{\"mode\":\"SHORT_SUMMARY\"}", 400),
                Arguments.of("{\"text\":\"Текст\"}", 400),

                // Вместо строки передано число или null.
                Arguments.of("{\"text\":123,\"mode\":\"SHORT_SUMMARY\"}", 400),
                Arguments.of("{\"text\":null,\"mode\":\"SHORT_SUMMARY\"}", 400),

                // Лишнее поле и незавершённый JSON.
                Arguments.of("{\"text\":\"Текст\",\"mode\":\"SHORT_SUMMARY\",\"extra\":1}", 400),
                Arguments.of("{\"text\":", 400),

                // Превышен лимит длины текста.
                Arguments.of(
                        "{\"text\":\"" + "а".repeat(10001) + "\",\"mode\":\"SHORT_SUMMARY\"}",
                        413
                )
        );
    }

    /** При ошибке LLM возвращается 502, а сервер продолжает работать. */
    @Test
    void handlesLlmFailure() throws Exception {
        when(llmClient.generate("Учебный текст", ProcessingMode.SHORT_SUMMARY))
                .thenThrow(new LlmUnavailableException());

        var response = post(VALID_REQUEST);
        assertProblem(response, 502);

        var json = mapper.readTree(response.body());
        assertThat(json.path("detail").asText()).isEqualTo(
                "Сервис обработки текста временно недоступен. Повторите запрос позже."
        );

        var health = get("/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("ok");
    }

    /** Проверяет статус, формат и обязательные поля ошибки по контракту. */
    private void assertProblem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");

        var json = mapper.readTree(response.body());
        assertThat(json.path("type").asText()).isEqualTo("about:blank");
        assertThat(json.path("status").asInt()).isEqualTo(status);
        assertThat(json.path("instance").asText()).isEqualTo(ENDPOINT);
        assertThat(json.path("detail").asText()).isNotBlank();

        String expectedTitle = switch (status) {
            case 400 -> "Bad Request";
            case 413 -> "Payload Too Large";
            case 502 -> "Bad Gateway";
            default -> throw new IllegalArgumentException("Неожиданный статус");
        };

        assertThat(json.path("title").asText()).isEqualTo(expectedTitle);
    }

    /** Отправляет JSON на эндпоинт обработки текста. */
    private HttpResponse<String> post(String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(ENDPOINT))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Отправляет GET-запрос, например для проверки здоровья сервера. */
    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).GET().build();

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Собирает адрес с портом, выбранным Spring для этого теста. */
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
