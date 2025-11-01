package org.lovetropics.multimedia.mod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.network.MultimediaModNetwork;

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

    public MultimediaMod(final IEventBus modBus) {
        ENTITY_REGISTER.register(modBus);
        MultimediaModNetwork.DATA_SERIALIZER_REGISTER.register(modBus);
    }

    public static ResourceLocation location(final String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
