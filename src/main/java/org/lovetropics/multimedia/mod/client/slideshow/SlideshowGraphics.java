package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.lovetropics.multimedia.mod.client.ScreenSizeCapture;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;

public interface SlideshowGraphics {
    double LOG_2 = Math.log(2.0);

    static float roundLog2(final float value) {
        return (float) Math.pow(2.0, Mth.ceil(Math.log(value) / LOG_2));
    }

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

    @Nullable
    static SlideshowGraphics forWorld(final PoseStack.Pose pose, final float worldWidth, final float worldHeight, final int lightCoords, final MultiBufferSource bufferSource, final Font font) {
        final FrameSize frameSize = new FrameSize(Math.round(worldWidth * 16), Math.round(worldHeight * 16));

        final Vector2fc screenSizePixels = ScreenSizeCapture.sizeInScreenPixels(pose,
                new Vector3f(-worldWidth / 2.0f, -worldHeight / 2.0f, 0.0f),
                new Vector3f(worldWidth / 2.0f, -worldHeight / 2.0f, 0.0f),
                new Vector3f(worldWidth / 2.0f, worldHeight / 2.0f, 0.0f),
                new Vector3f(-worldWidth / 2.0f, worldHeight / 2.0f, 0.0f)
        );
        if (screenSizePixels == null) {
            return null;
        }

        final float textureScale = roundLog2(Math.max(
                screenSizePixels.x() / frameSize.width(),
                screenSizePixels.y() / frameSize.height()
        ));
        final FrameSize textureFrameSize = frameSize.scale(textureScale);

        final PoseStack poseStack = new PoseStack();
        poseStack.mulPose(pose.pose());

        return new SlideshowGraphics() {
            private static final float Z_OFFSET = 0.01f;

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
                addQuad(x, y, width, height, color, bufferSource.getBuffer(RenderType.textBackground()));
            }

            @Override
            public void blit(final ResourceLocation location, final int x, final int y, final int width, final int height, final int color) {
                addQuad(x, y, width, height, color, bufferSource.getBuffer(RenderType.entityTranslucent(location)));
            }

            private void addQuad(final float x, final float y, final float width, final float height, final int color, final VertexConsumer buffer) {
                final float x0 = -worldWidth / 2.0f + x / frameSize.width() * worldWidth;
                final float y0 = -worldHeight / 2.0f + y / frameSize.height() * worldHeight;
                final float x1 = x0 + width / frameSize.width() * worldWidth;
                final float y1 = y0 + height / frameSize.height() * worldHeight;
                final PoseStack.Pose pose = poseStack.last();
                buffer.addVertex(pose, x0, y0, 0.0f).setUv(0.0f, 1.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                buffer.addVertex(pose, x1, y0, 0.0f).setUv(1.0f, 1.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                buffer.addVertex(pose, x1, y1, 0.0f).setUv(1.0f, 0.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                buffer.addVertex(pose, x0, y1, 0.0f).setUv(0.0f, 0.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                poseStack.translate(0.0f, 0.0f, Z_OFFSET);
            }

            @Override
            public void drawCenteredText(final Component text, final int x, final int y, final int color) {
                poseStack.pushPose();
                poseStack.scale(1.0f / 16.0f, -1.0f / 16.0f, 1.0f / 16.0f);
                font.drawInBatch(text,
                        -worldWidth / 2.0f + (float) x / frameSize.width() * worldWidth - font.width(text) / 2.0f,
                        -worldHeight / 2.0f + (float) y / frameSize.height() * worldHeight - font.lineHeight / 2.0f,
                        color,
                        true,
                        poseStack.last().pose(),
                        bufferSource,
                        Font.DisplayMode.NORMAL,
                        0,
                        lightCoords
                );
                poseStack.translate(0.0f, 0.0f, Z_OFFSET);
                poseStack.popPose();
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
