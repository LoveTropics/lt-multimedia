package org.lovetropics.multimedia.mod.client.config;

import com.mojang.serialization.Codec;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractOptionSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import org.lovetropics.multimedia.mod.MultimediaMod;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class MultimediaClientConfig {
    private static final MultimediaClientConfig INSTANCE = new MultimediaClientConfig();

    private static final double MAX_VOLUME = 2.0;

    public final DoubleValue audioVolume;

    private final ModConfigSpec spec;

    private MultimediaClientConfig() {
        final Builder spec = new Builder();
        audioVolume = spec.defineInRange("audioVolume", 1.0, 0.0, MAX_VOLUME);

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
                new OptionInstance.ValueSet<>() {
                    @Override
                    public Function<OptionInstance<Double>, AbstractWidget> createButton(final OptionInstance.TooltipSupplier<Double> tooltipSupplier, final Options options, final int x, final int y, final int width, final Consumer<Double> onValueChanged) {
                        return option -> new VolumeSlider(options, x, y, width, option);
                    }

                    @Override
                    public Optional<Double> validateValue(final Double value) {
                        return Optional.of(value);
                    }

                    @Override
                    public Codec<Double> codec() {
                        return Codec.DOUBLE;
                    }
                },
                audioVolume.getAsDouble(),
                audioVolume::set
        );
    }

    private static Component percentOrOff(final Component text, final double value) {
        if (value == 0.0) {
            return Options.genericValueLabel(text, CommonComponents.OPTION_OFF);
        }
        return Component.translatable("options.percent_value", text, Mth.floor(value * 100.0));
    }

    public static void register(final ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, INSTANCE.spec);
    }

    private class VolumeSlider extends AbstractOptionSliderButton {
        private final OptionInstance<Double> option;

        public VolumeSlider(final Options options, final int x, final int y, final int width, final OptionInstance<Double> option) {
            super(options, x, y, width, Button.DEFAULT_HEIGHT, option.get() / MAX_VOLUME);
            this.option = option;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(percentOrOff(option.caption, value * MAX_VOLUME));
        }

        @Override
        protected void applyValue() {
            audioVolume.set(value * MAX_VOLUME);
        }
    }
}
