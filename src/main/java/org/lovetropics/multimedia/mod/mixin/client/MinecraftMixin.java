package org.lovetropics.multimedia.mod.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import org.lovetropics.multimedia.mod.client.duck.ExtendedSoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Redirect(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;stop()V"))
    private void stopSounds(final SoundManager soundManager) {
        ((ExtendedSoundEngine) soundManager.soundEngine).multimedia$stopAllExceptPlayback();
    }
}
