package org.lovetropics.multimedia.mod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.entity.render.ScreenRenderer;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowManager;
import org.lovetropics.multimedia.mod.config.MultimediaClientConfig;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.network.MultimediaModNetwork;

import javax.annotation.Nullable;
import java.util.Objects;

@Mod(MultimediaMod.ID)
public class MultimediaMod {
    public static final String ID = "multimedia";

    private static final DeferredRegister.Entities ENTITY_REGISTER = DeferredRegister.createEntities(ID);

    public static final DeferredHolder<EntityType<?>, EntityType<ScreenEntity>> SCREEN = ENTITY_REGISTER.registerEntityType("screen", ScreenEntity::new, MobCategory.MISC, entityBuilder -> entityBuilder
            .noLootTable()
            .sized(0.5f, 0.5f)
            .clientTrackingRange(10)
            .updateInterval(Integer.MAX_VALUE)
    );

    @Nullable
    private static MediaFileCache mediaCache;
    @Nullable
    private static SlideshowManager slideshowManager;

    public MultimediaMod(final IEventBus modBus, final ModContainer modContainer) {
        ENTITY_REGISTER.register(modBus);
        MultimediaModNetwork.DATA_SERIALIZER_REGISTER.register(modBus);

        if (FMLLoader.getDist() == Dist.CLIENT) {
            MultimediaClientConfig.register(modContainer);

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

    @EventBusSubscriber(modid = MultimediaMod.ID, value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void registerEntityRenderers(final EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(SCREEN.get(), ScreenRenderer::new);
        }
    }
}
