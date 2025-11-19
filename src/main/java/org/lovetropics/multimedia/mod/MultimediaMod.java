package org.lovetropics.multimedia.mod;

import com.lovetropics.lib.slideshow.SlideshowApi;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.network.MultimediaModNetwork;
import org.lovetropics.multimedia.mod.slideshow.instance.ServerSlideshowManager;

import java.util.List;
import java.util.Objects;

@Mod(MultimediaMod.ID)
public class MultimediaMod {
    public static final String ID = "multimedia";

    private static final DeferredRegister.Entities ENTITY_REGISTER = DeferredRegister.createEntities(ID);
    private static final DeferredRegister.DataComponents DATA_COMPONENTS_REGISTER = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ID);

    public static final DeferredHolder<EntityType<?>, EntityType<ScreenEntity>> SCREEN = ENTITY_REGISTER.registerEntityType("screen", ScreenEntity::new, MobCategory.MISC, entityBuilder -> entityBuilder
            .noLootTable()
            .sized(0.5f, 0.5f)
            .clientTrackingRange(6)
            .updateInterval(Integer.MAX_VALUE)
    );

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<ResourceLocation>>> SLIDESHOW_VIEWER = DATA_COMPONENTS_REGISTER.registerComponentType("slideshow_viewer", b -> b
            .persistent(ResourceLocation.CODEC.listOf())
            .networkSynchronized(ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()))
            .cacheEncoding()
    );

    @Nullable
    private static ServerSlideshowManager slideshowManager;

    public MultimediaMod(final IEventBus modBus) {
        ENTITY_REGISTER.register(modBus);
        DATA_COMPONENTS_REGISTER.register(modBus);
        MultimediaModNetwork.DATA_SERIALIZER_REGISTER.register(modBus);

        slideshowManager = new ServerSlideshowManager();
        NeoForge.EVENT_BUS.register(slideshowManager);
        SlideshowApi.setSlideshowManager(slideshowManager);
    }

    public static ResourceLocation location(final String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    public static ServerSlideshowManager slideshowManager() {
        return Objects.requireNonNull(slideshowManager, "Slideshow manager not initialized");
    }
}
