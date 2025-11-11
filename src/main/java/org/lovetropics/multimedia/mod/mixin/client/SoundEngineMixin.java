package org.lovetropics.multimedia.mod.mixin.client;

import com.google.common.collect.Multimap;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.lovetropics.multimedia.mod.client.duck.ExtendedSoundEngine;
import org.lovetropics.multimedia.mod.client.playback.AudioPlaybackChannel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;

@Mixin(SoundEngine.class)
public class SoundEngineMixin implements ExtendedSoundEngine {
    @Shadow
    private boolean loaded;

    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;
    @Shadow
    @Final
    public ChannelAccess channelAccess;
    @Shadow
    @Final
    private Map<SoundInstance, Integer> queuedSounds;
    @Shadow
    @Final
    private List<TickableSoundInstance> tickingSounds;
    @Shadow
    @Final
    private Multimap<SoundSource, SoundInstance> instanceBySource;
    @Shadow
    @Final
    private Map<SoundInstance, Integer> soundDeleteTime;
    @Shadow
    @Final
    private List<TickableSoundInstance> queuedTickableSounds;

    @Override
    public void multimedia$stopAllExceptPlayback() {
        if (!loaded) {
            return;
        }
        channelAccess.executeOnChannels(channels -> channels
                .filter(channel -> !(channel instanceof AudioPlaybackChannel))
                .forEach(Channel::stop)
        );
        channelAccess.scheduleTick();
        instanceToChannel.clear();
        queuedSounds.clear();
        tickingSounds.clear();
        instanceBySource.clear();
        soundDeleteTime.clear();
        queuedTickableSounds.clear();
    }
}
