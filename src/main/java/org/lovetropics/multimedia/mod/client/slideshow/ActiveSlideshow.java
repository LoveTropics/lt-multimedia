package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Mth;
import org.apache.commons.io.IOUtils;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;
import org.lovetropics.multimedia.mod.client.playback.Playback;
import org.lovetropics.multimedia.mod.client.playback.VideoFrameTexture;
import org.lovetropics.multimedia.mod.slideshow.Slide;
import org.lovetropics.multimedia.mod.slideshow.SlideTransition;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/* package-private */ class ActiveSlideshow {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MediaFileCache mediaCache;
    private final Slideshow slideshow;

    @Nullable
    private PreparedSlide currentSlide;
    @Nullable
    private PreparedSlide nextSlide;
    private final Queue<Slide> slideQueue = new ArrayDeque<>();

    private float lastFade;
    private float fade;

    public ActiveSlideshow(final MediaFileCache mediaCache, final Slideshow slideshow) {
        this.mediaCache = mediaCache;
        this.slideshow = slideshow;
        slideQueue.addAll(slideshow.slides());
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

        final CompletableFuture<PreparedContent> contentFuture = CompletableFuture.supplyAsync(
                () -> prepareSlideContent(mediaCache, slide, windowSize),
                Util.nonCriticalIoPool()
        ).thenCompose(Function.identity());
        return new PreparedSlide(contentFuture.exceptionally(throwable -> {
            LOGGER.error("An unexpected error occurred while preparing slide", throwable);
            return new ErrorContent();
        }), transitionIn, transitionOut);
    }

    private static CompletableFuture<PreparedContent> prepareSlideContent(final MediaFileCache mediaCache, final Slide slide, final FrameSize windowSize) {
        return switch (slide) {
            case final Slide.Video video -> {
                final SeekableByteChannel channel;
                try {
                    channel = mediaCache.openChannel(video.file());
                } catch (final IOException e) {
                    LOGGER.error("Failed to load video slide", e);
                    yield CompletableFuture.completedFuture(new ErrorContent());
                }
                yield CompletableFuture.supplyAsync(() -> {
                    try {
                        final Playback playback = Playback.open(channel, windowSize);
                        return new VideoContent(playback);
                    } catch (final IOException | DecoderException e) {
                        IOUtils.closeQuietly(channel);
                        LOGGER.error("Failed to load video slide", e);
                        return new ErrorContent();
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
            closeScreen();
            return true;
        }

        if (currentSlide != null) {
            openScreen();
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

    private void openScreen() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            minecraft.setScreen(new SlideshowScreen());
        }
    }

    private void closeScreen() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof SlideshowScreen) {
            minecraft.setScreen(null);
        }
    }

    private void swapSlides() {
        lastFade = 0.0f;
        fade = 0.0f;
        if (currentSlide != null) {
            currentSlide.close();
        }
        if (nextSlide != null) {
            nextSlide.start();
        }
        currentSlide = nextSlide;
        nextSlide = prepareNextSlide(slideshow, currentSlide);
    }

    public void draw(final GuiGraphics graphics, final Font font, final DeltaTracker deltaTracker) {
        if (currentSlide == null && nextSlide == null) {
            return;
        }

        final float fade = Mth.lerp(deltaTracker.getGameTimeDeltaPartialTick(true), lastFade, this.fade);

        final float backgroundAlpha;
        if (currentSlide != null && nextSlide != null) {
            backgroundAlpha = 1.0f;
        } else if (currentSlide != null) {
            backgroundAlpha = 1.0f - fade;
        } else {
            backgroundAlpha = fade;
        }

        // Could definitely be more efficient than just filling the entire screen... :)
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.color(backgroundAlpha, CommonColors.BLACK));

        if (currentSlide != null) {
            currentSlide.draw(graphics, font, 1.0f - fade);
        }
        if (nextSlide != null && fade > 0.0f) {
            nextSlide.draw(graphics, font, fade);
        }
    }

    private static class PreparedSlide {
        private final CompletableFuture<PreparedContent> content;
        private final SlideTransition transitionIn;
        private final SlideTransition transitionOut;
        private boolean forceSwapOut;

        private PreparedSlide(final CompletableFuture<PreparedContent> content, final SlideTransition transitionIn, final SlideTransition transitionOut) {
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
            final PreparedContent content = this.content.getNow(null);
            return content != null && content.isReadyToSwapOut();
        }

        public void start() {
            content.join().start();
        }

        public void draw(final GuiGraphics graphics, final Font font, final float fade) {
            final PreparedContent content = this.content.getNow(null);
            if (content != null) {
                content.draw(graphics, font, fade);
            }
        }

        public void close() {
            content.join().close();
        }
    }

    private interface PreparedContent extends AutoCloseable {
        boolean isReadyToSwapOut();

        void start();

        void draw(GuiGraphics graphics, Font font, float alpha);

        void close();
    }

    private record VideoContent(Playback playback) implements PreparedContent {
        @Override
        public boolean isReadyToSwapOut() {
            return playback.hasStopped();
        }

        @Override
        public void start() {
            playback.play();
        }

        @Override
        public void draw(final GuiGraphics graphics, final Font font, final float alpha) {
            playback.updateWindowSize(FrameSize.from(Minecraft.getInstance().getWindow()));

            final VideoFrameTexture texture = playback.updateTexture(RenderSystem.getDevice());
            if (texture == null) {
                return;
            }

            final FrameSize frameSize = texture.frameSize();
            final FrameSize guiSize = frameSize.resizeInto(new FrameSize(graphics.guiWidth(), graphics.guiHeight()));
            final int x = (graphics.guiWidth() - guiSize.width()) / 2;
            final int y = (graphics.guiHeight() - guiSize.height()) / 2;
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture.location(), x, y, 0.0f, 0.0f, guiSize.width(), guiSize.height(), 1, 1, 1, 1, ARGB.white(alpha));
        }

        @Override
        public void close() {
            playback.close();
        }
    }

    private static class ErrorContent implements PreparedContent {
        private static final Duration DURATION = Duration.ofSeconds(5);
        private static final Component MESSAGE = Component.translatable("slideshow.slide.error");

        private Instant swapAfter = Instant.MAX;

        @Override
        public boolean isReadyToSwapOut() {
            return Instant.now().isAfter(swapAfter);
        }

        @Override
        public void start() {
            swapAfter = Instant.now().plus(DURATION);
        }

        @Override
        public void draw(final GuiGraphics graphics, final Font font, final float alpha) {
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.color(alpha, CommonColors.BLACK));
            graphics.drawCenteredString(font, MESSAGE, graphics.guiWidth() / 2, graphics.guiHeight() / 2, ARGB.color(alpha, CommonColors.RED));
        }

        @Override
        public void close() {
        }
    }
}
