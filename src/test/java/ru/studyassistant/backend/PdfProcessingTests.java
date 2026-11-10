package ru.studyassistant.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.studyassistant.backend.llm.LlmUnavailableException;
import ru.studyassistant.backend.material.PdfProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.studyassistant.backend.llm.LlmClient;
import ru.studyassistant.backend.material.ProcessingMode;
import tools.jackson.databind.json.JsonMapper;

/** PDF читается настоящим PDFBox, внешний LLM заменён заглушкой. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=")
@ExtendWith(OutputCaptureExtension.class)
class PdfProcessingTests {
    private static final Path TEMP_ROOT = createTempRoot();

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("pdf-http-test-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void pdfSettings(DynamicPropertyRegistry registry) {
        registry.add("app.pdf.temp-directory", () -> TEMP_ROOT.toString());
    }

    @AfterEach
    void noTemporaryResourcesRemain() throws Exception {
        try (var files = Files.list(TEMP_ROOT)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @AfterAll
    static void removeTestDirectory() throws Exception {
        Files.delete(TEMP_ROOT);
    }

    @Autowired
    private PdfProperties pdfProperties;
    @Value("${local.server.port}")
    private int port;
    @MockitoBean
    private LlmClient llmClient;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @EnumSource(ProcessingMode.class)
    void processesRussianPdfInEveryMode(ProcessingMode mode) throws Exception {
        when(llmClient.generate(anyString(), eq(mode))).thenReturn("Результат " + mode);
        var response = upload("lecture.PDF", resource("russian.pdf"), mode);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = mapper.readTree(response.body());
        assertThat(json.path("mode").asText()).isEqualTo(mode.name());
        assertThat(json.path("result").asText()).isEqualTo("Результат " + mode);
        var text = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(text.capture(), eq(mode));
        assertThat(text.getValue().strip()).isEqualTo(
                "Митохондрии обеспечивают клетку энергией. Ёж изучает биологию.");
    }

    @Test
    void preservesPageOrder() throws Exception {
        when(llmClient.generate(anyString(), eq(ProcessingMode.SHORT_SUMMARY))).thenReturn("Ответ");
        assertThat(upload("pages.pdf", resource("multipage.pdf"), ProcessingMode.SHORT_SUMMARY)
                .statusCode()).isEqualTo(200);
        var text = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(text.capture(), eq(ProcessingMode.SHORT_SUMMARY));
        assertThat(text.getValue()).containsSubsequence("Страница 1", "Страница 2", "Страница 3");
    }

    @Test
    void checksContentInsteadOfTrustingFilename() throws Exception {
        assertProblem(upload("fake.pdf", "Обычный текст".getBytes(StandardCharsets.UTF_8),
                ProcessingMode.SHORT_SUMMARY), 415);
        assertProblem(upload("renamed.txt", resource("plain.pdf"), ProcessingMode.SHORT_SUMMARY), 415);
        assertProblem(upload("archive.txt", new byte[] {'P', 'K', 3, 4}, ProcessingMode.SHORT_SUMMARY), 415);
        verifyNoInteractions(llmClient);
    }

    @Test
    void rejectsScansPasswordsAndCorruption() throws Exception {
        assertProblem(upload("scan.pdf", generated(1, "", false), ProcessingMode.SHORT_SUMMARY), 422);
        assertProblem(upload("locked.pdf", generated(1, "Text", true), ProcessingMode.SHORT_SUMMARY), 422);
        assertProblem(upload("broken.pdf", "%PDF-1.7\nbroken".getBytes(StandardCharsets.US_ASCII),
                ProcessingMode.SHORT_SUMMARY), 422);
        verifyNoInteractions(llmClient);
    }

    @Test
    void rejectsPageAndTextLimitsWithoutTruncation() throws Exception {
        assertProblem(upload("pages.pdf", generated(51, "Text", false), ProcessingMode.SHORT_SUMMARY), 413);
        assertProblem(upload("long.pdf", generated(1, "x".repeat(10001), false),
                ProcessingMode.SHORT_SUMMARY), 413);
        verifyNoInteractions(llmClient);
    }

    @Test
    void acceptsExactly50Pages() throws Exception {
        when(llmClient.generate(anyString(), eq(ProcessingMode.SHORT_SUMMARY))).thenReturn("Ответ");
        assertThat(upload("pages.pdf", generated(50, "Text", false), ProcessingMode.SHORT_SUMMARY)
                .statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({"plain.pdf,Photosynthesis", "table.pdf,Earth", "formulas.pdf,E = mc²"})
    void processesDatasetDocuments(String filename, String marker) throws Exception {
        when(llmClient.generate(anyString(), eq(ProcessingMode.SHORT_SUMMARY))).thenReturn("Ответ");
        assertThat(upload(filename, resource(filename), ProcessingMode.SHORT_SUMMARY).statusCode()).isEqualTo(200);
        var text = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(text.capture(), eq(ProcessingMode.SHORT_SUMMARY));
        assertThat(text.getValue()).contains(marker);
        if (filename.equals("table.pdf")) {
            assertThat(text.getValue()).containsSubsequence("Planet", "Moons", "Earth", "1", "Mars", "2");
        }
        if (filename.equals("formulas.pdf")) {
            assertThat(text.getValue()).contains("a² + b² = c²", "α + β = γ");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"scan.pdf", "password.pdf", "corrupted.pdf"})
    void rejectsDatasetDocumentsBeforeLlm(String filename, CapturedOutput output) throws Exception {
        assertProblem(upload(filename, resource(filename), ProcessingMode.SHORT_SUMMARY), 422);
        verifyNoInteractions(llmClient);
        assertThat(output.getAll()).doesNotContain("Intentionally corrupted synthetic fixture", "fixture-password");
    }

    @Test
    void pdfLlmFailureIs502() throws Exception {
        when(llmClient.generate(anyString(), eq(ProcessingMode.SHORT_SUMMARY)))
                .thenThrow(new LlmUnavailableException());
        assertProblem(upload("russian.pdf", resource("russian.pdf"), ProcessingMode.SHORT_SUMMARY), 502);
    }

    @Test
    void configuredPageLimitIsUsed() throws Exception {
        int original = pdfProperties.getMaxPages();
        try {
            pdfProperties.setMaxPages(1);
            assertProblem(upload("pages.pdf", resource("multipage.pdf"), ProcessingMode.SHORT_SUMMARY), 413);
            verifyNoInteractions(llmClient);
        } finally {
            pdfProperties.setMaxPages(original);
        }
    }

    @Test
    void configuredTimeoutReturns504AndServerKeepsWorking() throws Exception {
        var original = pdfProperties.getTimeout();
        try {
            pdfProperties.setTimeout(java.time.Duration.ofMillis(1));
            assertProblem(upload("russian.pdf", resource("russian.pdf"), ProcessingMode.SHORT_SUMMARY), 504);
            verifyNoInteractions(llmClient);
        } finally {
            pdfProperties.setTimeout(original);
        }
        when(llmClient.generate(anyString(), eq(ProcessingMode.SHORT_SUMMARY))).thenReturn("Ответ");
        assertThat(upload("russian.pdf", resource("russian.pdf"), ProcessingMode.SHORT_SUMMARY)
                .statusCode()).isEqualTo(200);
    }

    @Test
    void doesNotLogFileContents(CapturedOutput output) throws Exception {
        when(llmClient.generate(anyString(), eq(ProcessingMode.SHORT_SUMMARY))).thenReturn("Ответ");
        assertThat(upload("russian.pdf", resource("russian.pdf"), ProcessingMode.SHORT_SUMMARY)
                .statusCode()).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain("Ёж изучает биологию", "Митохондрии обеспечивают");
    }

    private byte[] generated(int pages, String text, boolean encrypted) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            if (!text.isEmpty()) {
                try (var content = new PDPageContentStream(document, document.getPage(0))) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(30, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            if (encrypted) {
                document.protect(new StandardProtectionPolicy("owner", "password", new AccessPermission()));
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/pdf/" + name)) {
            return input.readAllBytes();
        }
    }

    private void assertProblem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        var json = mapper.readTree(response.body());
        assertThat(json.path("type").asText()).isEqualTo("about:blank");
        assertThat(json.path("status").asInt()).isEqualTo(status);
        assertThat(json.path("detail").asText()).isNotBlank();
        assertThat(json.path("instance").asText()).isEqualTo("/api/v1/materials/process");
        if (status == 422) {
            assertThat(json.path("title").asText()).isEqualTo("Unprocessable Entity");
        }
    }

    private HttpResponse<String> upload(String filename, byte[] bytes, ProcessingMode mode) throws Exception {
        var body = new ByteArrayOutputStream();
        // MIME намеренно не PDF: сервер должен проверить сами байты.
        body.write(("--pdf-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--pdf-boundary\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\n"
                + mode + "\r\n--pdf-boundary--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/materials/process"))
                .header("Content-Type", "multipart/form-data; boundary=pdf-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
