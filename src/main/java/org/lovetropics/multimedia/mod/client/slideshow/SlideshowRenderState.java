package org.lovetropics.multimedia.mod.client.slideshow;

import org.jetbrains.annotations.Nullable;

public record SlideshowRenderState(
        @Nullable
        PreparedSlide currentSlide,
        @Nullable
        PreparedSlide nextSlide,
        float fade
) {
    public void draw(final SlideshowGraphics graphics) {
        if (currentSlide != null && fade < 1.0f) {
            currentSlide.draw(graphics, 1.0f - fade);
        }
        if (nextSlide != null && fade > 0.0f) {
            nextSlide.draw(graphics, fade);
        }
    }
}
