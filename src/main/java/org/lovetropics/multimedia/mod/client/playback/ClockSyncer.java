package org.lovetropics.multimedia.mod.client.playback;

public interface ClockSyncer {
    boolean requestSyncTo(double elapsedTime);

    double getElapsedTime();

    boolean isPaused();
}
