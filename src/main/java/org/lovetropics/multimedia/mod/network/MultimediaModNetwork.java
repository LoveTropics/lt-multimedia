package org.lovetropics.multimedia.mod.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.lovetropics.multimedia.mod.MultimediaMod;

@EventBusSubscriber(modid = MultimediaMod.ID)
public final class MultimediaModNetwork {
    private static final String PROTOCOL_VERSION = "1";

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(ClientboundPreloadMediaPacket.TYPE, ClientboundPreloadMediaPacket.STREAM_CODEC);
        registrar.playToClient(ClientboundStartSlideshowPacket.TYPE, ClientboundStartSlideshowPacket.STREAM_CODEC);
    }

    @SubscribeEvent
    public static void registerClientHandler(final RegisterClientPayloadHandlersEvent event) {
        event.register(ClientboundPreloadMediaPacket.TYPE, ClientboundPreloadMediaPacket::handle);
        event.register(ClientboundStartSlideshowPacket.TYPE, ClientboundStartSlideshowPacket::handle);
    }
}
