package com.namecheap.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class RateLimiter {

    private final int maxPerMinute;
    private final int maxPerHour;
    private final ConcurrentLinkedDeque<Instant> timestamps = new ConcurrentLinkedDeque<>();

    RateLimiter(int maxPerMinute, int maxPerHour) {
        this.maxPerMinute = maxPerMinute;
        this.maxPerHour = maxPerHour;
    }

    RateLimiter() {
        this(20, 700);
    }

    void checkAndRecord() {
        Instant now = Instant.now();
        purgeOld(now);

        long lastMinute = timestamps.stream()
                .filter(t -> Duration.between(t, now).toSeconds() < 60)
                .count();
        if (lastMinute >= maxPerMinute) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded: %d requests in the last minute (max %d). Wait before retrying."
                            .formatted(lastMinute, maxPerMinute));
        }

        long lastHour = timestamps.size();
        if (lastHour >= maxPerHour) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded: %d requests in the last hour (max %d). Wait before retrying."
                            .formatted(lastHour, maxPerHour));
        }

        timestamps.addLast(now);
    }

    private void purgeOld(Instant now) {
        while (!timestamps.isEmpty()) {
            Instant oldest = timestamps.peekFirst();
            if (oldest != null && Duration.between(oldest, now).toSeconds() >= 3600) {
                timestamps.pollFirst();
            } else {
                break;
            }
        }
    }

    int getRequestCountLastMinute() {
        Instant now = Instant.now();
        purgeOld(now);
        return (int) timestamps.stream()
                .filter(t -> Duration.between(t, now).toSeconds() < 60)
                .count();
    }

    int getRequestCountLastHour() {
        Instant now = Instant.now();
        purgeOld(now);
        return timestamps.size();
    }

    public static final class RateLimitExceededException extends RuntimeException {
        RateLimitExceededException(String message) {
            super(message);
        }
    }
}
