package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.lovetropics.multimedia.mod.MultimediaMod;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoFrameTexture extends AbstractTexture {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private final ResourceLocation location;
    @Nullable
    private FrameSize frameSize;

    private VideoFrameTexture(final ResourceLocation location) {
        this.location = location;
    }

    /* package-private */ static VideoFrameTexture register(final TextureManager textureManager) {
        final ResourceLocation location = MultimediaMod.location("video_frame_" + NEXT_ID.getAndIncrement());
        final VideoFrameTexture texture = new VideoFrameTexture(location);
        textureManager.register(texture.location(), texture);
        return texture;
    }

    private GpuTexture prepareTexture(final GpuDevice device, final FrameSize frameSize) {
        if (texture != null && texture.getWidth(0) == frameSize.width() && texture.getHeight(0) == frameSize.height()) {
            return texture;
        }

        if (texture != null) {
            texture.close();
        }
        if (textureView != null) {
            textureView.close();
        }
        texture = device.createTexture("Video", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, frameSize.width(), frameSize.height(), 1, 1);
        texture.setTextureFilter(FilterMode.LINEAR, false);
        textureView = device.createTextureView(texture);

        return texture;
    }

    public void copyFrom(final GpuDevice device, final PresentableVideoFrame frame) {
        try (frame) {
            frameSize = frame.frameSize();
            final GpuTexture texture = prepareTexture(device, frameSize);
            frame.copyTo(texture);
        }
    }

    public boolean hasFrame() {
        return texture != null;
    }

    public FrameSize frameSize() {
        return Objects.requireNonNull(frameSize, "No frame present");
    }

    public ResourceLocation location() {
        return location;
    }
}
