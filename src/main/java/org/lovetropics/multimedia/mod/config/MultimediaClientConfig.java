package org.lovetropics.multimedia.mod.config;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import org.lovetropics.multimedia.mod.MultimediaMod;

public class MultimediaClientConfig {
    private static final MultimediaClientConfig INSTANCE = new MultimediaClientConfig();

    public final DoubleValue audioVolume;

    private final ModConfigSpec spec;

    private MultimediaClientConfig() {
        final Builder spec = new Builder();
        audioVolume = spec.defineInRange("audioVolume", 1.0, 0.0, 1.0);

        this.spec = spec.build();
    }

    public static MultimediaClientConfig get() {
        return INSTANCE;
    }

    public OptionInstance<Double> audioVolumeOption() {
        return new OptionInstance<>(
                "soundCategory." + MultimediaMod.ID + ".video",
                OptionInstance.noTooltip(),
                MultimediaClientConfig::percentOrOff,
                OptionInstance.UnitDouble.INSTANCE,
                audioVolume.getAsDouble(),
                audioVolume::set
        );
    }

    private static Component percentOrOff(final Component text, final Double value) {
        if (value == 0.0) {
            return Options.genericValueLabel(text, CommonComponents.OPTION_OFF);
        }
        return Component.translatable("options.percent_value", text, Mth.floor(value * 100.0));
    }

    public static void register(final ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, INSTANCE.spec);
    }
}
