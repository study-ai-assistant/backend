package ru.studyassistant.backend.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/** Обычный словарь GPT-OSS; служебный формат сообщения резервируется отдельно. */
public final class TokenCounter {
    private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    private TokenCounter() { }
    public static int count(String text) { return ENCODING.countTokensOrdinary(text); }
}
