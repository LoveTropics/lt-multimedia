package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.lovetropics.multimedia.mod.client.ScreenSizeCapture;
import org.lovetropics.multimedia.mod.client.playback.FrameSize;

public interface SlideshowGraphics {
    double LOG_2 = Math.log(2.0);
    FrameSize BASE_GUI_SIZE = new FrameSize(Window.BASE_WIDTH, Window.BASE_HEIGHT);

    static float roundLog2(final float value) {
        return (float) Math.pow(2.0, Mth.ceil(Math.log(value) / LOG_2));
    }

    static SlideshowGraphics forGui(final GuiGraphicsExtractor graphics, final Font font) {
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
            public void blit(final Identifier location, final int x, final int y, final int width, final int height, final int color) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, location, x, y, 0, 0, width, height, 1, 1, 1, 1, color);
            }

            @Override
            public void drawText(final FormattedCharSequence text, final int x, final int y, final int color, final int scale) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(x, y);
                graphics.pose().scale(scale);
                graphics.text(font, text, 0, 0, color);
                graphics.pose().popMatrix();
            }

            @Override
            public Font font() {
                return font;
            }
        };
    }

    @Nullable
    static SlideshowGraphics forWorld(final PoseStack.Pose pose, final float worldWidth, final float worldHeight, final int lightCoords, final SubmitNodeCollector submitNodeCollector, final Font font) {
        final FrameSize frameSize = new FrameSize(Math.round(worldWidth * 16), Math.round(worldHeight * 16)).resizeInto(BASE_GUI_SIZE);

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
            private static final float Z_OFFSET = 0.001f;

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
                addQuad(x, y, width, height, color, RenderTypes.textBackground());
            }

            @Override
            public void blit(final Identifier location, final int x, final int y, final int width, final int height, final int color) {
                addQuad(x, y, width, height, color, RenderTypes.entityTranslucent(location));
            }

            private void addQuad(int x, int y, int width, int height, int color, RenderType renderType) {
                submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
                    final float x0 = -worldWidth / 2.0f + (float) x / frameSize.width() * worldWidth;
                    final float y0 = -worldHeight / 2.0f + (float) y / frameSize.height() * worldHeight;
                    final float x1 = x0 + (float) width / frameSize.width() * worldWidth;
                    final float y1 = y0 + (float) height / frameSize.height() * worldHeight;
                    buffer.addVertex(pose, x0, y0, 0.0f).setUv(0.0f, 1.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                    buffer.addVertex(pose, x1, y0, 0.0f).setUv(1.0f, 1.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                    buffer.addVertex(pose, x1, y1, 0.0f).setUv(1.0f, 0.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                    buffer.addVertex(pose, x0, y1, 0.0f).setUv(0.0f, 0.0f).setLight(lightCoords).setNormal(pose, 0.0f, 0.0f, -1.0f).setColor(color).setOverlay(OverlayTexture.NO_OVERLAY);
                });
                poseStack.translate(0.0f, 0.0f, Z_OFFSET);
            }

            @Override
            public void drawText(final FormattedCharSequence text, final int x, final int y, final int color, final int scale) {
                poseStack.pushPose();
                poseStack.translate(
                        ((float) x / frameSize.width() - 0.5f) * worldWidth,
                        (0.5f - (float) y / frameSize.height()) * worldHeight,
                        0.0f
                );
                poseStack.scale(scale * worldWidth / frameSize.width(), -scale * worldHeight / frameSize.height(), 1.0f / 16.0f);
                submitNodeCollector.submitText(poseStack, 0.0f, 0.0f, text, true, Font.DisplayMode.NORMAL, lightCoords, color, 0, 0);
                poseStack.popPose();
                poseStack.translate(0.0f, 0.0f, Z_OFFSET);
            }

            @Override
            public Font font() {
                return font;
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

    void blit(Identifier location, int x, int y, int width, int height, int color);

    void drawText(FormattedCharSequence text, int x, int y, int color, int scale);

    default void drawCenteredText(final Component text, final int x, final int y, final int color, final int scale) {
        final FormattedCharSequence charSequence = text.getVisualOrderText();
        drawText(charSequence, x - font().width(charSequence) * scale / 2, y - font().lineHeight * scale / 2, color, scale);
    }

    Font font();
}
