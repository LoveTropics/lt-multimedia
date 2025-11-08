package org.lovetropics.multimedia.mod.client.playback;

import org.lovetropics.multimedia.mod.PlaybackClock;

public enum PlaybackSyncType {
    WALL_TIME,
    PLAYBACK,
    ;

    public ClockSyncer createAudioSyncer(final PlaybackClock clock) {
        return clock.createSyncer(this == PLAYBACK);
    }
}
