package org.lovetropics.multimedia.mod.slideshow.instance;

import com.lovetropics.lib.slideshow.SlideshowInstanceHandle;
import com.lovetropics.lib.slideshow.SlideshowManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.network.ClientboundPreloadMediaPacket;
import org.lovetropics.multimedia.mod.slideshow.SlideshowHolder;
import org.lovetropics.multimedia.mod.slideshow.Slideshows;

import java.util.ArrayList;
import java.util.List;

public class ServerSlideshowManager implements SlideshowManager {
    private final List<ServerFullScreenSlideshow> fullScreenInstances = new ArrayList<>();

    @SubscribeEvent
    public void tick(final ServerTickEvent.Post event) {
        fullScreenInstances.removeIf(ServerFullScreenSlideshow::tick);
    }

    @SubscribeEvent
    public void onPlayerClone(final PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof final ServerPlayer oldPlayer && event.getEntity() instanceof final ServerPlayer newPlayer) {
            replacePlayer(oldPlayer, newPlayer);
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof final ServerPlayer player)) {
            return;
        }
        for (final ServerFullScreenSlideshow instance : fullScreenInstances) {
            instance.removePlayer(player);
        }
    }

    @Override
    public @Nullable ServerFullScreenSlideshow open(final ResourceLocation id) {
        final SlideshowHolder slideshow = Slideshows.REGISTRY.get(id);
        return slideshow != null ? open(slideshow) : null;
    }

    public ServerFullScreenSlideshow open(final SlideshowHolder slideshow) {
        return new ServerFullScreenSlideshow(this, slideshow);
    }

    @Override
    public void preload(final ServerPlayer player, final ResourceLocation id) {
        final SlideshowHolder slideshow = Slideshows.REGISTRY.get(id);
        if (slideshow != null) {
            preload(player, slideshow);
        }
    }

    @Override
    public void replacePlayer(final ServerPlayer oldPlayer, final ServerPlayer newPlayer) {
        for (final ServerFullScreenSlideshow instance : fullScreenInstances) {
            instance.replacePlayer(oldPlayer, newPlayer);
        }
    }

    public void preload(final ServerPlayer player, final SlideshowHolder slideshow) {
        PacketDistributor.sendToPlayer(player, new ClientboundPreloadMediaPacket(slideshow.value().files().toList()));
    }

    public void clear(final Entity entity) {
        if (entity instanceof final ServerPlayer player) {
            final ServerFullScreenSlideshow instance = byPlayer(player);
            if (instance != null) {
                instance.removePlayer(player);
            }
        } else if (entity instanceof final ScreenEntity screen) {
            screen.setSlideshow(null);
        }
    }

    public @Nullable SlideshowInstanceHandle byEntity(final Entity entity) {
        if (entity instanceof final ServerPlayer player) {
            return byPlayer(player);
        } else if (entity instanceof final ScreenEntity screen) {
            return screen.asHandle();
        }
        return null;
    }

    public @Nullable ServerFullScreenSlideshow byPlayer(final ServerPlayer player) {
        for (final ServerFullScreenSlideshow instance : fullScreenInstances) {
            if (instance.isPlayerWatching(player)) {
                return instance;
            }
        }
        return null;
    }

    /* package-private */ void ensureRegistered(final ServerFullScreenSlideshow instance) {
        if (!fullScreenInstances.contains(instance)) {
            fullScreenInstances.add(instance);
        }
    }

    /* package-private */ void remove(final ServerFullScreenSlideshow instance) {
        fullScreenInstances.remove(instance);
    }

    /* package-private */ void removePlayerFromOthers(final ServerPlayer player, final ServerFullScreenSlideshow instance) {
        for (final ServerFullScreenSlideshow otherInstance : fullScreenInstances) {
            if (otherInstance != instance) {
                otherInstance.removePlayer(player);
            }
        }
    }
}
