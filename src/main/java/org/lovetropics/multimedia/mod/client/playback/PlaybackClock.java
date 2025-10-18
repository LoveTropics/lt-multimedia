package org.lovetropics.multimedia.mod.client.playback;

import net.minecraft.Util;
import net.minecraft.util.Mth;

public class PlaybackClock {
    private static final long TIME_NOT_SET = -1;

    private volatile long startedAt = TIME_NOT_SET;
    private volatile long pausedAt = TIME_NOT_SET;

    public void play() {
        if (startedAt == TIME_NOT_SET) {
            startedAt = Util.getMillis();
        } else if (pausedAt != TIME_NOT_SET) {
            final long elapsedMillis = pausedAt - startedAt;
            startedAt = Util.getMillis() - elapsedMillis;
            pausedAt = TIME_NOT_SET;
        }
    }

    public void pause() {
        if (pausedAt == TIME_NOT_SET) {
            pausedAt = getCurrentTimestamp();
        }
    }

    public void ensureInRange(final double frameStartTime, final double frameEndTime) {
        if (isPaused()) {
            return;
        }
        final long currentTimestamp = getCurrentTimestamp();
        final double elapsedTime = (currentTimestamp - startedAt) / 1000.0;
        if (elapsedTime < frameStartTime || elapsedTime > frameEndTime) {
            final double adjustedElapsedTime = Mth.clamp(elapsedTime, frameStartTime, frameEndTime);
            startedAt = Mth.floor(currentTimestamp - adjustedElapsedTime * 1000.0);
        }
    }

    private long getCurrentTimestamp() {
        return pausedAt != TIME_NOT_SET ? pausedAt : Util.getMillis();
    }

    public double getElapsedTime() {
        if (startedAt == TIME_NOT_SET) {
            return 0.0;
        }
        return (getCurrentTimestamp() - startedAt) / 1000.0;
    }

    public boolean isPaused() {
        return startedAt == TIME_NOT_SET || pausedAt != TIME_NOT_SET;
    }
}
