package ru.studyassistant.backend.jobs;

import java.time.Instant;
import ru.studyassistant.backend.material.ProcessingMode;

public record JobView(String id, Status status, ProcessingMode mode, int completedParts, int totalParts,
                      String stage, Instant createdAt, Instant expiresAt, String result, Failure error,
                      long promptTokens, long completionTokens, long elapsedMillis) {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public record Failure(int status, String code, String detail, Integer part) { }
}
