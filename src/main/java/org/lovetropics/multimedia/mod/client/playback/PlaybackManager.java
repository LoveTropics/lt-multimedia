package org.lovetropics.multimedia.mod.client.playback;

import java.util.ArrayList;
import java.util.List;

public class PlaybackManager {
    private static final List<Playback> ACTIVE_PLAYBACKS = new ArrayList<>();

    /* package-private */ static void register(final Playback playback) {
        ACTIVE_PLAYBACKS.add(playback);
    }

    /* package-private */ static void unregister(final Playback playback) {
        ACTIVE_PLAYBACKS.remove(playback);
    }

    public static void endFrame() {
        for (final Playback playback : ACTIVE_PLAYBACKS) {
            playback.endFrame();
        }
    }
}
