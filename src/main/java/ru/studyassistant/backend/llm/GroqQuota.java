package ru.studyassistant.backend.llm;

import java.util.ArrayDeque;
import org.springframework.stereotype.Component;

/** Скользящие окна и общий резерв на вход+выход для всех запросов одной JVM. */
@Component
public class GroqQuota {
    private final QuotaProperties properties;
    private final ArrayDeque<Reservation> reservations = new ArrayDeque<>();
    public GroqQuota(QuotaProperties properties) { this.properties = properties; }

    public Reservation acquire(int tokens) { return acquire(tokens, true); }

    public synchronized Reservation acquire(int tokens, boolean waitForMinute) {
        if (tokens > properties.getTokensPerMinute()) { throw limited(); }
        while (true) {
            long now = System.nanoTime();
            long day = properties.getDayWindow().toNanos();
            long minute = properties.getMinuteWindow().toNanos();
            while (!reservations.isEmpty() && now - reservations.peekFirst().at >= day) {
                reservations.removeFirst();
            }
            long dayTokens = 0;
            long minuteTokens = 0;
            int minuteRequests = 0;
            long waitNanos = minute;
            for (Reservation item : reservations) {
                dayTokens += item.dayTokens;
                if (now - item.at < minute) {
                    minuteTokens += item.tokens;
                    minuteRequests++;
                    waitNanos = Math.min(waitNanos, minute - (now - item.at));
                }
            }
            if (dayTokens + tokens > properties.getTokensPerDay()
                    || reservations.size() >= properties.getRequestsPerDay()) { throw limited(); }
            if (minuteTokens + tokens <= properties.getTokensPerMinute()
                    && minuteRequests < properties.getRequestsPerMinute()) {
                var reservation = new Reservation(now, tokens);
                reservations.addLast(reservation);
                return reservation;
            }
            if (!waitForMinute) { throw limited(); }
            try { wait(Math.max(1, (waitNanos + 999999) / 1000000)); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LlmUnavailableException();
            }
        }
    }

    private LlmUnavailableException limited() {
        return new LlmUnavailableException(LlmUnavailableException.Reason.RATE_LIMIT);
    }
    public synchronized void complete(Reservation reservation, LlmClient.Usage usage) {
        if (usage != null) {
            // Минутный резерв не возвращаем: провайдер мог учитывать max_completion_tokens.
            reservation.dayTokens = Math.max(0, usage.promptTokens() + usage.completionTokens());
        }
    }

    public static final class Reservation {
        private final long at;
        private final int tokens;
        private long dayTokens;
        private Reservation(long at, int tokens) { this.at = at; this.tokens = tokens; this.dayTokens = tokens; }
    }
}
