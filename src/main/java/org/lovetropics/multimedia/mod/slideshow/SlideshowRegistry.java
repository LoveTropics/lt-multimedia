package org.lovetropics.multimedia.mod.slideshow;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.Util;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.mod.MediaFile;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

@EventBusSubscriber(modid = MultimediaMod.ID)
public class SlideshowRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LISTER = FileToIdConverter.json("slideshow");

    public static final Map<ResourceLocation, SlideshowHolder> REGISTRY = new HashMap<>();
    private static final Map<ResourceLocation, SlideshowHolder> IMPORTED_REGISTRY = new HashMap<>();

    @SubscribeEvent
    public static void addReloadListener(final AddServerReloadListenersEvent event) {
        final RegistryAccess registries = event.getRegistryAccess();
        event.addListener(MultimediaMod.location("slideshows"), (barrier, resourceManager, backgroundExecutor, gameExecutor) ->
                load(registries, resourceManager, backgroundExecutor)
                        .thenCompose(barrier::wait)
                        .thenAcceptAsync(slideshows -> {
                            REGISTRY.clear();
                            slideshows.forEach(holder -> REGISTRY.put(holder.id(), holder));
                            REGISTRY.putAll(IMPORTED_REGISTRY);
                        }, gameExecutor)
        );
    }

    public static ResourceLocation importSimpleVideo(final ResourceLocation name, final URI url, final double duration) {
        final ResourceLocation id = name.withPrefix("import/");
        final Slideshow slideshow = new Slideshow(
                List.of(new Slide(
                        new SlideContent.Video(new MediaFile(url), Duration.ZERO, Duration.ofSeconds((long) (duration * 1000.0)), 1.0f),
                        Optional.empty(),
                        Optional.empty()
                )),
                SlideTransition.NONE,
                false
        );
        final SlideshowHolder holder = new SlideshowHolder(id, slideshow);
        REGISTRY.put(id, holder);
        IMPORTED_REGISTRY.put(id, holder);
        return id;
    }

    private static CompletableFuture<List<SlideshowHolder>> load(final RegistryAccess registryAccess, final ResourceManager resourceManager, final Executor executor) {
        final RegistryOps<JsonElement> ops = registryAccess.createSerializationContext(JsonOps.INSTANCE);
        return CompletableFuture.supplyAsync(() -> listEntries(ops, resourceManager, executor), executor).thenCompose(Function.identity());
    }

    private static CompletableFuture<List<SlideshowHolder>> listEntries(final DynamicOps<JsonElement> ops, final ResourceManager resourceManager, final Executor executor) {
        final List<CompletableFuture<SlideshowHolder>> futures = LISTER.listMatchingResources(resourceManager).entrySet().stream()
                .map(resource -> {
                    final ResourceLocation path = resource.getKey();
                    final ResourceLocation id = LISTER.fileToId(path);
                    return CompletableFuture.supplyAsync(() -> {
                        final Slideshow slideshow = loadSlideshow(ops, path, resource.getValue());
                        return slideshow != null ? new SlideshowHolder(id, slideshow) : null;
                    }, executor);
                })
                .toList();
        return Util.sequence(futures).thenApply(slideshows -> slideshows.stream().filter(Objects::nonNull).toList());
    }

    @Nullable
    private static Slideshow loadSlideshow(final DynamicOps<JsonElement> ops, final ResourceLocation path, final Resource resource) {
        try (final BufferedReader reader = resource.openAsReader()) {
            return Slideshow.CODEC.parse(ops, StrictJsonParser.parse(reader)).getOrThrow(JsonSyntaxException::new);
        } catch (final IOException | JsonParseException e) {
            LOGGER.error("Failed to load media slideshow at {}", path, e);
            return null;
        }
    }
}
