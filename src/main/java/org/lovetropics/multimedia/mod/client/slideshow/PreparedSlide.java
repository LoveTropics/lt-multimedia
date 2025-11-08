package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.lovetropics.multimedia.mod.client.playback.AudioWorldSource;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;
import org.lovetropics.multimedia.mod.client.playback.Playback;
import org.lovetropics.multimedia.mod.client.playback.VideoFrameTexture;

public interface PreparedSlide extends AutoCloseable {
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

    record Text(Component text) implements PreparedSlide {
        @Override
        public void startOrSync(final double time, final boolean paused) {
        }

        @Override
        public double getPlaybackTime() {
            return Double.NaN;
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
