package ru.studyassistant.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.studyassistant.backend.llm.LlmClient;
import ru.studyassistant.backend.llm.LlmUnavailableException;
import ru.studyassistant.backend.material.ProcessingMode;
import tools.jackson.databind.json.JsonMapper;

/** Проверяет настоящий multipart HTTP, декодирование и LLM-заглушку вместе. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=")
class MaterialProcessingTests {
    private static final String TEXT = "Митохондрии обеспечивают клетку энергией.";
    private static final String PATH = "/api/v1/materials/process";
    @Value("${local.server.port}")
    private int port;
    @MockitoBean
    private LlmClient llmClient;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @EnumSource(ProcessingMode.class)
    void processesAllModes(ProcessingMode mode) throws Exception {
        when(llmClient.generate(TEXT, mode)).thenReturn("Ответ для " + mode);
        var response = upload("lecture.txt", TEXT.getBytes(StandardCharsets.UTF_8), mode.name(), "file");
        assertThat(response.statusCode()).isEqualTo(200);
        var json = mapper.readTree(response.body());
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.path("mode").asText()).isEqualTo(mode.name());
        assertThat(json.path("result").asText()).isEqualTo("Ответ для " + mode);
        verify(llmClient).generate(TEXT, mode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "windows-1251"})
    void preservesRussianText(String charset) throws Exception {
        when(llmClient.generate(TEXT, ProcessingMode.SHORT_SUMMARY)).thenReturn("Ответ");
        assertThat(upload("lecture.TXT", TEXT.getBytes(Charset.forName(charset)),
                "SHORT_SUMMARY", "file").statusCode()).isEqualTo(200);
        verify(llmClient).generate(TEXT, ProcessingMode.SHORT_SUMMARY);
    }

    @Test
    void stripsUtf8Bom() throws Exception {
        when(llmClient.generate(TEXT, ProcessingMode.SHORT_SUMMARY)).thenReturn("Ответ");
        assertThat(upload("bom.txt", ("\uFEFF" + TEXT).getBytes(StandardCharsets.UTF_8),
                "SHORT_SUMMARY", "file").statusCode()).isEqualTo(200);
        verify(llmClient).generate(TEXT, ProcessingMode.SHORT_SUMMARY);
    }

    @Test
    void acceptsUnicodeLimitIncludingEmoji() throws Exception {
        String text = "😀".repeat(10000);
        when(llmClient.generate(text, ProcessingMode.SHORT_SUMMARY)).thenReturn("Ответ");
        assertThat(upload("emoji.txt", text.getBytes(StandardCharsets.UTF_8),
                "SHORT_SUMMARY", "file").statusCode()).isEqualTo(200);
        verify(llmClient).generate(text, ProcessingMode.SHORT_SUMMARY);
    }

    static Stream<Arguments> invalidFiles() {
        return Stream.of(
                Arguments.of("empty.txt", new byte[0], "SHORT_SUMMARY", "file", 400),
                Arguments.of("space.txt", " \r\n\t".getBytes(StandardCharsets.UTF_8),
                        "SHORT_SUMMARY", "file", 400),
                Arguments.of("bom.txt", "\uFEFF".getBytes(StandardCharsets.UTF_8),
                        "SHORT_SUMMARY", "file", 400),
                Arguments.of("document.pdf", new byte[] {65}, "SHORT_SUMMARY", "file", 415),
                Arguments.of("long.txt", "а".repeat(10001).getBytes(StandardCharsets.UTF_8),
                        "SHORT_SUMMARY", "file", 413),
                Arguments.of("large.txt", new byte[10 * 1024 * 1024 + 1], "SHORT_SUMMARY", "file", 413),
                Arguments.of("test.txt", new byte[] {65}, "UNKNOWN", "file", 400),
                Arguments.of("test.txt", new byte[] {65}, "SHORT_SUMMARY", "files", 400)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidFiles")
    void rejectsInvalidUploadsBeforeLlm(String name, byte[] bytes, String mode, String field, int status)
            throws Exception {
        assertProblem(upload(name, bytes, mode, field), status);
        verifyNoInteractions(llmClient);
    }

    @Test
    void returns502WhenProviderFails() throws Exception {
        when(llmClient.generate(TEXT, ProcessingMode.SHORT_SUMMARY)).thenThrow(new LlmUnavailableException());
        var response = upload("test.txt", TEXT.getBytes(StandardCharsets.UTF_8), "SHORT_SUMMARY", "file");
        assertProblem(response, 502);
        assertThat(mapper.readTree(response.body()).path("detail").asText()).isEqualTo(
                "Сервис обработки текста временно недоступен. Повторите запрос позже.");
    }

    @Test
    void rejectsSeveralFiles() throws Exception {
        String body = "--boundary\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\n"
                + "SHORT_SUMMARY\r\n"
                + "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"one.txt\""
                + "\r\n\r\nFirst\r\n"
                + "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"two.txt\""
                + "\r\n\r\nSecond\r\n--boundary--\r\n";
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(response, 400);
        verifyNoInteractions(llmClient);
    }

    private void assertProblem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        var json = mapper.readTree(response.body());
        assertThat(json.path("type").asText()).isEqualTo("about:blank");
        assertThat(json.path("status").asInt()).isEqualTo(status);
        assertThat(json.path("instance").asText()).isEqualTo(PATH);
        assertThat(json.path("detail").asText()).isNotBlank();
    }

    private HttpResponse<String> upload(String name, byte[] bytes, String mode, String field) throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(("--boundary\r\nContent-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + name + "\"\r\nContent-Type: text/plain\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--boundary\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\n"
                + mode + "\r\n--boundary--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
