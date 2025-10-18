package org.lovetropics.multimedia.mod.client.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.FileUtil;
import net.minecraft.util.ExtraCodecs;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/* package-private */ record MediaIndex(
        List<CachedFile> cachedFiles
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Codec<MediaIndex> CODEC = CachedFile.CODEC.listOf().xmap(MediaIndex::new, MediaIndex::cachedFiles);

    public static MediaIndex load(final Path path) {
        if (!Files.exists(path)) {
            return new MediaIndex(List.of());
        }
        try (final BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getPartialOrThrow(JsonSyntaxException::new);
        } catch (final IOException | JsonParseException e) {
            LOGGER.error("Failed to load index file", e);
            return new MediaIndex(List.of());
        }
    }

    public void store(final Path path) {
        final JsonElement json = CODEC.encodeStart(JsonOps.INSTANCE, this).getOrThrow();
        try (final BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(json, writer);
        } catch (final IOException e) {
            LOGGER.error("Failed to store index file", e);
        }
    }

    public record CachedFile(
            MediaFileId fileId,
            String fileName,
            Instant lastAccessedAt
    ) {
        private static final Codec<String> FILE_NAME_CODEC = Codec.STRING.validate(name -> {
            if (name.indexOf('/') != -1 || !FileUtil.isPathPartPortable(name)) {
                return DataResult.error(() -> "Invalid file name: " + name);
            }
            return DataResult.success(name);
        });

        public static final Codec<CachedFile> CODEC = RecordCodecBuilder.create(i -> i.group(
                MediaFileId.MAP_CODEC.forGetter(CachedFile::fileId),
                FILE_NAME_CODEC.fieldOf("file_name").forGetter(CachedFile::fileName),
                ExtraCodecs.INSTANT_ISO8601.fieldOf("last_accessed_at").forGetter(CachedFile::lastAccessedAt)
        ).apply(i, CachedFile::new));
    }
}
