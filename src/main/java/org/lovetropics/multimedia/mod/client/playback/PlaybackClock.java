package org.lovetropics.multimedia.mod.client.playback;

import net.minecraft.Util;

public class PlaybackClock {
    private static final long TIME_NOT_SET = -1;
    private static final double SECONDS_TO_NANOS = 1000_000_000.0;

    private final PlaybackSyncType syncType;

    private volatile long startedAt = TIME_NOT_SET;
    private volatile long pausedAt = TIME_NOT_SET;

    public PlaybackClock(final PlaybackSyncType syncType) {
        this.syncType = syncType;
    }

    public synchronized void play() {
        if (startedAt == TIME_NOT_SET) {
            startedAt = Util.getNanos();
        } else if (pausedAt != TIME_NOT_SET) {
            final long elapsedNanos = pausedAt - startedAt;
            startedAt = Util.getNanos() - elapsedNanos;
            pausedAt = TIME_NOT_SET;
        }
    }

    public synchronized void pause() {
        if (pausedAt == TIME_NOT_SET) {
            pausedAt = getCurrentTimestamp();
        }
    }

    public synchronized void setElapsedTime(final double elapsedTime) {
        final boolean wasPaused = isPaused();
        final long currentTimestamp = Util.getNanos();
        startedAt = (long) Math.floor(currentTimestamp - elapsedTime * SECONDS_TO_NANOS);
        if (wasPaused) {
            pausedAt = currentTimestamp;
        }
    }

    public synchronized boolean requestSyncTo(final double elapsedTime) {
        if (syncType != PlaybackSyncType.WALL_TIME) {
            setElapsedTime(elapsedTime);
            return true;
        }
        return false;
    }

    private long getCurrentTimestamp() {
        return pausedAt != TIME_NOT_SET ? pausedAt : Util.getNanos();
    }

    public synchronized double getElapsedTime() {
        if (startedAt == TIME_NOT_SET) {
            return 0.0;
        }
        return (getCurrentTimestamp() - startedAt) / SECONDS_TO_NANOS;
    }

    public synchronized boolean isPaused() {
        return startedAt == TIME_NOT_SET || pausedAt != TIME_NOT_SET;
    }
}
