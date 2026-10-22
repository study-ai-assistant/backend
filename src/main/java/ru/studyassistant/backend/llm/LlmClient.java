package ru.studyassistant.backend.llm;

import ru.studyassistant.backend.material.ProcessingMode;

/** Реальный HTTP-адаптер добавляется после выбора провайдера. */
public interface LlmClient {
    String generate(String text, ProcessingMode mode);
}
