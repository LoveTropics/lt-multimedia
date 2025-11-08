package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.apache.commons.io.IOUtils;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.MultimediaReader;
import org.lovetropics.multimedia.mod.MediaFile;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.playback.Playback;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.slideshow.Slide;
import org.lovetropics.multimedia.mod.slideshow.SlideContent;
import org.lovetropics.multimedia.mod.slideshow.SlideTransition;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class SlideQueue {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component ERROR_MESSAGE = Component.translatable("slideshow.slide.error").withStyle(ChatFormatting.RED);

    private final MediaFileCache mediaCache;
    private final List<SlideContent> slides;
    private final DoubleList slideStartTimes;
    private final DoubleList transitions;

    private SlideQueue(final MediaFileCache mediaCache, final List<SlideContent> slides, final DoubleList slideStartTimes, final DoubleList transitions) {
        this.mediaCache = mediaCache;
        this.slides = slides;
        this.slideStartTimes = slideStartTimes;
        this.transitions = transitions;
    }

    public static SlideQueue load(final MediaFileCache mediaCache, final Slideshow slideshow) {
        final List<SlideContent> slides = new ArrayList<>(slideshow.slides().size());
        final DoubleList slideStartTimes = new DoubleArrayList(slideshow.slides().size() + 1);
        final DoubleList transitions = new DoubleArrayList(slideshow.slides().size() + 1);

        double slideStartTime = 0.0;
        SlideTransition lastTransition = slideshow.defaultTransition();

        for (final Slide slide : slideshow.slides()) {
            final SlideTransition transitionIn = slide.transitionIn().orElse(lastTransition);

            final double duration = toSeconds(slide.content().duration());
            final double transitionInDuration = Math.min(toSeconds(transitionIn.duration()), duration);

            final double slideEndTime = slideStartTime + duration;
            slides.add(slide.content());
            slideStartTimes.add(slideStartTime);
            transitions.add(transitionInDuration);

            slideStartTime = slideEndTime;
            lastTransition = slide.transitionOut().orElse(slideshow.defaultTransition());
        }

        slideStartTimes.add(slideStartTime);
        transitions.add(toSeconds(lastTransition.duration()));

        return new SlideQueue(mediaCache, slides, slideStartTimes, transitions);
    }

    public int getSlideIndexAt(final double time) {
        for (int i = 0; i < slides.size(); i++) {
            if (time < getSlideEndTime(i)) {
                return i;
            }
        }
        return slides.size() - 1;
    }

    public double getSlideStartTime(final int index) {
        return slideStartTimes.getDouble(index);
    }

    public double getSlideEndTime(final int index) {
        return slideStartTimes.getDouble(index + 1);
    }

    public double getTransitionIn(final int index) {
        return transitions.getDouble(index);
    }

    public int size() {
        return slides.size();
    }

    private CompletableFuture<MultimediaReader> openReaderAsync(final MediaFile file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final MultimediaReader reader;
                final SeekableByteChannel channel = mediaCache.openChannel(file);
                try {
                    reader = MultimediaReader.open(channel);
                } catch (final IOException e) {
                    IOUtils.closeQuietly(channel);
                    throw e;
                }
                return reader;
            } catch (final IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    private CompletableFuture<PreparedSlide> tryPrepare(final SlideContent content, final PlaybackSyncType syncType) {
        return switch (content) {
            case SlideContent.Video(final MediaFile file, final Duration startAt, final Duration duration, final float volume) ->
                    openReaderAsync(file).thenApplyAsync(reader -> {
                        try {
                            final Playback playback = Playback.open(reader, null, syncType);
                            final double startAtTime = toSeconds(startAt);
                            playback.seekTo(startAtTime);
                            return new PreparedSlide.Video(playback, startAtTime, volume);
                        } catch (final IOException | DecoderException e) {
                            IOUtils.closeQuietly(reader);
                            throw new CompletionException(e);
                        }
                    }, Minecraft.getInstance());
            case final SlideContent.Text text -> CompletableFuture.completedFuture(new PreparedSlide.Text(text.text()));
        };
    }

    public CompletableFuture<PreparedSlide> prepareSlide(final int index, final PlaybackSyncType syncType) {
        final SlideContent content = slides.get(index);
        return tryPrepare(content, syncType).exceptionally(throwable -> {
            LOGGER.error("Failed to load slide {}", content, throwable);
            return new PreparedSlide.Text(ERROR_MESSAGE);
        });
    }

    private static double toSeconds(final Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
