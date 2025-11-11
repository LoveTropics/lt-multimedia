package org.lovetropics.multimedia.mod.client.slideshow;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.entity.ScreenClientState;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;
import org.lovetropics.multimedia.mod.network.SlideshowNetworkId;

import javax.annotation.Nullable;

public class ClientSlideshowManager {
    private final MediaFileCache mediaCache;

    @Nullable
    private FullScreenSlideshow fullScreenSlideshow;

    private final Int2ObjectMap<ScreenClientState> screenStates = new Int2ObjectOpenHashMap<>();

    public ClientSlideshowManager(final MediaFileCache mediaCache) {
        this.mediaCache = mediaCache;
    }

    public void registerOverlays(final RegisterGuiLayersEvent event) {
        event.registerAboveAll(MultimediaMod.location("slideshow"), (graphics, deltaTracker) -> {
            final Minecraft minecraft = Minecraft.getInstance();
            if (fullScreenSlideshow != null && !fullScreenSlideshow.shouldRenderOver(minecraft.screen)) {
                final float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
                fullScreenSlideshow.draw(SlideshowGraphics.forGui(graphics, minecraft.font), partialTicks);
            }
        });
    }

    @SubscribeEvent
    public void onRenderScreen(final ScreenEvent.Render.Post event) {
        // Only render over the topmost layer
        final Screen screen = event.getScreen();
        if (screen != Minecraft.getInstance().screen) {
            return;
        }
        if (fullScreenSlideshow != null && fullScreenSlideshow.shouldRenderOver(screen)) {
            fullScreenSlideshow.draw(SlideshowGraphics.forGui(event.getGuiGraphics(), event.getScreen().getFont()), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public void tick(final ClientTickEvent.Pre event) {
        if (fullScreenSlideshow != null && fullScreenSlideshow.tick()) {
            fullScreenSlideshow.close();
            fullScreenSlideshow = null;
        }

        final ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (final Int2ObjectMap.Entry<ScreenClientState> entry : Int2ObjectMaps.fastIterable(screenStates)) {
                final Entity entity = level.getEntity(entry.getIntKey());
                if (entity instanceof final ScreenEntity screen) {
                    entry.getValue().tick(screen);
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

    public void start(final SlideshowNetworkId id, final Slideshow slideshow, final double time, final boolean paused) {
        slideshow.ensureDownloaded(mediaCache);

        final ScreenClientState screen = getScreenState(id);
        if (screen != null) {
            screen.start(mediaCache, slideshow, time, paused);
        } else if (id.isFullScreen()) {
            if (fullScreenSlideshow != null) {
                fullScreenSlideshow.close();
            }
            fullScreenSlideshow = new FullScreenSlideshow(new SlideshowDriver(mediaCache, slideshow, PlaybackSyncType.PLAYBACK));
            fullScreenSlideshow.seekTo(time, paused);
        }
    }

    public void clear(final SlideshowNetworkId id) {
        final ScreenClientState screen = getScreenState(id);
        if (screen != null) {
            screen.clear();
        } else if (fullScreenSlideshow != null) {
            fullScreenSlideshow.close();
            fullScreenSlideshow = null;
        }
    }

    public void seekTo(final SlideshowNetworkId id, final double time, final boolean paused) {
        final ScreenClientState screen = getScreenState(id);
        if (screen != null) {
            screen.seekTo(time, paused);
        } else if (id.isFullScreen() && fullScreenSlideshow != null) {
            fullScreenSlideshow.seekTo(time, paused);
        }
    }

    @Nullable
    private ScreenClientState getScreenState(final SlideshowNetworkId id) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || id.isFullScreen()) {
            return null;
        }
        return screenStates.get(id.id());
    }

    @Nullable
    public ScreenClientState getScreenState(final ScreenEntity screen) {
        return screenStates.get(screen.getId());
    }
}
