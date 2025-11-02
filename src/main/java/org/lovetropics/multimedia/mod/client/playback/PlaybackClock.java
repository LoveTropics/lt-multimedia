package org.lovetropics.multimedia.mod.client.playback;

import net.minecraft.Util;
import net.minecraft.util.Mth;

public class PlaybackClock {
    private static final long TIME_NOT_SET = -1;
    private static final double SECONDS_TO_NANOS = 1000_000_000.0;

    private final PlaybackSyncType syncType;

    private volatile long startedAt = TIME_NOT_SET;
    private volatile long pausedAt = TIME_NOT_SET;

    public PlaybackClock(final PlaybackSyncType syncType) {
        this.syncType = syncType;
    }

    public PlaybackSyncType syncType() {
        return syncType;
    }

    public synchronized void play() {
        if (startedAt == TIME_NOT_SET) {
            startedAt = Util.getNanos();
        } else if (pausedAt != TIME_NOT_SET) {
            final long elapsedMillis = pausedAt - startedAt;
            startedAt = Util.getNanos() - elapsedMillis;
            pausedAt = TIME_NOT_SET;
        }
    }

    public synchronized void pause() {
        if (pausedAt == TIME_NOT_SET) {
            pausedAt = getCurrentTimestamp();
        }
    }

    public synchronized void ensureInRange(final double frameStartTime, final double frameEndTime) {
        if (isPaused()) {
            return;
        }
        final long currentTimestamp = getCurrentTimestamp();
        final double elapsedTime = (currentTimestamp - startedAt) / SECONDS_TO_NANOS;
        if (elapsedTime < frameStartTime || elapsedTime > frameEndTime) {
            final double adjustedElapsedTime = Mth.clamp(elapsedTime, frameStartTime, frameEndTime);
            startedAt = (long) Math.floor(currentTimestamp - adjustedElapsedTime * SECONDS_TO_NANOS);
        }
    }

    private long getCurrentTimestamp() {
        return pausedAt != TIME_NOT_SET ? pausedAt : Util.getNanos();
    }

    public double getElapsedTime() {
        if (startedAt == TIME_NOT_SET) {
            return 0.0;
        }
        return (getCurrentTimestamp() - startedAt) / SECONDS_TO_NANOS;
    }

    public boolean isPaused() {
        return startedAt == TIME_NOT_SET || pausedAt != TIME_NOT_SET;
    }
}
