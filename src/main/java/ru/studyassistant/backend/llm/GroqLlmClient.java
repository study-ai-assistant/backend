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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final GroqQuota quota;
    private final ThreadLocal<Usage> usage = new ThreadLocal<>();

    public GroqLlmClient(LlmProperties properties) {
        this(properties, new GroqQuota(new QuotaProperties()));
    }

    @Autowired
    public GroqLlmClient(LlmProperties properties, GroqQuota quota) {
        this.properties = properties;
        this.quota = quota;

        // Ограничиваем время подключения и ожидания
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));

        this.client = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** Обрабатывает целый материал в выбранном режиме. */
    @Override
    public String generate(String text, ProcessingMode mode) {
        return generateWithPrompt(text, LlmPrompts.forMode(mode), 1600, false);
    }

    /** Обрабатывает одну часть материала. */
    public String generateChunk(String text, ProcessingMode mode) {
        return generateWithPrompt(text, LlmPrompts.forChunk(mode), 1600, true);
    }

    /** Объединяет ответы по частям в итоговый результат. */
    public String mergeResults(String results, ProcessingMode mode) {
        return generateWithPrompt(results, LlmPrompts.forMerge(mode), 2400, true);
    }

    @Override
    public Usage lastUsage() { return usage.get(); }

    /** Ожидание квоты отделено от 60-секундного сетевого таймаута. */
    private String generateWithPrompt(String text, String systemPrompt, int maxOutput, boolean waitForQuota) {
        usage.remove();
        if (!"groq".equalsIgnoreCase(properties.getProvider())
                || properties.getApiKey().isBlank()
                || properties.getModel().isBlank()
                || properties.getBaseUrl().isBlank()) {
            throw failure(Reason.UNAVAILABLE, "missing_configuration");
        }
        // Не включаем ожидание квоты в 60 секунд сетевого вызова.
        var reservation = quota.acquire(
                TokenCounter.count(text) + TokenCounter.count(systemPrompt) + maxOutput + 64, waitForQuota);
        var task = new FutureTask<Completion>(() -> generateText(text, systemPrompt, maxOutput));
        Thread.startVirtualThread(task);

        try {
            Completion completion = task.get(60, TimeUnit.SECONDS);
            quota.complete(reservation, completion.usage());
            usage.set(completion.usage());
            return LlmOutputFormatter.format(completion.text());
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw failure(Reason.TIMEOUT, "deadline_exceeded");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(Reason.UNAVAILABLE, "interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof LlmUnavailableException llmFailure) {
                usage.set(llmFailure.getUsage());
                quota.complete(reservation, llmFailure.getUsage());
                throw llmFailure;
            }
            throw failure(Reason.UNAVAILABLE, "unexpected_client_error");
        }
    }

    /** Отправляет текст в Groq и извлекает результат из ответа. */
    private Completion generateText(String text, String systemPrompt, int maxOutput) {

        // Инструкция режима и учебный текст передаются отдельно.
        var messages = List.of(
                Map.of(
                        "role", "system",
                        "content", systemPrompt
                ),
                Map.of("role", "user", "content", text)
        );

        var request = new GroqRequest(properties.getModel(), messages, maxOutput, "low");

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

            if ("length".equals(choice.finish_reason())) {
                LOG.warn("Groq request failed: reason=UNAVAILABLE, diagnostic=truncated_completion");
                throw new LlmUnavailableException(Reason.UNAVAILABLE, response.usage() == null ? null
                        : new Usage(response.usage().prompt_tokens(), response.usage().completion_tokens()));
            }
            return new Completion(choice.message().content().strip(), response.usage() == null ? null
                    : new Usage(response.usage().prompt_tokens(), response.usage().completion_tokens()));
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

    private record GroqRequest(String model, List<Map<String, String>> messages,
                               int max_completion_tokens, String reasoning_effort) {
        @Override
        public String toString() {
            // RestClient вызывает toString() при отладочном логировании запроса.
            return "GroqRequest";
        }
    }

    /** Структура ответа Groq. Дополнительные поля JSON игнорируются. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroqResponse(List<Choice> choices, ProviderUsage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderUsage(long prompt_tokens, long completion_tokens) {}

    private record Completion(String text, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message, String finish_reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String content) {}
}
