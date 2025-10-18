package org.lovetropics.multimedia.mod.client.cache;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/* package-private */ class CachedFile {
    private final MediaFileId fileId;
    private final Path path;
    @Nullable
    private final FileDownload download;

    private Instant lastAccessedAt;

    private CachedFile(
            final MediaFileId fileId,
            final Path path,
            @Nullable final FileDownload download,
            final Instant lastAccessedAt
    ) {
        this.fileId = fileId;
        this.path = path;
        this.download = download;
        this.lastAccessedAt = lastAccessedAt;
    }

    public static CachedFile startDownload(final MediaFileId fileId, final Path path, final InputStream input) {
        final FileDownload download = FileDownload.start(path, input, fileId.size());
        return new CachedFile(fileId, path, download, Instant.now());
    }

    @Nullable
    public static CachedFile fromCache(final Path rootPath, final MediaIndex.CachedFile file) {
        final Path path = rootPath.resolve(file.fileName());
        if (!Files.exists(path)) {
            return null;
        }
        return new CachedFile(
                file.fileId(),
                path,
                null,
                file.lastAccessedAt()
        );
    }

    public MediaFileId fileId() {
        return fileId;
    }

    public Path path() {
        return path;
    }

    public Duration getTimeSinceLastAccess() {
        return Duration.between(lastAccessedAt, Instant.now());
    }

    public void touch() {
        lastAccessedAt = Instant.now();
    }

    public CompletionStage<Void> awaitDownload() {
        return download != null ? download.awaitComplete() : CompletableFuture.completedFuture(null);
    }

    public boolean isDownloaded() {
        return download == null || download.isComplete();
    }

    public MediaIndex.CachedFile asIndexFile(final Path rootPath) {
        return new MediaIndex.CachedFile(fileId, rootPath.relativize(path).toString(), lastAccessedAt);
    }

    public InputStream openInputStream() throws IOException {
        if (download != null) {
            return download.openInputStream();
        }
        return Files.newInputStream(path);
    }
}
