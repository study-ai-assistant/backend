package ru.studyassistant.backend.llm;

/** Ошибка обращения к LLM, преобразуется обработчиком в HTTP 502. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException() {
        super("Сервис обработки текста временно недоступен. Повторите запрос позже.");
    }
}
