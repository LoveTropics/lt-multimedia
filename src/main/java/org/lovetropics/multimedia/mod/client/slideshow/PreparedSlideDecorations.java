package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.CommonColors;
import net.minecraft.util.FormattedCharSequence;
import org.lovetropics.multimedia.mod.slideshow.SlideDecorations;

import java.util.List;

public record PreparedSlideDecorations(
        Component header,
        List<TextAndWidth> body,
        int bodyWidth,
        int lineHeight
) {
    private static final int MAX_BODY_WIDTH = Window.BASE_WIDTH - 60;

    private static final int BODY_BACKGROUND_PADDING = 4;
    private static final int BODY_BACKGROUND_COLOR = ARGB.color(0.8f, CommonColors.BLACK);

    public static PreparedSlideDecorations prepare(final SlideDecorations decorations) {
        final Font font = Minecraft.getInstance().font;
        final List<TextAndWidth> body = splitLines(font, decorations.body(), MAX_BODY_WIDTH);
        int bodyWidth = 0;
        for (final TextAndWidth line : body) {
            bodyWidth = Math.max(bodyWidth, line.width);
        }

        return new PreparedSlideDecorations(
                decorations.header(),
                body,
                bodyWidth,
                font.lineHeight
        );
    }

    private static List<TextAndWidth> splitLines(final Font font, final Component message, final int maxWidth) {
        return font.splitIgnoringLanguage(message, maxWidth).stream()
                .map(line -> {
                    final FormattedCharSequence charSequence = Language.getInstance().getVisualOrder(line);
                    return new TextAndWidth(charSequence, font.width(charSequence));
                })
                .toList();
    }

    public void draw(final SlideshowGraphics graphics, final boolean textBackground, final float alpha) {
        final int textColor = ARGB.white(alpha);

        final int centerX = graphics.width() / 2;

        final int bodyHeight = body.size() * lineHeight;
        final int bodyTop = (graphics.height() - bodyHeight) / 2;
        final int bodyLeft = centerX - bodyWidth / 2;

        final int headerTop = bodyTop - BODY_BACKGROUND_PADDING - lineHeight * 2;
        graphics.drawCenteredText(header, centerX, headerTop, textColor, 2);

        if (!body.isEmpty()) {
            if (textBackground) {
                graphics.fill(
                        bodyLeft - BODY_BACKGROUND_PADDING,
                        bodyTop - BODY_BACKGROUND_PADDING,
                        bodyWidth + BODY_BACKGROUND_PADDING * 2,
                        bodyHeight + BODY_BACKGROUND_PADDING * 2,
                        ARGB.multiply(BODY_BACKGROUND_COLOR, ARGB.white(alpha))
                );
            }

            int lineTop = bodyTop;
            for (final TextAndWidth line : body) {
                graphics.drawText(line.text, centerX - line.width / 2, lineTop, textColor, 1);
                lineTop += lineHeight;
            }
        }
    }

    private record TextAndWidth(FormattedCharSequence text, int width) {
    }
}
