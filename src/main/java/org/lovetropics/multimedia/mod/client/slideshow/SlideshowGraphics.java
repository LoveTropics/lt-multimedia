package org.lovetropics.multimedia.mod.client.slideshow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;

public interface SlideshowGraphics {
    static SlideshowGraphics forGui(final GuiGraphics graphics, final Font font) {
        final FrameSize frameSize = new FrameSize(graphics.guiWidth(), graphics.guiHeight());
        final FrameSize textureFrameSize = FrameSize.from(Minecraft.getInstance().getWindow());

        return new SlideshowGraphics() {
            @Override
            public FrameSize frameSize() {
                return frameSize;
            }

            @Override
            public FrameSize textureFrameSize() {
                return textureFrameSize;
            }

            @Override
            public void fill(final int x, final int y, final int width, final int height, final int color) {
                graphics.fill(x, y, x + width, y + height, color);
            }

            @Override
            public void blit(final ResourceLocation location, final int x, final int y, final int width, final int height, final int color) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, location, x, y, 0, 0, width, height, 1, 1, 1, 1, color);
            }

            @Override
            public void drawCenteredText(final Component text, final int x, final int y, final int color) {
                graphics.drawCenteredString(font, text, x, y, color);
            }
        };
    }

    default int width() {
        return frameSize().width();
    }

    default int height() {
        return frameSize().height();
    }

    FrameSize frameSize();

    FrameSize textureFrameSize();

    void fill(int x, int y, int width, int height, int color);

    void blit(ResourceLocation location, int x, int y, int width, int height, int color);

    void drawCenteredText(Component text, int x, int y, int color);
}
