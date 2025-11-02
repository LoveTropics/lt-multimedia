package org.lovetropics.multimedia.mod.client.playback;

import net.minecraft.util.Mth;
import org.lovetropics.multimedia.AudioFrame;
import org.lovetropics.multimedia.DecoderException;
import org.lwjgl.BufferUtils;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

public class AudioSampler {
    private final AudioFormat inputFormat;
    @Nullable
    private ByteBuffer inputBuffer;

    public AudioSampler(final AudioFormat inputFormat) {
        if (inputFormat.getSampleSizeInBits() != Short.SIZE) {
            throw new IllegalArgumentException("Unsupported sample size: " + inputFormat.getSampleSizeInBits());
        }
        if (inputFormat.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            throw new IllegalArgumentException("Unsupported sample encoding: " + inputFormat.getEncoding());
        }
        this.inputFormat = inputFormat;
    }

    public ByteBuffer sample(final AudioFrame inputFrame, final int outputSamples) throws DecoderException {
        if (inputBuffer == null || inputBuffer.capacity() < inputFrame.bytes()) {
            inputBuffer = BufferUtils.createByteBuffer(inputFrame.bytes());
        }
        inputBuffer.clear();
        inputFrame.unpackSamples(inputBuffer);
        inputBuffer.flip();

        final int inputSamples = inputFrame.samples();
        if (inputSamples == outputSamples) {
            return inputBuffer;
        }

        final int channels = inputFormat.getChannels();
        final ByteBuffer outputBuffer = BufferUtils.createByteBuffer(outputSamples * channels * Short.BYTES);

        for (int outputIndex = 0; outputIndex < outputSamples; outputIndex++) {
            final double scaledIndex = (double) outputIndex / outputSamples * inputSamples;
            final int inputIndex = Mth.floor(scaledIndex);
            final double alpha = scaledIndex - inputIndex;

            // Extremely naive implementation, just clamp the final sample
            final int nextInputIndex = Math.min(inputIndex + 1, inputSamples - 1);

            for (int channelIndex = 0; channelIndex < channels; channelIndex++) {
                final short sample = inputBuffer.getShort((inputIndex * channels + channelIndex) * Short.BYTES);
                final short nextSample = inputBuffer.getShort((nextInputIndex * channels + channelIndex) * Short.BYTES);
                outputBuffer.putShort((short) Math.round(Mth.lerp(alpha, sample, nextSample)));
            }
        }

        return outputBuffer.flip();
    }
}
