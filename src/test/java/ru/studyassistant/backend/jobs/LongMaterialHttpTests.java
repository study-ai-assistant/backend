package ru.studyassistant.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ru.studyassistant.backend.llm.LlmClient;
import ru.studyassistant.backend.llm.LlmUnavailableException;
import ru.studyassistant.backend.material.ProcessingMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=")
class LongMaterialHttpTests {
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private Stub llm;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String LONG = "Учебный материал объясняет устройство алгоритмов. ".repeat(300);

    @BeforeEach
    void reset() { llm.reset(); }

    @ParameterizedTest
    @EnumSource(ProcessingMode.class)
    void completesChunksAndMergeInEveryMode(ProcessingMode mode) throws Exception {
        var response = text(LONG, mode.name());
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("Retry-After")).contains("3");
        JsonNode result = await(response);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("completedParts").asInt()).isEqualTo(result.path("totalParts").asInt());
        assertThat(llm.chunks.get()).isEqualTo(result.path("totalParts").asInt());
        assertThat(llm.merges.get()).isEqualTo(1);
        assertThat(llm.mergeInput).contains("Часть 1:", "Ответ части 1", "Часть 2:", "Ответ части 2");
        assertThat(llm.mergeInput.indexOf("Часть 1:")).isLessThan(llm.mergeInput.indexOf("Часть 2:"));
        assertThat(result.path("result").asText()).isEqualTo("Итог " + mode);
        assertThat(result.path("promptTokens").asLong()).isPositive();
    }

    @ParameterizedTest
    @MethodSource("longFileModes")
    void acceptsLongFiles(String extension, ProcessingMode mode) throws Exception {
        byte[] bytes = extension.equals("txt") ? LONG.getBytes(StandardCharsets.UTF_8) : pdf();
        JsonNode result = await(file(bytes, extension, mode.name()));
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("mode").asText()).isEqualTo(mode.name());
        assertThat(llm.chunks.get()).isGreaterThan(1);
    }

    static Stream<Arguments> longFileModes() {
        return Stream.of("txt", "pdf").flatMap(extension ->
                Stream.of(ProcessingMode.values()).map(mode -> Arguments.of(extension, mode)));
    }

    @Test
    void exposesProgressAndProcessesJobsSequentially() throws Exception {
        llm.blockAt = 1;
        var first = text(LONG, "SHORT_SUMMARY");
        assertThat(llm.entered.await(5, TimeUnit.SECONDS)).isTrue();
        llm.blockAt = 2;
        llm.release.countDown();
        assertThat(llm.secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode progress = get(location(first));
        assertThat(progress.path("status").asText()).isEqualTo("RUNNING");
        assertThat(progress.path("completedParts").asInt()).isEqualTo(1);
        var second = text(LONG, "KEY_TERMS");
        assertThat(get(location(second)).path("status").asText()).isEqualTo("QUEUED");
        llm.secondRelease.countDown();
        assertThat(await(first).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(await(second).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "RATE_LIMIT", "TIMEOUT"})
    void returnsSafeErrorAndStopsAfterFailedChunk(String reason) throws Exception {
        llm.failAt = 2;
        llm.reason = LlmUnavailableException.Reason.valueOf(reason);
        JsonNode result = await(text(LONG, "DETAILED_SUMMARY"));
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("error").path("part").asInt()).isEqualTo(2);
        assertThat(result.path("error").path("code").asText()).isEqualTo("LLM_" + reason);
        assertThat(llm.chunks.get()).isEqualTo(2);
        assertThat(llm.merges.get()).isZero();
        assertThat(result.path("result").isNull()).isTrue();
    }

    @Test
    void mergeFailureDoesNotExposePartialResult() throws Exception {
        llm.failMerge = true;
        JsonNode result = await(text(LONG, "KEY_TERMS"));
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("error").path("part").isNull()).isTrue();
    }

    @Test
    void rejectsCharacterAndTokenLimitsForJsonTxtAndPdf() throws Exception {
        assertThat(text("a".repeat(24001), "SHORT_SUMMARY").statusCode()).isEqualTo(413);
        assertThat(text("😀".repeat(10001), "SHORT_SUMMARY").statusCode()).isEqualTo(413);
        // 22000 ASCII символов: оценка UTF-8/4 проходит, настоящий o200k — уже нет.
        assertThat(text("a ".repeat(11000), "SHORT_SUMMARY").statusCode()).isEqualTo(413);
        assertThat(file("a ".repeat(11000).getBytes(StandardCharsets.UTF_8), "txt").statusCode()).isEqualTo(413);
        assertThat(file(pdf(22, "a ".repeat(500)), "pdf").statusCode()).isEqualTo(413);
        assertThat(file("a".repeat(24001).getBytes(StandardCharsets.UTF_8), "txt").statusCode()).isEqualTo(413);
        assertThat(file(pdf(26), "pdf").statusCode()).isEqualTo(413);
        assertThat(llm.chunks.get()).isZero();
    }

    @Test
    void routesTokenDenseTextToBackgroundEvenBelowCharacterThreshold() throws Exception {
        JsonNode result = await(text("a ".repeat(4900), "SHORT_SUMMARY"));
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("totalParts").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void shortMaterialsKeepSynchronousRoutes() throws Exception {
        assertThat(text("Короткий текст", "SHORT_SUMMARY").statusCode()).isEqualTo(400);
        var request = HttpRequest.newBuilder(uri("/api/v1/text/process"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"Текст\",\"mode\":\"SHORT_SUMMARY\"}"));
        assertThat(send(request).statusCode()).isEqualTo(200);
        assertThat(llm.chunks.get()).isZero();
        assertThat(llm.singles.get()).isEqualTo(1);
    }

    @Test
    void unknownJobAndInvalidInputFollowContract() throws Exception {
        assertThat(send(HttpRequest.newBuilder(uri("/api/v1/jobs/missing")).GET()).statusCode()).isEqualTo(404);
        assertThat(text("text", "UNKNOWN").statusCode()).isEqualTo(400);
        assertThat(text(" ", "SHORT_SUMMARY").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> text(String text, String mode) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("text", text, "mode", mode));
        return send(HttpRequest.newBuilder(uri("/api/v1/jobs/text")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> file(byte[] data, String extension) throws Exception {
        return file(data, extension, "SHORT_SUMMARY");
    }

    private HttpResponse<String> file(byte[] data, String extension, String mode) throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(("--testboundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"long."
                + extension + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--testboundary\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\n"
                + mode + "\r\n--testboundary--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(uri("/api/v1/jobs/materials"))
                .header("Content-Type", "multipart/form-data; boundary=testboundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    private byte[] pdf() throws Exception { return pdf(12); }
    private byte[] pdf(int pages) throws Exception {
        return pdf(pages, "Algorithms preserve order and process data. ".repeat(24));
    }
    private byte[] pdf(int pages, String line) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(30, 700);
                    content.showText(line); content.endText();
                }
            }
            doc.save(out); return out.toByteArray();
        }
    }

    private JsonNode await(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(202);
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        do {
            JsonNode status = get(location(response));
            if (status.path("status").asText().matches("SUCCEEDED|FAILED")) { return status; }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Job did not finish");
    }
    private String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElseThrow();
    }
    private JsonNode get(String path) throws Exception {
        var response = send(HttpRequest.newBuilder(uri(path)).GET());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary Stub stub() { return new Stub(); }
    }
    static class Stub implements LlmClient {
        AtomicInteger chunks = new AtomicInteger();
        AtomicInteger merges = new AtomicInteger();
        AtomicInteger singles = new AtomicInteger();
        volatile int failAt;
        volatile int blockAt;
        volatile boolean failMerge;
        volatile String mergeInput;
        volatile LlmUnavailableException.Reason reason;
        CountDownLatch entered, release, secondEntered, secondRelease;
        void reset() {
            chunks.set(0); merges.set(0); singles.set(0); failAt = 0; blockAt = 0; failMerge = false;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            secondEntered = new CountDownLatch(1); secondRelease = new CountDownLatch(1);
        }
        public String generate(String text, ProcessingMode mode) {
            singles.incrementAndGet(); return "Краткий результат";
        }
        public String generateChunk(String text, ProcessingMode mode) {
            int n = chunks.incrementAndGet();
            try {
                if (n == 1 && blockAt == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS); }
                if (n == 2 && blockAt == 2) { secondEntered.countDown(); secondRelease.await(5, TimeUnit.SECONDS); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            if (n == failAt) { throw new LlmUnavailableException(reason); }
            return "Ответ части " + n;
        }
        public String mergeResults(String text, ProcessingMode mode) {
            merges.incrementAndGet();
            mergeInput = text;
            if (failMerge) { throw new LlmUnavailableException(); }
            return "Итог " + mode;
        }
        public Usage lastUsage() { return new Usage(10, 5); }
    }
}
