package org.lovetropics.multimedia.mod.client.entity;

import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.config.MultimediaClientConfig;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowDriver;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowRenderState;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

public class ScreenClientState implements AutoCloseable {
    @Nullable
    private SlideshowDriver driver;

    public void tick(final ScreenEntity screen) {
        if (driver != null) {
            driver.setAudioVolume((float) MultimediaClientConfig.get().audioVolume.getAsDouble());
            driver.setAudioSource(screen.asAudioSource());
            driver.tick();
        }
    }

    public void start(final MediaFileCache mediaCache, final Slideshow slideshow, final double time, final boolean paused) {
        if (driver == null || !driver.slideshow().equals(slideshow)) {
            if (driver != null) {
                driver.close();
            }
            driver = new SlideshowDriver(mediaCache, slideshow, PlaybackSyncType.WALL_TIME);
        }
        driver.seekTo(time, paused);
    }

    public void clear() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    public void seekTo(final double time, final boolean paused) {
        if (driver != null) {
            driver.seekTo(time, paused);
        }
    }

    @Nullable
    public SlideshowRenderState extractRenderState(final float partialTicks) {
        return driver != null ? driver.extractRenderState(partialTicks) : null;
    }

    @Override
    public void close() {
        clear();
    }
}
