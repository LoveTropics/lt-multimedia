package org.lovetropics.multimedia.mod.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lovetropics.multimedia.mod.MediaFile;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.config.MultimediaClientConfig;
import org.lovetropics.multimedia.mod.client.entity.render.ScreenRenderer;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowManager;
import org.lovetropics.multimedia.mod.network.ClientboundClearSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundPreloadMediaPacket;
import org.lovetropics.multimedia.mod.network.ClientboundStartSlideshowPacket;

import javax.annotation.Nullable;
import java.util.Objects;

@Mod(value = MultimediaMod.ID, dist = Dist.CLIENT)
public class MultimediaClientMod {
    @Nullable
    private static MediaFileCache mediaCache;
    @Nullable
    private static SlideshowManager slideshowManager;

    public MultimediaClientMod(final IEventBus modBus, final ModContainer modContainer) {
        MultimediaClientConfig.register(modContainer);

        mediaCache = new MediaFileCache(
                FMLPaths.MODSDIR.get().resolve("lovetropics").resolve("media_cache"),
                "LoveTropics Multimedia / " + modContainer.getModInfo().getVersion()
        );
        slideshowManager = new SlideshowManager(mediaCache);

        modBus.addListener(slideshowManager::registerOverlays);
        modBus.addListener(this::registerEntityRenderers);
        modBus.addListener(this::registerClientHandlers);
        NeoForge.EVENT_BUS.register(slideshowManager);
    }

    public static MediaFileCache mediaCache() {
        return Objects.requireNonNull(mediaCache, "Media cache not initialized");
    }

    public static SlideshowManager slideshowManager() {
        return Objects.requireNonNull(slideshowManager, "Slideshow manager not initialized");
    }

    public void registerEntityRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MultimediaMod.SCREEN.get(), ScreenRenderer::new);
    }

    public void registerClientHandlers(final RegisterClientPayloadHandlersEvent event) {
        final MediaFileCache mediaCache = mediaCache();
        final SlideshowManager slideshowManager = slideshowManager();
        event.register(ClientboundPreloadMediaPacket.TYPE, (packet, context) -> {
            for (final MediaFile file : packet.files()) {
                mediaCache.ensureDownloaded(file);
            }
        });
        event.register(ClientboundStartSlideshowPacket.TYPE, (packet, context) ->
                slideshowManager.start(packet.sequence())
        );
        event.register(ClientboundClearSlideshowPacket.TYPE, (packet, context) ->
                slideshowManager.clear()
        );
    }
}
