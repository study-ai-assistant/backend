package ru.studyassistant.backend.extraction;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
public class TxtTextExtractor implements TextExtractor {

    @Override
    public String extract(InputStream input, String filename) throws IOException {
        byte[] bytes = input.readAllBytes();

        // Сначала пробуем UTF-8
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Если не получилось, пробуем Windows-1251
            try {
                return new String(bytes, Charset.forName("windows-1251"));
            } catch (Exception ex) {
                throw new IOException("Не удалось декодировать файл. Поддерживаются UTF-8 и Windows-1251");
            }
        }
    }
}
