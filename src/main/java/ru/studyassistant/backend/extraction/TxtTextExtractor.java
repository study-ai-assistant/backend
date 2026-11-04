package ru.studyassistant.backend.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Читает UTF-8 строго, затем пробует Windows-1251 без подмены повреждённых символов. */
@Component
public class TxtTextExtractor implements TextExtractor {
    @Override
    public String extract(InputStream input, String filename) throws IOException {
        byte[] bytes = input.readAllBytes();
        String text;
        try {
            text = decode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException exception) {
            text = decode(bytes, Charset.forName("windows-1251"));
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
