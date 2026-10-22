package ru.studyassistant.backend.extraction;

import java.io.IOException;
import java.io.InputStream;

/** Точка расширения для TXT, DOCX и PDF. Потоком управляет вызывающий код. */
public interface TextExtractor {
    String extract(InputStream input, String filename) throws IOException;
}
