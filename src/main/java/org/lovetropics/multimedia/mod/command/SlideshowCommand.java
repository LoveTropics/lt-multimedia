package org.lovetropics.multimedia.mod.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.lovetropics.multimedia.mod.MediaFile;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.network.ClientboundClearSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundPreloadMediaPacket;
import org.lovetropics.multimedia.mod.network.ClientboundStartSlideshowPacket;
import org.lovetropics.multimedia.mod.slideshow.Slide;
import org.lovetropics.multimedia.mod.slideshow.SlideshowHolder;
import org.lovetropics.multimedia.mod.slideshow.Slideshows;

import java.util.Collection;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.arguments.EntityArgument.*;
import static net.minecraft.commands.arguments.ResourceLocationArgument.getId;
import static net.minecraft.commands.arguments.ResourceLocationArgument.id;

@EventBusSubscriber(modid = MultimediaMod.ID)
public class SlideshowCommand {
    private static final DynamicCommandExceptionType NO_SLIDESHOW = new DynamicCommandExceptionType(id -> Component.translatableEscape("commands.slideshow.error.no_slideshow", id));

    @SubscribeEvent
    public static void register(final RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("slideshow")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(literal("start")
                        .then(argument("targets", entities())
                                .then(argument("id", id())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggestResource(Slideshows.REGISTRY.keySet(), builder)
                                        )
                                        .executes(context -> startSlideshow(getEntities(context, "targets"), getId(context, "id")))
                                )
                        )
                )
                .then(literal("clear")
                        .then(argument("targets", entities())
                                    .executes(context -> clearSlideshow(getEntities(context, "targets")))
                        )
                )
                .then(literal("preload")
                        .then(argument("targets", players())
                                .then(argument("id", id())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggestResource(Slideshows.REGISTRY.keySet(), builder)
                                        )
                                        .executes(context -> preloadSlideshow(getPlayers(context, "targets"), getId(context, "id")))
                                )
                        )
                )
        );
    }

    private static int startSlideshow(final Collection<? extends Entity> targets, final ResourceLocation id) throws CommandSyntaxException {
        final SlideshowHolder slideshow = Slideshows.REGISTRY.get(id);
        if (slideshow == null) {
            throw NO_SLIDESHOW.create(id);
        }
        for (final Entity target : targets) {
            if (target instanceof final ServerPlayer player) {
                player.connection.send(new ClientboundStartSlideshowPacket(slideshow.value()));
            } else if (target instanceof final ScreenEntity screen) {
                screen.setSlideshow(slideshow);
            }
        }
        return targets.size();
    }

    private static int clearSlideshow(final Collection<? extends Entity> targets) {
        for (final Entity target : targets) {
            if (target instanceof final ServerPlayer player) {
                player.connection.send(new ClientboundClearSlideshowPacket());
            } else if (target instanceof final ScreenEntity screen) {
                screen.setSlideshow(null);
            }
        }
        return targets.size();
    }

    private static int preloadSlideshow(final Collection<ServerPlayer> targets, final ResourceLocation id) throws CommandSyntaxException {
        final SlideshowHolder slideshow = Slideshows.REGISTRY.get(id);
        if (slideshow == null) {
            throw NO_SLIDESHOW.create(id);
        }
        final List<MediaFile> files = slideshow.value().slides().stream().map(Slide::file).distinct().toList();
        for (final ServerPlayer target : targets) {
            target.connection.send(new ClientboundPreloadMediaPacket(files));
        }
        return targets.size();
    }
}
