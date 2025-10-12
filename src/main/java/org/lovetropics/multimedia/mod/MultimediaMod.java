package org.lovetropics.multimedia.mod;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(MultimediaMod.ID)
public class MultimediaMod {
    public static final String ID = "multimedia";

    public MultimediaMod(final IEventBus modBus, final ModContainer modContainer) {
    }

    public static ResourceLocation location(final String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
