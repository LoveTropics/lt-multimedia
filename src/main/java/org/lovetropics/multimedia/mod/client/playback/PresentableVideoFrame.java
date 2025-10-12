package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.textures.GpuTexture;

public interface PresentableVideoFrame extends AutoCloseable {
    void copyTo(GpuTexture texture);

    FrameSize frameSize();

    @Override
    void close();
}
