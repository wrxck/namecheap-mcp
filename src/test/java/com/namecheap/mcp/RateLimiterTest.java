package com.namecheap.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsRequestsWithinLimit() {
        var limiter = new RateLimiter(5, 100);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 5; i++) {
                limiter.checkAndRecord();
            }
        });
    }

    @Test
    void blocksWhenMinuteLimitExceeded() {
        var limiter = new RateLimiter(3, 100);
        limiter.checkAndRecord();
        limiter.checkAndRecord();
        limiter.checkAndRecord();

        var ex = assertThrows(RateLimiter.RateLimitExceededException.class, limiter::checkAndRecord);
        assertTrue(ex.getMessage().contains("last minute"));
    }

    @Test
    void blocksWhenHourLimitExceeded() {
        var limiter = new RateLimiter(1000, 3);
        limiter.checkAndRecord();
        limiter.checkAndRecord();
        limiter.checkAndRecord();

        var ex = assertThrows(RateLimiter.RateLimitExceededException.class, limiter::checkAndRecord);
        assertTrue(ex.getMessage().contains("last hour"));
    }

    @Test
    void tracksRequestCounts() {
        var limiter = new RateLimiter();
        assertEquals(0, limiter.getRequestCountLastMinute());
        assertEquals(0, limiter.getRequestCountLastHour());

        limiter.checkAndRecord();
        limiter.checkAndRecord();

        assertEquals(2, limiter.getRequestCountLastMinute());
        assertEquals(2, limiter.getRequestCountLastHour());
    }
}
