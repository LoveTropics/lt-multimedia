package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.apache.commons.io.IOUtils;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.playback.AudioWorldSource;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;
import org.lovetropics.multimedia.mod.client.playback.Playback;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.slideshow.Slide;
import org.lovetropics.multimedia.mod.slideshow.SlideContent;
import org.lovetropics.multimedia.mod.slideshow.SlideTransition;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class SlideshowDriver implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MediaFileCache mediaCache;
    private final Slideshow slideshow;
    private final PlaybackSyncType syncType;

    private float audioVolume = 1.0f;
    @Nullable
    private AudioWorldSource audioSource;

    @Nullable
    private PreparedSlide currentSlide;
    @Nullable
    private PreparedSlide nextSlide;
    private final Queue<Slide> slideQueue = new ArrayDeque<>();

    private float lastFade;
    private float fade;

    public SlideshowDriver(final MediaFileCache mediaCache, final Slideshow slideshow, final PlaybackSyncType syncType) {
        this.mediaCache = mediaCache;
        this.slideshow = slideshow;
        slideQueue.addAll(slideshow.slides());
        this.syncType = syncType;
    }

    public void setAudioVolume(final float volume) {
        if (volume == audioVolume) {
            return;
        }
        audioVolume = volume;
        if (currentSlide != null) {
            currentSlide.setAudioVolume(volume);
        }
    }

    public void setAudioSource(final AudioWorldSource source) {
        if (source.equals(audioSource)) {
            return;
        }
        audioSource = source;
        if (currentSlide != null) {
            currentSlide.setAudioSource(audioSource);
        }
    }

    @Nullable
    private PreparedSlide prepareNextSlide(final Slideshow slideshow, @Nullable final PreparedSlide currentSlide) {
        final Slide slide = slideQueue.poll();
        if (slide != null) {
            return prepareSlide(slideshow, currentSlide, slide);
        }
        return null;
    }

    private PreparedSlide prepareSlide(final Slideshow slideshow, @Nullable final PreparedSlide previousSlide, final Slide slide) {
        final Window window = Minecraft.getInstance().getWindow();
        final FrameSize windowSize = FrameSize.from(window);

        final SlideTransition previousTransitionOut = previousSlide != null ? previousSlide.transitionOut : slideshow.defaultTransition();
        final SlideTransition transitionIn = slide.transitionIn().orElse(previousTransitionOut);
        final SlideTransition transitionOut = slide.transitionOut().orElse(slideshow.defaultTransition());

        final CompletableFuture<PreparedSlideContent> contentFuture = CompletableFuture.supplyAsync(
                () -> prepareSlideContent(mediaCache, slide.content(), windowSize, syncType),
                Util.nonCriticalIoPool()
        ).thenCompose(Function.identity());
        return new PreparedSlide(contentFuture.exceptionally(throwable -> {
            LOGGER.error("An unexpected error occurred while preparing slide", throwable);
            return new PreparedSlideContent.Error();
        }), transitionIn, transitionOut);
    }

    private static CompletableFuture<PreparedSlideContent> prepareSlideContent(final MediaFileCache mediaCache, final SlideContent slide, final FrameSize windowSize, final PlaybackSyncType syncType) {
        return switch (slide) {
            case final SlideContent.Video video -> {
                final SeekableByteChannel channel;
                try {
                    channel = mediaCache.openChannel(video.file());
                } catch (final IOException e) {
                    LOGGER.error("Failed to load video slide", e);
                    yield CompletableFuture.completedFuture(new PreparedSlideContent.Error());
                }
                yield CompletableFuture.supplyAsync(() -> {
                    try {
                        final Playback playback = Playback.open(channel, windowSize, syncType);
                        playback.seekTo(video.startAt().toMillis() / 1000.0);
                        return new PreparedSlideContent.Video(playback, video.volume());
                    } catch (final IOException | DecoderException e) {
                        IOUtils.closeQuietly(channel);
                        LOGGER.error("Failed to load video slide", e);
                        return new PreparedSlideContent.Error();
                    }
                }, Minecraft.getInstance());
            }
        };
    }

    public void clear() {
        slideQueue.clear();
        if (currentSlide != null) {
            currentSlide.forceSwapOut();
        }
        nextSlide = null;
    }

    public boolean tick() {
        if (nextSlide == null) {
            nextSlide = prepareNextSlide(slideshow, currentSlide);
        }

        if (currentSlide == null && nextSlide == null) {
            return true;
        }

        if ((currentSlide == null || currentSlide.isReadyToSwapOut()) && (nextSlide == null || nextSlide.isReadyToSwapIn())) {
            final SlideTransition transition = nextSlide != null ? nextSlide.transitionIn : currentSlide.transitionOut;
            lastFade = fade;
            fade += SharedConstants.MILLIS_PER_TICK / (float) transition.duration().toMillis();
            if (fade >= 1.0f) {
                swapSlides();
            }
        }

        return false;
    }

    private void swapSlides() {
        lastFade = 0.0f;
        fade = 0.0f;
        if (currentSlide != null) {
            currentSlide.close();
        }
        if (nextSlide != null) {
            setupSlideAudio(nextSlide);
            nextSlide.start();
        }
        currentSlide = nextSlide;
        nextSlide = prepareNextSlide(slideshow, currentSlide);
    }

    private void setupSlideAudio(final PreparedSlide slide) {
        slide.setAudioVolume(audioVolume);
        if (audioSource != null) {
            slide.setAudioSource(audioSource);
        }
    }

    @Nullable
    public SlideshowRenderState extractRenderState(final float partialTicks) {
        final PreparedSlideContent currentSlide = this.currentSlide != null ? this.currentSlide.getContentNow() : null;
        final PreparedSlideContent nextSlide = this.nextSlide != null ? this.nextSlide.getContentNow() : null;
        if (currentSlide == null && nextSlide == null) {
            return null;
        }
        return new SlideshowRenderState(currentSlide, nextSlide, Mth.lerp(partialTicks, lastFade, fade));
    }

    public boolean hasFadedIn() {
        return currentSlide != null;
    }

    @Override
    public void close() {
        if (currentSlide != null) {
            currentSlide.close();
            currentSlide = null;
        }
        if (nextSlide != null) {
            nextSlide.close();
            nextSlide = null;
        }
    }

    public Slideshow slideshow() {
        return slideshow;
    }

    private static class PreparedSlide {
        private final CompletableFuture<PreparedSlideContent> content;
        private final SlideTransition transitionIn;
        private final SlideTransition transitionOut;
        private boolean forceSwapOut;

        private PreparedSlide(final CompletableFuture<PreparedSlideContent> content, final SlideTransition transitionIn, final SlideTransition transitionOut) {
            this.content = content;
            this.transitionIn = transitionIn;
            this.transitionOut = transitionOut;
        }

        public void forceSwapOut() {
            forceSwapOut = true;
        }

        public boolean isReadyToSwapIn() {
            return content.isDone();
        }

        public boolean isReadyToSwapOut() {
            if (forceSwapOut) {
                return true;
            }
            final PreparedSlideContent content = getContentNow();
            return content != null && content.isReadyToSwapOut();
        }

        public void setAudioVolume(final float volume) {
            content.join().setAudioVolume(volume);
        }

        public void setAudioSource(final AudioWorldSource source) {
            content.join().setAudioSource(source);
        }

        public void start() {
            content.join().start();
        }

        public void close() {
            content.join().close();
        }

        @Nullable
        public PreparedSlideContent getContentNow() {
            return content.getNow(null);
        }
    }
}
