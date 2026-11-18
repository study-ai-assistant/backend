package ru.studyassistant.backend.llm;

/** Ошибка обращения к LLM, преобразуется обработчиком в HTTP 502. */
public class LlmUnavailableException extends RuntimeException {

    private final Reason reason;
    private final LlmClient.Usage usage;
    public LlmClient.Usage getUsage() { return usage; }

    public Reason getReason() { return reason; }

    public LlmUnavailableException() {
        this(Reason.UNAVAILABLE);
    }

    public LlmUnavailableException(Reason reason) { this(reason, null); }

    public LlmUnavailableException(Reason reason, LlmClient.Usage usage) {
        super(reason.detail);
        this.reason = reason;
        this.usage = usage;
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
