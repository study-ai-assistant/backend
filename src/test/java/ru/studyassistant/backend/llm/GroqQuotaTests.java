package ru.studyassistant.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class GroqQuotaTests {
    @Test
    void synchronousCallDoesNotWaitBehindBackgroundWork() {
        var settings = new QuotaProperties();
        settings.setTokensPerMinute(100);
        var quota = new GroqQuota(settings);
        quota.acquire(90);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofMillis(300), () ->
                assertThatThrownBy(() -> quota.acquire(20, false)).isInstanceOf(LlmUnavailableException.class));
    }

    @Test
    void reconcilesDailyReservationWithActualUsage() {
        var settings = new QuotaProperties();
        settings.setTokensPerDay(100);
        var quota = new GroqQuota(settings);
        var ticket = quota.acquire(80);
        quota.complete(ticket, new LlmClient.Usage(20, 10));
        quota.acquire(60);
        assertThatThrownBy(() -> quota.acquire(11)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void waitsForRollingWindowAcrossConcurrentRequests() throws Exception {
        var settings = new QuotaProperties();
        settings.setTokensPerMinute(100);
        settings.setMinuteWindow(Duration.ofMillis(120));
        var quota = new GroqQuota(settings);
        quota.acquire(70);
        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> quota.acquire(70));
            Thread.sleep(20);
            assertThat(result.isDone()).isFalse();
            result.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(90));
    }

    @Test
    void respectsDailyBudgetAndSingleRequestSize() {
        var settings = new QuotaProperties();
        settings.setTokensPerMinute(100);
        settings.setTokensPerDay(100);
        var quota = new GroqQuota(settings);
        quota.acquire(70);
        assertThatThrownBy(() -> quota.acquire(31)).isInstanceOf(LlmUnavailableException.class);
        assertThatThrownBy(() -> quota.acquire(101)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void countsRequestsAsWellAsTokens() throws Exception {
        var settings = new QuotaProperties();
        settings.setRequestsPerMinute(1);
        settings.setMinuteWindow(Duration.ofMillis(80));
        var quota = new GroqQuota(settings);
        quota.acquire(1);
        long start = System.nanoTime();
        quota.acquire(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(60));
    }
}
