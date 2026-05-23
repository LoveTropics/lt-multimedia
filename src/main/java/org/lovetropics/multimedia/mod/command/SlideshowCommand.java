package org.lovetropics.multimedia.mod.command;

import com.lovetropics.lib.slideshow.SlideshowInstanceHandle;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;
import org.lovetropics.multimedia.mod.slideshow.SlideshowHolder;
import org.lovetropics.multimedia.mod.slideshow.SlideshowRegistry;
import org.lovetropics.multimedia.mod.slideshow.instance.ServerFullScreenSlideshow;

import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.arguments.EntityArgument.entities;
import static net.minecraft.commands.arguments.EntityArgument.getEntities;
import static net.minecraft.commands.arguments.IdentifierArgument.getId;
import static net.minecraft.commands.arguments.IdentifierArgument.id;

@EventBusSubscriber(modid = MultimediaMod.ID)
public class SlideshowCommand {
    private static final DynamicCommandExceptionType NO_SLIDESHOW = new DynamicCommandExceptionType(id -> Component.translatableEscape("commands.slideshow.error.no_slideshow", id));

    @SubscribeEvent
    public static void register(final RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("slideshow")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(argument("targets", entities())
                        .then(literal("play")
                                .executes(context -> setSlideshowPaused(getEntities(context, "targets"), false))
                                .then(argument("id", id())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggestResource(SlideshowRegistry.REGISTRY.keySet(), builder)
                                        )
                                        .executes(context -> startSlideshow(getEntities(context, "targets"), getId(context, "id")))
                                )
                        )
                        .then(literal("pause")
                                .executes(context -> setSlideshowPaused(getEntities(context, "targets"), true))
                        )
                        .then(literal("seek")
                                .then(argument("seconds", doubleArg(0.0))
                                        .executes(context -> seekSlideshow(getEntities(context, "targets"), getDouble(context, "seconds")))
                                )
                        )
                        .then(literal("clear")
                                .executes(context -> clearSlideshow(getEntities(context, "targets")))
                        )
                        .then(literal("preload")
                                .then(argument("id", id())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggestResource(SlideshowRegistry.REGISTRY.keySet(), builder)
                                        )
                                        .executes(context -> preloadSlideshow(getEntities(context, "targets"), getId(context, "id")))
                                )
                        )
                )
        );
    }

    private static int startSlideshow(final Collection<? extends Entity> targets, final Identifier id) throws CommandSyntaxException {
        final SlideshowHolder slideshow = SlideshowRegistry.REGISTRY.get(id);
        if (slideshow == null) {
            throw NO_SLIDESHOW.create(id);
        }
        for (final Entity target : targets) {
            if (target instanceof final ServerPlayer player) {
                final ServerFullScreenSlideshow instance = MultimediaMod.slideshowManager().open(slideshow);
                instance.play();
                instance.addPlayer(player);
            } else if (target instanceof final ScreenEntity screen) {
                screen.setSlideshow(slideshow);
            }
        }
        return targets.size();
    }

    private static int clearSlideshow(final Collection<? extends Entity> targets) {
        for (final Entity target : targets) {
            MultimediaMod.slideshowManager().clear(target);
        }
        return targets.size();
    }

    private static int setSlideshowPaused(final Collection<? extends Entity> targets, final boolean paused) {
        return applyToInstances(targets, instance ->
                instance.setPaused(paused)
        );
    }

    private static int seekSlideshow(final Collection<? extends Entity> targets, final double seconds) {
        return applyToInstances(targets, instance ->
                instance.seekTo(seconds, instance.isPaused())
        );
    }

    private static int applyToInstances(final Collection<? extends Entity> targets, final Consumer<SlideshowInstanceHandle> consumer) {
        final Set<SlideshowInstanceHandle> instances = new ReferenceOpenHashSet<>();
        for (final Entity target : targets) {
            final SlideshowInstanceHandle instance = MultimediaMod.slideshowManager().byEntity(target);
            if (instance != null) {
                instances.add(instance);
            }
        }
        for (final SlideshowInstanceHandle instance : instances) {
            consumer.accept(instance);
        }
        return instances.size();
    }

    private static int preloadSlideshow(final Collection<? extends Entity> targets, final Identifier id) throws CommandSyntaxException {
        final SlideshowHolder slideshow = SlideshowRegistry.REGISTRY.get(id);
        if (slideshow == null) {
            throw NO_SLIDESHOW.create(id);
        }
        for (final Entity target : targets) {
            if (target instanceof final ServerPlayer player) {
                MultimediaMod.slideshowManager().preload(player, slideshow);
            }
        }
        return targets.size();
    }
}
