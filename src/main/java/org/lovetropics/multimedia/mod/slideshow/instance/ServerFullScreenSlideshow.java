package org.lovetropics.multimedia.mod.slideshow.instance;

import com.lovetropics.lib.slideshow.SlideshowInstanceHandle;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lovetropics.multimedia.mod.PlaybackClock;
import org.lovetropics.multimedia.mod.network.ClientboundClearSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundSeekSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundStartSlideshowPacket;
import org.lovetropics.multimedia.mod.network.SlideshowNetworkId;
import org.lovetropics.multimedia.mod.slideshow.SlideshowHolder;

import java.util.Set;

public class ServerFullScreenSlideshow implements SlideshowInstanceHandle {
    private final ServerSlideshowManager slideshowManager;
    private final SlideshowHolder slideshow;
    private final double totalTime;

    private final PlaybackClock clock = new PlaybackClock();
    private final Set<ServerPlayer> players = new ReferenceOpenHashSet<>();

    /* package-private */ ServerFullScreenSlideshow(final ServerSlideshowManager slideshowManager, final SlideshowHolder slideshow) {
        this.slideshowManager = slideshowManager;
        this.slideshow = slideshow;
        totalTime = slideshow.value().duration().toMillis() / 1000.0;
    }

    public SlideshowHolder slideshow() {
        return slideshow;
    }

    /* package-private */ boolean tick() {
        players.removeIf(Entity::isRemoved);
        return players.isEmpty() || currentTime() >= totalTime();
    }

    @Override
    public void addPlayer(final ServerPlayer player) {
        slideshowManager.removePlayerFromOthers(player, this);
        if (players.add(player)) {
            PacketDistributor.sendToPlayer(player, new ClientboundStartSlideshowPacket(SlideshowNetworkId.FULL_SCREEN, slideshow.value(), currentTime(), clock.isPaused()));
            slideshowManager.ensureRegistered(this);
        }
    }

    @Override
    public void removePlayer(final ServerPlayer player) {
        if (players.remove(player) && !player.isRemoved()) {
            PacketDistributor.sendToPlayer(player, new ClientboundClearSlideshowPacket(SlideshowNetworkId.FULL_SCREEN));
        }
    }

    /* package-private */ void replacePlayer(final ServerPlayer oldPlayer, final ServerPlayer newPlayer) {
        if (players.remove(oldPlayer)) {
            players.add(newPlayer);
        }
    }

    public boolean isPlayerWatching(final ServerPlayer player) {
        return players.contains(player);
    }

    @Override
    public void seekTo(final double time, final boolean paused) {
        clock.set(time, paused);
        broadcast(new ClientboundSeekSlideshowPacket(SlideshowNetworkId.FULL_SCREEN, time, paused));
    }

    @Override
    public double currentTime() {
        return clock.getElapsedTime();
    }

    @Override
    public double totalTime() {
        return totalTime;
    }

    @Override
    public boolean isPaused() {
        return clock.isPaused();
    }

    @Override
    public void close() {
        broadcast(new ClientboundClearSlideshowPacket(SlideshowNetworkId.FULL_SCREEN));
        players.clear();
        slideshowManager.remove(this);
    }

    private void broadcast(final CustomPacketPayload packet) {
        for (final ServerPlayer player : players) {
            if (!player.isRemoved()) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }
}
