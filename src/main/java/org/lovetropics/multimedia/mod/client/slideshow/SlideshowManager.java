package org.lovetropics.multimedia.mod.client.slideshow;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.entity.ScreenClientState;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

import javax.annotation.Nullable;

public class SlideshowManager {
    private final MediaFileCache mediaCache;

    @Nullable
    private FullScreenSlideshow fullScreenSlideshow;

    private final Int2ObjectMap<ScreenClientState> screenStates = new Int2ObjectOpenHashMap<>();

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

        final ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (final Int2ObjectMap.Entry<ScreenClientState> entry : Int2ObjectMaps.fastIterable(screenStates)) {
                final Entity entity = level.getEntity(entry.getIntKey());
                if (entity instanceof final ScreenEntity screen) {
                    entry.getValue().tick(screen, mediaCache);
                }
            }
        }
    }

    @SubscribeEvent
    public void onLoggedOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        if (fullScreenSlideshow != null) {
            fullScreenSlideshow.close();
            fullScreenSlideshow = null;
        }
    }

    @SubscribeEvent
    public void onEntityJoinLevel(final EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof final ScreenEntity screen) {
            screenStates.put(screen.getId(), new ScreenClientState());
        }
    }

    @SubscribeEvent
    public void onEntityLeaveLevel(final EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof final ScreenEntity screen) {
            final ScreenClientState screenState = screenStates.remove(screen.getId());
            if (screenState != null) {
                screenState.close();
            }
        }
    }

    @SubscribeEvent
    public void onLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            screenStates.values().forEach(ScreenClientState::close);
            screenStates.clear();
        }
    }

    public void start(final Slideshow slideshow) {
        slideshow.ensureDownloaded(mediaCache);
        fullScreenSlideshow = new FullScreenSlideshow(new SlideshowDriver(mediaCache, slideshow, PlaybackSyncType.AUDIO));
    }

    public void clear() {
        if (fullScreenSlideshow != null) {
            fullScreenSlideshow.clear();
        }
    }

    @Nullable
    public ScreenClientState getScreenState(final ScreenEntity screen) {
        return screenStates.get(screen.getId());
    }
}
