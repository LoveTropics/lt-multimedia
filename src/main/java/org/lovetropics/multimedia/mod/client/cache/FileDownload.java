package org.lovetropics.multimedia.mod.client.cache;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/* package-private */ class FileDownload implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path path;
    private final long size;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition canRead = lock.newCondition();

    @Nullable
    private RandomAccessFile file;
    private long writeIndex;

    private boolean writerOpen = true;
    private int readersOpen;

    private final CompletableFuture<Void> completeFuture = new CompletableFuture<>();

    private FileDownload(final Path path, final long size) {
        this.path = path;
        this.size = size;
    }

    public static FileDownload start(final Path path, final InputStream input, final long size) {
        final FileDownload download = new FileDownload(path, size);
        Util.nonCriticalIoPool().execute(() -> download.writeFrom(input));
        return download;
    }

    private void writeFrom(final InputStream input) {
        try {
            lock.lock();
            try {
                file = new RandomAccessFile(path.toFile(), "rw");
                file.setLength(size);
            } finally {
                lock.unlock();
            }

            final byte[] buffer = new byte[1024 * 8];
            int read;
            while ((read = input.read(buffer, 0, buffer.length)) >= 0) {
                lock.lock();
                try {
                    file.seek(writeIndex);
                    file.write(buffer, 0, read);
                    writeIndex += read;
                    canRead.signal();
                } finally {
                    lock.unlock();
                }
            }
            completeFuture.complete(null);
        } catch (final Exception e) {
            LOGGER.error("An error occurred while downloading file", e);
            completeFuture.completeExceptionally(e);
        } finally {
            closeWriter();
            IOUtils.closeQuietly(input);
        }
    }

    public CompletionStage<Void> awaitComplete() {
        return completeFuture;
    }

    public boolean isComplete() {
        return completeFuture.state() == Future.State.SUCCESS;
    }

    public InputStream openInputStream() throws IOException {
        lock.lock();
        try {
            // The RandomAccessFile is already closed or will be soon, just read directly from the file
            if (!writerOpen) {
                return Files.newInputStream(path);
            }
            readersOpen++;
        } finally {
            lock.unlock();
        }

        return new InputStream() {
            private long readIndex;
            private boolean closed;

            @Override
            public int read() throws IOException {
                final byte[] b = new byte[1];
                if (read(b, 0, 1) == -1) {
                    return -1;
                }
                return b[0] & 0xff;
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                lock.lock();
                try {
                    while (readIndex < size) {
                        final int readBytes = tryRead(b, off, len);
                        if (readBytes == 0) {
                            if (!writerOpen) {
                                return -1;
                            }
                            canRead.awaitUninterruptibly();
                        } else {
                            return readBytes;
                        }
                    }
                } finally {
                    lock.unlock();
                }
                return -1;
            }

            private int tryRead(final byte[] b, final int off, final int len) throws IOException {
                final long availableBytes = writeIndex - readIndex;
                if (availableBytes <= 0) {
                    return 0;
                }
                final RandomAccessFile file = FileDownload.this.file;
                if (file == null) {
                    return 0;
                }
                file.seek(readIndex);
                final int readBytes = file.read(b, off, (int) Math.min(len, availableBytes));
                if (readBytes != -1) {
                    readIndex += readBytes;
                }
                return readBytes;
            }

            @Override
            public long skip(final long n) {
                if (n <= 0) {
                    return 0;
                }
                final long skippedBytes = Math.min(n, size - readIndex);
                readIndex += skippedBytes;
                return skippedBytes;
            }

            @Override
            public int available() {
                return (int) Math.min(writeIndex - readIndex, Integer.MAX_VALUE);
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                closeReader();
            }
        };
    }

    private void closeWriter() {
        lock.lock();
        try {
            writerOpen = false;
            if (readersOpen > 0) {
                canRead.signal();
            } else {
                close();
            }
        } finally {
            lock.unlock();
        }
    }

    private void closeReader() {
        lock.lock();
        try {
            if (--readersOpen == 0) {
                if (!writerOpen) {
                    close();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (file != null) {
                IOUtils.closeQuietly(file);
                file = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
