package org.lovetropics.multimedia.mod.client.entity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.CommonColors;
import org.lovetropics.multimedia.mod.client.MultimediaClientMod;
import org.lovetropics.multimedia.mod.client.entity.ScreenClientState;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowDriver;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowGraphics;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowManager;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;

public class ScreenRenderer extends EntityRenderer<ScreenEntity, ScreenRenderState> {
    private final SlideshowManager slideshowManager;
    private final Font font;

    public ScreenRenderer(final EntityRendererProvider.Context context) {
        super(context);
        slideshowManager = MultimediaClientMod.slideshowManager();
        font = context.getFont();
    }

    @Override
    public void render(final ScreenRenderState state, final PoseStack poseStack, final MultiBufferSource bufferSource, final int packedLight) {
        super.render(state, poseStack, bufferSource, packedLight);

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.xRot));

        final SlideshowGraphics graphics = SlideshowGraphics.forWorld(poseStack.last(), state.width, state.height, LightTexture.FULL_BRIGHT, bufferSource, font);
        if (graphics != null) {
            graphics.fill(0, 0, graphics.width(), graphics.height(), CommonColors.BLACK);
            if (state.slideshowState != null) {
                state.slideshowState.draw(graphics);
            }
        }

        poseStack.popPose();
    }

    @Override
    public ScreenRenderState createRenderState() {
        return new ScreenRenderState();
    }

    @Override
    public void extractRenderState(final ScreenEntity entity, final ScreenRenderState state, final float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
        state.width = entity.getWidth();
        state.height = entity.getHeight();

        final ScreenClientState screenState = slideshowManager.getScreenState(entity);
        state.slideshowState = screenState != null ? screenState.extractRenderState(partialTick) : null;
    }
}
