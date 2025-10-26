package org.lovetropics.multimedia.mod.client.slideshow;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

import javax.annotation.Nullable;

public class SlideshowManager {
    private final MediaFileCache mediaCache;

    @Nullable
    private FullScreenSlideshow fullScreenSlideshow;

    public SlideshowManager(final MediaFileCache mediaCache) {
        this.mediaCache = mediaCache;
    }

    public void registerOverlays(final RegisterGuiLayersEvent event) {
        event.registerAboveAll(MultimediaMod.location("slideshow"), (graphics, deltaTracker) -> {
            if (fullScreenSlideshow != null) {
                final float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
                fullScreenSlideshow.draw(SlideshowGraphics.forGui(graphics, Minecraft.getInstance().font), partialTicks);
            }
        });
    }

    @SubscribeEvent
    public void tick(final ClientTickEvent.Pre event) {
        if (fullScreenSlideshow != null && fullScreenSlideshow.tick()) {
            fullScreenSlideshow = null;
        }
    }

    @SubscribeEvent
    public void onLoggedOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        if (fullScreenSlideshow != null) {
            fullScreenSlideshow.close();
            fullScreenSlideshow = null;
        }
    }

    public void start(final Slideshow slideshow) {
        slideshow.ensureDownloaded(mediaCache);
        fullScreenSlideshow = new FullScreenSlideshow(new SlideshowDriver(mediaCache, slideshow));
    }

    public void clear() {
        if (fullScreenSlideshow != null) {
            fullScreenSlideshow.clear();
        }
    }
}
