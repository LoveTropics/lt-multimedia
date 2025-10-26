package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.platform.Window;

public record FrameSize(int width, int height) {
    public FrameSize {
        width = Math.max(width, 1);
        height = Math.max(height, 1);
    }

    public static FrameSize from(final Window window) {
        return new FrameSize(window.getWidth(), window.getHeight());
    }

    public FrameSize fitInto(final FrameSize windowSize) {
        FrameSize result = this;
        if (result.width > windowSize.width) {
            result = result.resizeWidth(windowSize.width);
        }
        if (result.height > windowSize.height) {
            result = result.resizeHeight(windowSize.height);
        }
        return result;
    }

    public FrameSize resizeInto(final FrameSize windowSize) {
        final FrameSize resizedWidth = resizeWidth(windowSize.width);
        if (resizedWidth.height <= windowSize.height) {
            return resizedWidth;
        }
        return resizeHeight(windowSize.height);
    }

    public FrameSize resizeWidth(final int newWidth) {
        return new FrameSize(newWidth, height * newWidth / width);
    }

    public FrameSize resizeHeight(final int newHeight) {
        return new FrameSize(width * newHeight / height, newHeight);
    }

    public FrameSize scale(final float scale) {
        return new FrameSize(Math.round(width * scale), Math.round(height * scale));
    }
}
