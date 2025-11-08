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

    public void tick(final ScreenEntity screen, final MediaFileCache mediaCache) {
        final Slideshow slideshow = screen.getSlideshow().orElse(null);
        if (driver != null && !driver.slideshow().equals(slideshow)) {
            driver.close();
            driver = null;
        }
        if (slideshow != null && driver == null) {
            slideshow.ensureDownloaded(mediaCache);
            driver = new SlideshowDriver(mediaCache, slideshow, PlaybackSyncType.WALL_TIME);
        }

        if (driver != null) {
            driver.setAudioVolume((float) MultimediaClientConfig.get().audioVolume.getAsDouble());
            driver.setAudioSource(screen.asAudioSource());
            if (driver.tick()) {
                driver.close();
                driver = null;
            }
        }
    }

    public void clear() {
        if (driver != null) {
            driver.close();
            driver = null;
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
