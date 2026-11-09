package ru.studyassistant.backend.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.studyassistant.backend.llm.LlmUnavailableException.Reason;
import ru.studyassistant.backend.material.ProcessingMode;

/** HTTP-клиент Groq для обработки учебного текста в выбранном режиме. */
@Component
public class GroqLlmClient implements LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(GroqLlmClient.class);

    private final LlmProperties properties;
    private final RestClient client;

    public GroqLlmClient(LlmProperties properties) {
        this.properties = properties;

        // Ограничиваем время подключения и ожидания
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));

        this.client = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** Запускает обработку и ограничивает общее ожидание результата 60 секундами. */
    @Override
    public String generate(String text, ProcessingMode mode) {

        var task = new FutureTask<String>(() -> generateText(text, mode));
        Thread.startVirtualThread(task);

        try {
            return task.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw failure(Reason.TIMEOUT, "deadline_exceeded");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(Reason.UNAVAILABLE, "interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof LlmUnavailableException llmFailure) {
                throw llmFailure;
            }
            throw failure(Reason.UNAVAILABLE, "unexpected_client_error");
        }
    }

    /** Отправляет текст в Groq и извлекает конспект из ответа. */
    private String generateText(String text, ProcessingMode mode) {
        if (!"groq".equalsIgnoreCase(properties.getProvider())
                || properties.getApiKey().isBlank()
                || properties.getModel().isBlank()
                || properties.getBaseUrl().isBlank()) {
            throw failure(Reason.UNAVAILABLE, "missing_configuration");
        }

        // Инструкция режима и учебный текст передаются отдельно.
        var messages = List.of(
                Map.of(
                        "role", "system",
                        "content", LlmPrompts.forMode(mode)
                ),
                Map.of("role", "user", "content", text)
        );

        var request = new GroqRequest(properties.getModel(), messages);

        String url = properties.getBaseUrl().replaceAll("/+$", "")
                + "/chat/completions";

        try {
            // Ключ передаётся в заголовке авторизации тело в JSON.
            GroqResponse response = client.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (ignored, upstream) -> {
                        int status = upstream.getStatusCode().value();
                        Reason reason = switch (status) {
                            case 429 -> Reason.RATE_LIMIT;
                            case 408, 504 -> Reason.TIMEOUT;
                            default -> Reason.UNAVAILABLE;
                        };
                        throw failure(reason, "http_status=" + status);
                    })
                    .body(GroqResponse.class);

            if (response == null
                    || response.choices() == null
                    || response.choices().isEmpty()) {
                throw failure(Reason.UNAVAILABLE, "empty_choices");
            }

            Choice choice = response.choices().getFirst();

            // Пустой или неполный ответ
            if (choice == null
                    || choice.message() == null
                    || choice.message().content() == null
                    || choice.message().content().isBlank()) {
                throw failure(Reason.UNAVAILABLE, "empty_content");
            }

            return choice.message().content().strip();
        } catch (RestClientException exception) {
            if (exception.contains(SocketTimeoutException.class)) {
                throw failure(Reason.TIMEOUT, "socket_timeout");
            }
            String diagnostic = exception instanceof ResourceAccessException
                    ? "network_error" : "invalid_response";
            throw failure(Reason.UNAVAILABLE, diagnostic);
        }
    }

    private LlmUnavailableException failure(Reason reason, String diagnostic) {
        LOG.warn("Groq request failed: reason={}, diagnostic={}", reason, diagnostic);
        return new LlmUnavailableException(reason);
    }

    private record GroqRequest(String model, List<Map<String, String>> messages) {
        @Override
        public String toString() {
            // RestClient вызывает toString() при отладочном логировании запроса.
            return "GroqRequest";
        }
    }

    /** Структура ответа Groq. дополнительные поля JSON игнорируются. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroqResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String content) {}
}
