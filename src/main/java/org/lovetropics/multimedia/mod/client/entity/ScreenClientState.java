package org.lovetropics.multimedia.mod.client.entity;

import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowDriver;
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
            driver = new SlideshowDriver(mediaCache, slideshow);
        }

        if (driver != null) {
            driver.tick();
        }
    }

    @Nullable
    public SlideshowDriver slideshow() {
        return driver;
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }
}
