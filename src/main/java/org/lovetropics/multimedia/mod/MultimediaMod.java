package org.lovetropics.multimedia.mod;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowManager;

import javax.annotation.Nullable;
import java.util.Objects;

@Mod(MultimediaMod.ID)
public class MultimediaMod {
    public static final String ID = "multimedia";

    @Nullable
    private static MediaFileCache mediaCache;
    @Nullable
    private static SlideshowManager slideshowManager;

    public MultimediaMod(final IEventBus modBus, final ModContainer modContainer) {
        if (FMLLoader.getDist() == Dist.CLIENT) {
            mediaCache = new MediaFileCache(
                    FMLPaths.MODSDIR.get().resolve("lovetropics").resolve("media_cache"),
                    "LoveTropics Multimedia / " + modContainer.getModInfo().getVersion()
            );
            slideshowManager = new SlideshowManager(mediaCache);

            modBus.addListener(slideshowManager::registerOverlays);
            NeoForge.EVENT_BUS.register(slideshowManager);
        }
    }

    public static ResourceLocation location(final String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    public static MediaFileCache mediaCache() {
        return Objects.requireNonNull(mediaCache, "Media cache not initialized");
    }

    public static SlideshowManager slideshowManager() {
        return Objects.requireNonNull(slideshowManager, "Slideshow manager not initialized");
    }
}
