package org.lovetropics.multimedia.mod.client.playback;

public enum PlaybackSyncType {
    WALL_TIME,
    PLAYBACK,
    ;

    public ClockSyncer createAudioSyncer(final PlaybackClock clock) {
        return clock.createSyncer(this == PLAYBACK);
    }
}
