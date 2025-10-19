package org.lovetropics.multimedia;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

public class MultimediaNative {
    static {
        Loader.load("multimedia");
    }

    public static final int PACKET_VIDEO = 0;
    public static final int PACKET_AUDIO = 1;

    public static final int AUDIO_FORMAT_U8 = 0;
    public static final int AUDIO_FORMAT_I16 = 1;
    public static final int AUDIO_FORMAT_I32 = 2;
    public static final int AUDIO_FORMAT_I64 = 3;
    public static final int AUDIO_FORMAT_F32 = 4;
    public static final int AUDIO_FORMAT_F64 = 5;

    public static native long openReader(InputStream input) throws IOException;

    public static native void destroyReader(long reader) throws IOException;

    public static native long readPacket(long reader) throws IOException;

    public static native void destroyPacket(long packet);

    public static native int getPacketType(long packet);

    public static native long openVideoDecoder(long reader) throws IOException, DecoderException;

    public static native void destroyVideoDecoder(long videoDecoder);

    public static native int getVideoWidth(long videoDecoder);

    public static native int getVideoHeight(long videoDecoder);

    public static native void sendVideoPacket(long videoDecoder, long packet) throws DecoderException;

    public static native long readVideoFrame(long videoDecoder) throws DecoderException;

    public static native double getVideoFramePresentTime(long videoFrame);

    public static native double getVideoFramePresentEndTime(long videoFrame);

    public static native void unpackVideoPixels(long videoFrame, int outputWidth, int outputHeight, ByteBuffer output, int offset) throws DecoderException;

    public static native void destroyVideoFrame(long videoFrame);

    public static native long openAudioDecoder(long reader, int sampleFormat, boolean stereo, int sampleRate) throws IOException, DecoderException;

    public static native void destroyAudioDecoder(long audioDecoder);

    public static native void sendAudioPacket(long audioDecoder, long packet) throws DecoderException;

    public static native long readAudioFrame(long audioDecoder) throws DecoderException;

    public static native double getAudioFramePresentTime(long audioFrame);

    public static native int getAudioFrameSamples(long audioFrame);

    public static native int getAudioFrameBytes(long audioFrame);

    public static native void unpackAudioSamples(long audioFrame, ByteBuffer output, int offset) throws DecoderException;

    public static native void destroyAudioFrame(long audioFrame);

    private static class Loader {
        private static final Path UNPACK_ROOT = prepareUnpackRoot();

        private static Path prepareUnpackRoot() {
            final Path path = Path.of(System.getProperty("java.io.tmpdir")).resolve("lt-multimedia-natives");
            if (Files.exists(path)) {
                try {
                    Files.walkFileTree(path, Set.of(), 1, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (final IOException ignored) {
                }
            } else {
                try {
                    Files.createDirectory(path);
                } catch (final IOException e) {
                    throw new LinkageError("Could not create directory for native unpacking", e);
                }
            }
            return path;
        }

        public static void load(final String libraryName) {
            final Path path = unpackLibrary(UNPACK_ROOT, libraryName, Platform.detect());
            System.load(path.toAbsolutePath().toString());
            try {
                Files.deleteIfExists(path);
            } catch (final IOException ignored) {
                // Will fail on Windows, but will be cleaned up the next time the application boots
            }
        }

        private static Path unpackLibrary(final Path root, final String libraryName, final Platform platform) {
            final String libraryPath = platform.getLibraryPath(libraryName);
            try (final InputStream input = Loader.class.getClassLoader().getResourceAsStream(libraryPath)) {
                if (input == null) {
                    throw new LinkageError("Missing native library at " + libraryPath + " for platform " + platform.classifier);
                }
                final Path path = Files.createTempFile(root, libraryName, null);
                Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
                return path;
            } catch (final IOException e) {
                throw new LinkageError("Unable to load native library at " + libraryPath, e);
            }
        }
    }
}
