package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.CommonColors;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;
import org.lovetropics.multimedia.mod.client.playback.Playback;
import org.lovetropics.multimedia.mod.client.playback.VideoFrameTexture;

import java.time.Duration;
import java.time.Instant;

public interface PreparedSlideContent extends AutoCloseable {
    boolean isReadyToSwapOut();

    void start();

    void draw(SlideshowGraphics graphics, float alpha);

    void close();

    record Video(Playback playback) implements PreparedSlideContent {
        @Override
        public boolean isReadyToSwapOut() {
            return playback.hasStopped();
        }

        @Override
        public void start() {
            playback.play();
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
    }

    class Error implements PreparedSlideContent {
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
        public void draw(final SlideshowGraphics graphics, final float alpha) {
            graphics.drawCenteredText(MESSAGE, graphics.width() / 2, graphics.height() / 2, ARGB.color(alpha, CommonColors.RED));
        }

        @Override
        public void close() {
        }
    }
}
