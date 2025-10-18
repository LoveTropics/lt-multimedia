package org.lovetropics.multimedia.mod.client.slideshow;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.slideshow.Slide;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

import javax.annotation.Nullable;

// TODO: Capture and lock input
public class SlideshowManager {
    private final MediaFileCache mediaCache;

    @Nullable
    private ActiveSlideshow activeSlideshow;

    public SlideshowManager(final MediaFileCache mediaCache) {
        this.mediaCache = mediaCache;
    }

    public void registerOverlays(final RegisterGuiLayersEvent event) {
        event.registerAboveAll(MultimediaMod.location("slideshow"), (graphics, deltaTracker) -> {
            if (activeSlideshow != null) {
                activeSlideshow.draw(graphics, Minecraft.getInstance().font, deltaTracker);
            }
        });
    }

    @SubscribeEvent
    public void tick(final ClientTickEvent.Pre event) {
        if (activeSlideshow != null && activeSlideshow.tick()) {
            activeSlideshow = null;
        }
    }

    public void start(final Slideshow slideshow) {
        for (final Slide slide : slideshow.slides()) {
            mediaCache.ensureDownloaded(slide.file());
        }
        activeSlideshow = new ActiveSlideshow(mediaCache, slideshow);
    }
}
