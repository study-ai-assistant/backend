package ru.studyassistant.backend.llm;

import ru.studyassistant.backend.material.ProcessingMode;

/** Обработка целого текста, частей и итогового объединения через провайдера. */
public interface LlmClient {
    String generate(String text, ProcessingMode mode);

    default String generateChunk(String text, ProcessingMode mode) {
        return generate(text, mode);
    }

    default String mergeResults(String text, ProcessingMode mode) {
        return generate(text, mode);
    }

    default Usage lastUsage() { return null; }

    record Usage(long promptTokens, long completionTokens) { }

}
