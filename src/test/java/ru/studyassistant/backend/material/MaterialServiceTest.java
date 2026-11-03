package ru.studyassistant.backend.material;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.extraction.TextExtractor;
import ru.studyassistant.backend.llm.LlmClient;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialServiceTest {

    private final TextExtractor textExtractor = mock(TextExtractor.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final MaterialService service = new MaterialService(textExtractor, llmClient);

    @Test
    void shouldProcessValidUtf8File() throws Exception {
        String content = "Привет, мир!";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", content.getBytes("UTF-8")
        );

        when(textExtractor.extract(any(InputStream.class), eq("test.txt"))).thenReturn(content);
        when(llmClient.generate(content, ProcessingMode.SHORT_SUMMARY)).thenReturn("Краткий итог");

        ProcessingResult result = service.process(file, ProcessingMode.SHORT_SUMMARY);

        assertEquals(ProcessingMode.SHORT_SUMMARY, result.mode());
        assertEquals("Краткий итог", result.result());
    }

    @Test
    void shouldProcessValidWindows1251File() throws Exception {
        String content = "Тест в виндовой кодировке";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", content.getBytes("windows-1251")
        );

        when(textExtractor.extract(any(InputStream.class), eq("test.txt"))).thenReturn(content);
        when(llmClient.generate(content, ProcessingMode.DETAILED_SUMMARY)).thenReturn("Подробный итог");

        ProcessingResult result = service.process(file, ProcessingMode.DETAILED_SUMMARY);

        assertEquals(ProcessingMode.DETAILED_SUMMARY, result.mode());
        assertEquals("Подробный итог", result.result());
    }

    @Test
    void shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", new byte[0]);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            service.process(file, ProcessingMode.SHORT_SUMMARY);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("пуст"));
    }

    @Test
    void shouldRejectTooLargeFile() {
        byte[] largeContent = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", largeContent);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            service.process(file, ProcessingMode.SHORT_SUMMARY);
        });

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.getStatusCode());
        assertTrue(exception.getReason().contains("10 МБ"));
    }

    @Test
    void shouldRejectNonTxtFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", "fake pdf content".getBytes()
        );

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            service.process(file, ProcessingMode.SHORT_SUMMARY);
        });

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatusCode());
        assertTrue(exception.getReason().contains("TXT"));
    }
}
