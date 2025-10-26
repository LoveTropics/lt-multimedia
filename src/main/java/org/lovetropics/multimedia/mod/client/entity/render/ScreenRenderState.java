package org.lovetropics.multimedia.mod.client.entity.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.lovetropics.multimedia.mod.client.slideshow.SlideshowRenderState;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;

import javax.annotation.Nullable;

public class ScreenRenderState extends EntityRenderState {
    public float yRot;
    public float xRot;
    public float width = ScreenEntity.DEFAULT_WIDTH;
    public float height = ScreenEntity.DEFAULT_HEIGHT;
    @Nullable
    public SlideshowRenderState slideshowState;
}
