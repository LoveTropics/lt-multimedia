package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import org.lovetropics.multimedia.mod.client.playback.AudioWorldSource;
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

    default void setAudioVolume(final float volume) {
    }

    default void setAudioSource(final AudioWorldSource source) {
    }

    record Video(Playback playback, float volume) implements PreparedSlideContent {
        @Override
        public boolean isReadyToSwapOut() {
            return playback.currentTime() >= playback.duration();
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

        @Override
        public void setAudioVolume(final float volume) {
            playback.setAudioVolume(volume * this.volume);
        }

        @Override
        public void setAudioSource(final AudioWorldSource source) {
            playback.setAudioSource(source);
        }
    }

    class Text implements PreparedSlideContent {
        private final Component text;
        private final Duration duration;
        private Instant swapAfter = Instant.MAX;

        public Text(final Component text, final Duration duration) {
            this.text = text;
            this.duration = duration;
        }

        @Override
        public boolean isReadyToSwapOut() {
            return Instant.now().isAfter(swapAfter);
        }

        @Override
        public void start() {
            swapAfter = Instant.now().plus(duration);
        }

        @Override
        public void draw(final SlideshowGraphics graphics, final float alpha) {
            graphics.drawCenteredText(text, graphics.width() / 2, graphics.height() / 2, ARGB.white(alpha));
        }

        @Override
        public void close() {
        }
    }
}
