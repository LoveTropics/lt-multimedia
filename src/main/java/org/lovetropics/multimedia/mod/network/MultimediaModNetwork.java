package org.lovetropics.multimedia.mod.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

import java.util.Optional;

@EventBusSubscriber(modid = MultimediaMod.ID)
public final class MultimediaModNetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static final DeferredRegister<EntityDataSerializer<?>> DATA_SERIALIZER_REGISTER = DeferredRegister.create(NeoForgeRegistries.ENTITY_DATA_SERIALIZERS, MultimediaMod.ID);

    public static final DeferredHolder<EntityDataSerializer<?>, EntityDataSerializer<Optional<Slideshow>>> SLIDESHOW_SERIALIZER = DATA_SERIALIZER_REGISTER.register("slideshow", () -> EntityDataSerializer.forValueType(Slideshow.STREAM_CODEC.apply(ByteBufCodecs::optional)));

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(ClientboundPreloadMediaPacket.TYPE, ClientboundPreloadMediaPacket.STREAM_CODEC);
        registrar.playToClient(ClientboundStartSlideshowPacket.TYPE, ClientboundStartSlideshowPacket.STREAM_CODEC);
        registrar.playToClient(ClientboundClearSlideshowPacket.TYPE, ClientboundClearSlideshowPacket.STREAM_CODEC);
    }
}
