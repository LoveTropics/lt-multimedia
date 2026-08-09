package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.apache.commons.io.IOUtils;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.MultimediaReader;
import org.lovetropics.multimedia.mod.MediaFile;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.playback.AudioWorldSource;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;
import org.lovetropics.multimedia.mod.client.playback.Playback;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.client.playback.VideoFrameTexture;
import org.lovetropics.multimedia.mod.slideshow.SlideContent;
import org.lovetropics.multimedia.mod.slideshow.SlideDecorations;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

public interface PreparedSlide extends AutoCloseable {
    private static CompletableFuture<MultimediaReader> openReaderAsync(final MediaFileCache mediaCache, final MediaFile file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mediaCache.openMultimediaReader(file);
            } catch (final IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    private static CompletableFuture<NativeImage> openImageAsync(final MediaFileCache mediaCache, final MediaFile file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mediaCache.loadImage(file);
            } catch (final IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    static CompletableFuture<PreparedSlide> tryPrepare(final SlideContent content, final MediaFileCache mediaCache, final PlaybackSyncType syncType) {
        return switch (content) {
            case SlideContent.Video(final MediaFile file, final Duration startAt, final Duration ignored, final float volume) ->
                    openReaderAsync(mediaCache, file).thenApplyAsync(
                            reader -> openPlayback(syncType, startAt, volume, reader),
                            Minecraft.getInstance()
                    );
            case SlideContent.Image(final MediaFile file, final SlideDecorations decorations, final Duration ignored) ->
                    openImageAsync(mediaCache, file).thenApplyAsync(
                            image -> uploadImage(image, decorations),
                            Minecraft.getInstance()
                    );
            case final SlideContent.Blank blank ->
                    CompletableFuture.completedFuture(new Blank(PreparedSlideDecorations.prepare(blank.decorations())));
        };
    }

    private static Video openPlayback(final PlaybackSyncType syncType, final Duration startAt, final float volume, final MultimediaReader reader) {
        try {
            final Playback playback = Playback.open(reader, null, syncType);
            final double startAtTime = startAt.toMillis() / 1000.0;
            playback.seekTo(startAtTime);
            return new Video(playback, startAtTime, volume);
        } catch (final IOException | DecoderException e) {
            IOUtils.closeQuietly(reader);
            throw new CompletionException(e);
        }
    }

    private static Image uploadImage(final NativeImage image, final SlideDecorations decorations) {
        final DynamicTexture texture = new DynamicTexture(() -> "Image slide", image);
        final FrameSize imageSize = new FrameSize(image.getWidth(), image.getHeight());
        // We don't need to keep the whole image CPU-side now that we've uploaded it
        texture.setPixels(new NativeImage(1, 1, false));
        final Identifier textureId = MultimediaMod.id("image_slide_" + Image.NEXT_ID.getAndIncrement());
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        return new Image(textureId, texture, imageSize, PreparedSlideDecorations.prepare(decorations));
    }

    void startOrSync(double time, boolean paused);

    double getPlaybackTime();

    void draw(SlideshowGraphics graphics, float alpha);

    void close();

    default void setAudioVolume(final float volume) {
    }

    default void setAudioSource(final AudioWorldSource source) {
    }

    record Video(Playback playback, double startAt, float volume) implements PreparedSlide {
        @Override
        public void startOrSync(final double time, final boolean paused) {
            playback.pause();
            playback.seekTo(Mth.clamp(time + startAt, 0.0, playback.duration()));
            if (!paused) {
                playback.play();
            }
        }

        @Override
        public double getPlaybackTime() {
            return playback.currentTime() - startAt;
        }

        @Override
        public void draw(final SlideshowGraphics graphics, final float alpha) {
            playback.updateWindowSize(graphics.textureFrameSize());

            final VideoFrameTexture texture = playback.updateTexture(RenderSystem.getDevice());
            if (texture == null) {
                return;
            }

            final FrameSize guiSize = texture.frameSize().resizeInto(graphics.frameSize());
            final int x = (graphics.width() - guiSize.width()) / 2;
            final int y = (graphics.height() - guiSize.height()) / 2;
            graphics.blit(texture.location(), x, y, guiSize.width(), guiSize.height(), ARGB.white(alpha));
        }

        @Override
        public void close() {
            playback.close();
        }

        @Override
        public void setAudioVolume(final float volume) {
            playback.setAudioVolume(volume * this.volume);
        }

        @Override
        public void setAudioSource(final AudioWorldSource source) {
            playback.setAudioSource(source);
        }
    }

    record Image(Identifier textureId, DynamicTexture texture, FrameSize imageSize, PreparedSlideDecorations decorations) implements PreparedSlide {
        private static final AtomicInteger NEXT_ID = new AtomicInteger();

        @Override
        public void startOrSync(final double time, final boolean paused) {
        }

        @Override
        public double getPlaybackTime() {
            return Double.NaN;
        }

        @Override
        public void draw(final SlideshowGraphics graphics, final float alpha) {
            final FrameSize guiSize = imageSize.resizeInto(graphics.frameSize());
            final int x = (graphics.width() - guiSize.width()) / 2;
            final int y = (graphics.height() - guiSize.height()) / 2;
            graphics.blit(textureId, x, y, guiSize.width(), guiSize.height(), ARGB.white(alpha));

            decorations.draw(graphics, true, alpha);
        }

        @Override
        public void close() {
            Minecraft.getInstance().getTextureManager().release(textureId);
        }
    }

    record Blank(PreparedSlideDecorations decorations) implements PreparedSlide {
        @Override
        public void startOrSync(final double time, final boolean paused) {
        }

        @Override
        public double getPlaybackTime() {
            return Double.NaN;
        }

        @Override
        public void draw(final SlideshowGraphics graphics, final float alpha) {
            decorations.draw(graphics, false, alpha);
        }

        @Override
        public void close() {
        }
    }
}
