package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.ARGB;
import net.minecraft.util.CommonColors;
import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.mod.client.config.MultimediaClientConfig;

public class FullScreenSlideshow implements AutoCloseable {
    private final SlideshowDriver slideshow;
    private boolean closed;

    public FullScreenSlideshow(final SlideshowDriver slideshow) {
        this.slideshow = slideshow;
    }

    public boolean tick() {
        if (closed) {
            return true;
        }

        slideshow.setAudioVolume((float) MultimediaClientConfig.get().audioVolume.getAsDouble());
        if (slideshow.tick()) {
            closed = true;
            return true;
        }

        if (slideshow.hasFadedIn()) {
            openScreen();
        }
        return false;
    }

    public void draw(final SlideshowGraphics graphics, final float partialTicks) {
        final SlideshowRenderState state = slideshow.extractRenderState(partialTicks);
        if (state == null) {
            return;
        }

        final float fade = state.fade();
        final float backgroundAlpha;
        if (state.currentSlide() != null && state.nextSlide() != null) {
            backgroundAlpha = 1.0f;
        } else if (state.currentSlide() != null) {
            backgroundAlpha = 1.0f - fade;
        } else {
            backgroundAlpha = fade;
        }

        // Could definitely be more efficient than just filling the entire screen... :)
        graphics.fill(0, 0, graphics.width(), graphics.height(), ARGB.color(backgroundAlpha, CommonColors.BLACK));

        state.draw(graphics);
    }

    private void openScreen() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() == null) {
            minecraft.gui.setScreen(new CaptureScreen());
        }
    }

    public void seekTo(final double time, final boolean paused) {
        slideshow.seekTo(time, paused);
    }

    public boolean shouldRenderOver(final @Nullable Screen screen) {
        return screen instanceof ProgressScreen || screen instanceof LevelLoadingScreen;
    }

    @Override
    public void close() {
        slideshow.close();
    }

    private class CaptureScreen extends Screen {
        public CaptureScreen() {
            super(CommonComponents.EMPTY);
        }

        @Override
        public void tick() {
            super.tick();
            if (closed) {
                onClose();
            }
        }

        @Override
        public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
            // Pass through to in-game
        }

        @Override
        public boolean keyPressed(final KeyEvent event) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                minecraft.gui.pushScreenLayer(new PauseScreen(true));
                return true;
            } else if (minecraft.options.keyChat.matches(event)) {
                minecraft.gui.hud.getChat().openScreen(ChatComponent.ChatMethod.MESSAGE, ChatScreen::new);
                return true;
            } else if (minecraft.options.keyCommand.matches(event)) {
                minecraft.gui.hud.getChat().openScreen(ChatComponent.ChatMethod.COMMAND, ChatScreen::new);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
