package ru.studyassistant.backend.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.studyassistant.backend.material.ProcessingMode;

/** HTTP-клиент Groq для обработки учебного текста в выбранном режиме. */
@Component
public class GroqLlmClient implements LlmClient {

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
            throw new LlmUnavailableException();
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException();
        } catch (ExecutionException exception) {
            throw new LlmUnavailableException();
        }
    }

    /** Отправляет текст в Groq и извлекает конспект из ответа. */
    private String generateText(String text, ProcessingMode mode) {
        if (!"groq".equalsIgnoreCase(properties.getProvider())
                || properties.getApiKey().isBlank()
                || properties.getModel().isBlank()
                || properties.getBaseUrl().isBlank()) {
            throw new LlmUnavailableException();
        }

        // Инструкция режима и учебный текст передаются отдельно.
        var messages = List.of(
                Map.of(
                        "role", "system",
                        "content", LlmPrompts.forMode(mode)
                ),
                Map.of("role", "user", "content", text)
        );

        var request = Map.of(
                "model", properties.getModel(),
                "messages", messages
        );

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
                    .body(GroqResponse.class);

            if (response == null
                    || response.choices() == null
                    || response.choices().isEmpty()) {
                throw new LlmUnavailableException();
            }

            Choice choice = response.choices().getFirst();

            // Пустой или неполный ответ
            if (choice == null
                    || choice.message() == null
                    || choice.message().content() == null
                    || choice.message().content().isBlank()) {
                throw new LlmUnavailableException();
            }

            return choice.message().content().strip();
        } catch (RestClientException exception) {
            throw new LlmUnavailableException();
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
