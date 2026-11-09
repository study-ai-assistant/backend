package ru.studyassistant.backend.llm;

/** Ошибка обращения к LLM, преобразуется обработчиком в HTTP 502. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException() {
        this(Reason.UNAVAILABLE);
    }

    public LlmUnavailableException(Reason reason) {
        super(reason.detail);
    }

    public enum Reason {
        RATE_LIMIT("Лимит сервиса обработки текста исчерпан. Повторите запрос позже."),
        TIMEOUT("Сервис обработки текста не ответил вовремя. Повторите запрос."),
        UNAVAILABLE("Сервис обработки текста временно недоступен. Повторите запрос позже.");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }
    }
}
