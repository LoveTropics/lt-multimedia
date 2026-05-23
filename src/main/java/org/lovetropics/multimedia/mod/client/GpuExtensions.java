package org.lovetropics.multimedia.mod.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;

public class GpuExtensions {
    public static void copyBufferToTexture(final GpuBuffer src, final GpuTexture dst, final int xOffset, final int yOffset, final int width, final int height, final NativeImage.Format pixelFormat) {
        if ((src.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            throw new IllegalArgumentException("Buffer must have USAGE_COPY_SRC flag set");
        }
        if ((dst.usage() & GpuTexture.USAGE_COPY_DST) == 0) {
            throw new IllegalArgumentException("Texture must have USAGE_COPY_DST flag set");
        }
        GlStateManager._bindTexture(handle(dst));
        GlStateManager._pixelStore(GlConst.GL_UNPACK_ROW_LENGTH, width);
        GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GlConst.GL_UNPACK_ALIGNMENT, pixelFormat.components());
        GlStateManager._glBindBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER, handle(src));
        GlStateManager._texSubImage2D(GlConst.GL_TEXTURE_2D, 0, xOffset, yOffset, width, height, GlConst.toGl(pixelFormat), GlConst.GL_UNSIGNED_BYTE, 0);
        GlStateManager._glBindBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    private static int handle(final GpuTexture texture) {
        return ((GlTexture) texture).glId();
    }

    private static int handle(final GpuBuffer buffer) {
        return ((GlBuffer) buffer).handle;
    }
}
