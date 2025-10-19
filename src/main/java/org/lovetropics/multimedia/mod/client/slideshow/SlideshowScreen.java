package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class SlideshowScreen extends Screen {
    public SlideshowScreen() {
        super(CommonComponents.EMPTY);
    }

    @Override
    public void renderBackground(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        // Pass through to in-game
    }

    private void openChatScreen(final String defaultText) {
        final Minecraft.ChatStatus chatStatus = minecraft.getChatStatus();
        if (!chatStatus.isChatAllowed(minecraft.isLocalServer())) {
            final Component component = chatStatus.getMessage();
            minecraft.gui.setOverlayMessage(component, false);
            minecraft.getNarrator().saySystemNow(component);
            minecraft.gui.setChatDisabledByPlayerShown(chatStatus == Minecraft.ChatStatus.DISABLED_BY_PROFILE);
        } else {
            minecraft.pushGuiLayer(new ChatScreen(defaultText));
        }
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            minecraft.pushGuiLayer(new PauseScreen(true));
            return true;
        } else if (minecraft.options.keyChat.matches(keyCode, scanCode)) {
            openChatScreen("");
            return true;
        } else if (minecraft.options.keyCommand.matches(keyCode, scanCode)) {
            openChatScreen("/");
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
