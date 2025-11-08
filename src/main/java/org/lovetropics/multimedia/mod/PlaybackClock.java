package org.lovetropics.multimedia.mod;

import net.minecraft.Util;
import org.lovetropics.multimedia.mod.client.playback.ClockSyncer;

public class PlaybackClock {
    private static final long TIME_NOT_SET = -1;
    private static final double SECONDS_TO_NANOS = 1000_000_000.0;

    private volatile long startedAt = TIME_NOT_SET;
    private volatile long pausedAt = TIME_NOT_SET;

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

    public ClockSyncer createSyncer(final boolean authoritative) {
        return new ClockSyncer() {
            @Override
            public boolean requestSyncTo(final double elapsedTime) {
                if (authoritative) {
                    setElapsedTime(elapsedTime);
                    return true;
                }
                return false;
            }

            @Override
            public double getElapsedTime() {
                return PlaybackClock.this.getElapsedTime();
            }

            @Override
            public boolean isPaused() {
                return PlaybackClock.this.isPaused();
            }
        };
    }
}
