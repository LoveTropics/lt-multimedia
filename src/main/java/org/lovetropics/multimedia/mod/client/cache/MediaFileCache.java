package org.lovetropics.multimedia.mod.client.cache;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import net.minecraft.util.thread.ConsecutiveExecutor;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.lovetropics.multimedia.MultimediaReader;
import org.lovetropics.multimedia.mod.MediaFile;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MediaFileCache {
    private static final Logger LOGGER = LogUtils.getLogger();

    // We could parse the Cache-Control header, but for now this is fine
    private static final Duration ALWAYS_FRESH_BEFORE = Duration.ofMinutes(5);
    private static final Duration ALWAYS_CHECK_AFTER = Duration.ofHours(1);
    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofDays(2);

    private final Path rootPath;
    private final String userAgent;

    private final Path indexPath;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .executor(Util.nonCriticalIoPool())
            .build();

    private final ConsecutiveExecutor consecutiveExecutor = new ConsecutiveExecutor(Util.ioPool(), "lt-media-cache");

    private final List<CachedFile> files = new ArrayList<>();
    private final Map<URI, CompletableFuture<CachedFile>> pendingRequests = new HashMap<>();

    public MediaFileCache(final Path rootPath, final String userAgent) {
        this.rootPath = rootPath;
        indexPath = rootPath.resolve("index.json");
        this.userAgent = userAgent;

        consecutiveExecutor.schedule(() -> {
            try {
                Files.createDirectories(rootPath);
            } catch (final IOException e) {
                LOGGER.error("Failed to create cache directory: {}", rootPath, e);
            }

            final MediaIndex index = MediaIndex.load(indexPath);
            for (final MediaIndex.CachedFile indexFile : index.cachedFiles()) {
                final CachedFile file = CachedFile.fromCache(rootPath, indexFile);
                if (file != null) {
                    files.add(file);
                }
            }

            // We could run this more often than every startup - but this is good enough for now
            cleanCache();
        });
    }

    private void cleanCache() {
        files.removeIf(file -> file.getTimeSinceLastAccess().compareTo(EXPIRE_AFTER_ACCESS) > 0);
        cleanOldVersions();
        storeIndex();

        deleteUnreferencedFiles();
    }

    private void cleanOldVersions() {
        final Map<URI, List<CachedFile>> filesByUri = files.stream()
                .collect(Collectors.groupingBy(file -> file.fileId().uri()));

        filesByUri.forEach((uri, files) -> {
            if (files.size() < 2) {
                return;
            }
            final CachedFile latestFile = files.stream()
                    .max(Comparator.comparing(f -> f.fileId().resolvedAt()))
                    .orElse(null);
            for (final CachedFile file : files) {
                if (file != latestFile) {
                    this.files.remove(file);
                }
            }
        });
    }

    private void deleteUnreferencedFiles() {
        final Set<Path> knownPaths = new HashSet<>();
        knownPaths.add(indexPath);
        for (final CachedFile file : files) {
            knownPaths.add(file.path());
        }
        try {
            deleteAllExcept(rootPath, knownPaths);
        } catch (final IOException e) {
            LOGGER.error("Failed to delete cache files", e);
        }
    }

    private static void deleteAllExcept(final Path rootPath, final Set<Path> knownFiles) throws IOException {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                return dir.equals(rootPath) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (!knownFiles.contains(file)) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public CompletableFuture<Void> ensureDownloaded(final MediaFile file) {
        if (file.isLocalFile()) {
            return CompletableFuture.completedFuture(null);
        }
        return getOrDownload(file.uri()).thenCompose(CachedFile::awaitDownload);
    }

    public MultimediaReader openMultimediaReader(final MediaFile file) throws IOException {
        final SeekableByteChannel channel = openChannel(file);
        try {
            return MultimediaReader.open(channel);
        } catch (final IOException e) {
            IOUtils.closeQuietly(channel);
            throw e;
        }
    }

    public NativeImage loadImage(final MediaFile file) throws IOException {
        try (final SeekableByteChannel channel = openChannel(file)) {
            final ByteBuffer buffer = BufferUtils.createByteBuffer(Math.toIntExact(channel.size()));
            while (buffer.hasRemaining()) {
                channel.read(buffer);
            }
            buffer.flip();
            return NativeImage.read(buffer);
        }
    }

    public SeekableByteChannel openChannel(final MediaFile file) throws IOException {
        if (file.isLocalFile() && MediaFile.CAN_PLAY_FROM_LOCAL_FILE) {
            return Files.newByteChannel(Path.of(file.uri()));
        }
        final CachedFile cached;
        try {
            cached = getOrDownload(file.uri()).join();
        } catch (final CompletionException e) {
            switch (e.getCause()) {
                case final IOException cause -> throw cause;
                case final Throwable cause -> throw new IOException(cause);
            }
        }
        return cached.openChannel();
    }

    private CompletableFuture<CachedFile> getOrDownload(final URI uri) {
        return CompletableFuture.supplyAsync(
                () -> {
                    final CompletableFuture<CachedFile> pendingRequest = pendingRequests.get(uri);
                    if (pendingRequest != null) {
                        return pendingRequest;
                    }
                    final CompletableFuture<CachedFile> future = getOrDownloadInternal(uri);
                    pendingRequests.put(uri, future);
                    future.thenRunAsync(() -> pendingRequests.remove(uri, future), consecutiveExecutor::schedule);
                    return future;
                },
                consecutiveExecutor::schedule
        ).thenCompose(Function.identity());
    }

    private CompletableFuture<CachedFile> getOrDownloadInternal(final URI uri) {
        final List<CachedFile> candidateFiles = streamFilesForUri(uri).toList();

        // We don't have any files cached for this URI
        if (candidateFiles.isEmpty()) {
            return requestDownload(uri, List.of());
        }

        final CachedFile latestFile = candidateFiles.stream()
                .max(Comparator.comparing(f -> f.fileId().resolvedAt()))
                .get();
        final Duration latestFileAge = latestFile.fileId().getCurrentAge();

        // We downloaded this file very recently, don't bother checking the ETag at all
        if (latestFileAge.compareTo(ALWAYS_FRESH_BEFORE) <= 0) {
            return useCachedFile(latestFile);
        }

        final List<String> candidateEtags = candidateFiles.stream()
                .flatMap(cached -> cached.fileId().etag().stream())
                .toList();

        // We have some file cached, but it never had an ETag
        if (candidateEtags.isEmpty() && latestFileAge.compareTo(ALWAYS_CHECK_AFTER) <= 0) {
            return useCachedFile(latestFile);
        }

        return requestDownload(uri, candidateEtags);
    }

    private Stream<CachedFile> streamFilesForUri(final URI uri) {
        return files.stream().filter(cached -> cached.fileId().uri().equals(uri));
    }

    private CompletableFuture<CachedFile> useCachedFile(final CachedFile file) {
        file.touch();
        storeIndex();
        return CompletableFuture.completedFuture(file);
    }

    private CompletableFuture<CachedFile> requestDownload(final URI uri, final List<String> ifNoneMatch) {
        final HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET()
                .header("User-Agent", userAgent);
        if (!ifNoneMatch.isEmpty()) {
            request.header("If-None-Match", String.join(", ", ifNoneMatch));
        }

        return httpClient.sendAsync(request.build(), FileDownload.bodyHandler()).thenApplyAsync(response -> {
            try {
                return handleDownloadResponse(uri, response);
            } catch (final IOException e) {
                throw new CompletionException(e);
            }
        }, consecutiveExecutor::schedule);
    }

    private CachedFile handleDownloadResponse(final URI uri, final HttpResponse<FileDownload.Response> response) throws IOException {
        final Optional<String> etag = response.headers().firstValue("ETag");

        if (response.statusCode() == HttpResponseStatus.NOT_MODIFIED.code()) {
            final CachedFile matchingFile = streamFilesForUri(uri)
                    .filter(cached -> cached.fileId().etag().equals(etag))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Got NOT_MODIFIED, but ETag (" + etag + ") did not match any file we have locally"));
            matchingFile.touch();
            storeIndex();
            return matchingFile;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Unexpected status code " + response.statusCode());
        }

        return startDownload(uri, etag, response.body());
    }

    private CachedFile startDownload(final URI uri, final Optional<String> etag, final FileDownload.Response body) throws IOException {
        final Path path = selectFilePath(uri);
        final FileDownload download = body.startWritingTo(path);

        final MediaFileId fileId = new MediaFileId(uri, etag, download.size(), Instant.now());
        final CachedFile file = CachedFile.fromDownload(fileId, download);
        files.add(file);

        file.awaitDownload().thenRunAsync(this::storeIndex, consecutiveExecutor::schedule);

        return file;
    }

    private Path selectFilePath(final URI uri) {
        String fileName = FilenameUtils.getName(uri.getPath());
        if (fileName.isBlank()) {
            fileName = "media";
        }

        final String baseName = FileUtil.sanitizeName(FilenameUtils.removeExtension(fileName));
        final String extension = FilenameUtils.getExtension(fileName);

        int index = 1;
        Path newPath;
        do {
            String newFileName = baseName + "_" + index;
            if (!extension.isEmpty()) {
                newFileName += "." + extension;
            }
            newPath = rootPath.resolve(newFileName);
            index++;
        } while (Files.exists(newPath));

        return newPath;
    }

    private void storeIndex() {
        final List<MediaIndex.CachedFile> downloadedFiles = files.stream()
                .filter(CachedFile::isDownloaded)
                .map(file -> file.asIndexFile(rootPath))
                .toList();
        final MediaIndex index = new MediaIndex(downloadedFiles);
        index.store(indexPath);
    }
}
