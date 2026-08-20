package dev.terata.mctunnel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeartbeatTrackerTest {
    @Test
    void reportsUnknownUntilAHeartbeatCompletes() {
        HeartbeatTracker tracker = new HeartbeatTracker(20);
        tracker.recordSent(1L, 100L);

        assertEquals(-1, tracker.lossPercent());
        assertEquals(0, tracker.completedSamples());
        assertEquals(1, tracker.pendingSamples());
    }

    @Test
    void recordsSuccessfulRoundTripAndIgnoresDuplicatePong() {
        HeartbeatTracker tracker = new HeartbeatTracker(20);
        tracker.recordSent(5L, 100L);

        assertEquals(40L, tracker.recordPong(5L, 140L));
        assertEquals(-1L, tracker.recordPong(5L, 150L));
        assertEquals(0, tracker.lossPercent());
        assertEquals(1, tracker.completedSamples());
    }

    @Test
    void expiresMissingPongsAndIgnoresLateResponse() {
        HeartbeatTracker tracker = new HeartbeatTracker(20);
        tracker.recordSent(7L, 100L);
        tracker.expire(199L, 100L);
        assertEquals(-1, tracker.lossPercent());

        tracker.expire(200L, 100L);
        assertEquals(100, tracker.lossPercent());
        assertEquals(-1L, tracker.recordPong(7L, 210L));
    }

    @Test
    void handlesOutOfOrderPongs() {
        HeartbeatTracker tracker = new HeartbeatTracker(20);
        tracker.recordSent(1L, 100L);
        tracker.recordSent(2L, 110L);

        assertEquals(20L, tracker.recordPong(2L, 130L));
        assertEquals(40L, tracker.recordPong(1L, 140L));
        assertEquals(0, tracker.lossPercent());
    }

    @Test
    void keepsOnlyTheConfiguredRollingWindow() {
        HeartbeatTracker tracker = new HeartbeatTracker(3);
        for (long token = 1; token <= 3; token++) {
            tracker.recordSent(token, token * 10L);
            tracker.expire(token * 10L + 100L, 100L);
        }
        assertEquals(100, tracker.lossPercent());

        tracker.recordSent(4L, 40L);
        tracker.recordPong(4L, 50L);
        assertEquals(67, tracker.lossPercent());
        assertEquals(3, tracker.completedSamples());
    }

    @Test
    void resetClearsPendingAndCompletedSamples() {
        HeartbeatTracker tracker = new HeartbeatTracker(20);
        tracker.recordSent(1L, 1L);
        tracker.expire(2L, 1L);
        tracker.reset();

        assertEquals(-1, tracker.lossPercent());
        assertEquals(0, tracker.completedSamples());
        assertEquals(0, tracker.pendingSamples());
    }
}
