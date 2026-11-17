package ru.studyassistant.backend.jobs;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.llm.LlmClient;
import ru.studyassistant.backend.llm.LlmUnavailableException;
import ru.studyassistant.backend.material.ProcessingMode;
import ru.studyassistant.backend.material.TextSplitter;

/** Одна ограниченная очередь; данные существуют только до TTL или перезапуска. */
@Service
public class JobService {
    private final Map<String, Job> jobs = new HashMap<>();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("material-jobs").factory());
    private final java.util.concurrent.ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("job-cleanup").factory());
    private final JobProperties properties;
    private final TextSplitter splitter;
    private final LlmClient llm;
    private boolean closed;

    public JobService(JobProperties properties, TextSplitter splitter, LlmClient llm) {
        this.properties = properties;
        this.splitter = splitter;
        this.llm = llm;
        cleaner.scheduleWithFixedDelay(this::cleanup, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized JobView submit(String text, ProcessingMode mode) {
        if (text == null || text.isBlank() || mode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Передайте непустой text и корректный mode.");
        }
        List<String> parts;
        try {
            parts = splitter.split(text);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, exception.getMessage());
        }
        if (parts.size() == 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Короткий материал отправьте через /api/v1/text/process или /api/v1/materials/process.");
        }
        cleanup();
        if (closed || jobs.size() >= properties.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Очередь заполнена. Повторите запрос после удаления старых заданий.");
        }
        var job = new Job(mode, parts.size());
        jobs.put(job.id, job);
        worker.submit(() -> run(job, parts));
        synchronized (job) { return job.view(); }
    }

    public synchronized JobView get(String id) {
        cleanup();
        Job job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Задание не найдено или срок хранения истёк. Отправьте материал заново.");
        }
        synchronized (job) { return job.view(); }
    }

    private void run(Job job, List<String> parts) {
        synchronized (job) { job.status = JobView.Status.RUNNING; job.stage = "CHUNKS"; }
        int part = 0;
        try {
            var results = new ArrayList<String>();
            for (String text : parts) {
                part++;
                if (Thread.currentThread().isInterrupted()) { throw new IllegalStateException("shutdown"); }
                String answer = llm.generateChunk(text, job.mode);
                requireAnswer(answer);
                results.add("Часть " + part + ":\n" + answer);
                synchronized (job) { job.completed = part; job.addUsage(llm.lastUsage()); }
            }
            synchronized (job) { job.stage = "MERGE"; }
            String answer = llm.mergeResults(String.join("\n\n", results), job.mode);
            requireAnswer(answer);
            synchronized (job) {
                job.addUsage(llm.lastUsage());
                job.result = answer;
                job.status = JobView.Status.SUCCEEDED;
                job.finish(properties.getRetention());
            }
        } catch (LlmUnavailableException exception) {
            String code = exception.getReason() == LlmUnavailableException.Reason.RATE_LIMIT
                    ? "LLM_RATE_LIMIT" : "LLM_" + exception.getReason();
            synchronized (job) {
                job.addUsage(llm.lastUsage());
                job.error = new JobView.Failure(502, code, exception.getMessage(),
                        part <= parts.size() && job.completed < parts.size() ? part : null);
                job.status = JobView.Status.FAILED;
                job.finish(properties.getRetention());
            }
        } catch (RuntimeException exception) {
            synchronized (job) {
                job.error = new JobView.Failure(502, "PROCESSING_FAILED",
                        "Не удалось обработать часть или объединить результаты. Отправьте материал заново.",
                        job.completed < parts.size() ? part : null);
                job.status = JobView.Status.FAILED;
                job.finish(properties.getRetention());
            }
        }
    }

    private void requireAnswer(String answer) {
        if (answer == null || answer.isBlank()) { throw new IllegalStateException("Empty LLM result"); }
    }

    private synchronized void cleanup() {
        Instant now = Instant.now();
        jobs.values().removeIf(job -> {
            synchronized (job) { return job.expires != null && !now.isBefore(job.expires); }
        });
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        cleaner.shutdownNow();
        worker.shutdownNow();
        jobs.clear();
    }

    private static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final ProcessingMode mode;
        private final int total;
        private final Instant created = Instant.now();
        private JobView.Status status = JobView.Status.QUEUED;
        private String stage = "QUEUED";
        private int completed;
        private String result;
        private JobView.Failure error;
        private Instant finished;
        private Instant expires;
        private long promptTokens;
        private long completionTokens;
        private Job(ProcessingMode mode, int total) { this.mode = mode; this.total = total; }
        private void addUsage(LlmClient.Usage usage) {
            if (usage != null) { promptTokens += usage.promptTokens(); completionTokens += usage.completionTokens(); }
        }
        private void finish(java.time.Duration retention) {
            finished = Instant.now();
            expires = finished.plus(retention);
            stage = "FINISHED";
        }
        private JobView view() {
            return new JobView(id, status, mode, completed, total, stage, created, expires, result, error,
                    promptTokens, completionTokens,
                    java.time.Duration.between(created, finished == null ? Instant.now() : finished).toMillis());
        }
    }
}
