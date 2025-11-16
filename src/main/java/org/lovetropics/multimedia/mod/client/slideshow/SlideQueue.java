package org.lovetropics.multimedia.mod.client.slideshow;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.slideshow.Slide;
import org.lovetropics.multimedia.mod.slideshow.SlideContent;
import org.lovetropics.multimedia.mod.slideshow.SlideDecorations;
import org.lovetropics.multimedia.mod.slideshow.SlideTransition;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SlideQueue {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component ERROR_MESSAGE = Component.translatable("slideshow.slide.error").withStyle(ChatFormatting.RED);
    private static final SlideDecorations ERROR_DECORATIONS = new SlideDecorations(
            CommonComponents.EMPTY,
            ERROR_MESSAGE
    );

    private final MediaFileCache mediaCache;
    private final List<SlideContent> slides;
    private final DoubleList slideStartTimes;
    private final DoubleList transitions;
    private final boolean looping;

    private SlideQueue(final MediaFileCache mediaCache, final List<SlideContent> slides, final DoubleList slideStartTimes, final DoubleList transitions, final boolean looping) {
        this.mediaCache = mediaCache;
        this.slides = slides;
        this.slideStartTimes = slideStartTimes;
        this.transitions = transitions;
        this.looping = looping;
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

        return new SlideQueue(mediaCache, slides, slideStartTimes, transitions, slideshow.looping());
    }

    public int getSlideIndexAt(double time) {
        final int baseIndex;
        if (looping) {
            final double totalDuration = getTotalDuration();
            final int loopCount = Mth.floor(time / totalDuration);
            baseIndex = loopCount * slides.size();
            time -= loopCount * totalDuration;
        } else {
            baseIndex = 0;
        }

        for (int i = 0; i < slides.size(); i++) {
            if (time < getSlideEndTime(i)) {
                return baseIndex + i;
            }
        }

        return baseIndex + slides.size();
    }

    public double getSlideStartTime(int index) {
        final double baseTime;
        if (looping) {
            final int loopCount = Mth.floorDiv(index, slides.size());
            baseTime = loopCount * getTotalDuration();
            index -= loopCount * slides.size();
        } else {
            baseTime = 0.0;
        }
        return slideStartTimes.getDouble(index) + baseTime;
    }

    public double getSlideEndTime(int index) {
        final double baseTime;
        if (looping) {
            final int loopCount = Mth.floorDiv(index, slides.size());
            baseTime = loopCount * getTotalDuration();
            index -= loopCount * slides.size();
        } else {
            baseTime = 0.0;
        }
        return slideStartTimes.getDouble(index + 1) + baseTime;
    }

    public double getTransitionIn(int index) {
        if (looping) {
            index = Math.floorMod(index, slides.size());
        }
        return transitions.getDouble(index);
    }

    public double getTotalDuration() {
        return slideStartTimes.getDouble(slides.size());
    }

    public int size() {
        return slides.size();
    }

    public CompletableFuture<PreparedSlide> prepareSlide(int index, final PlaybackSyncType syncType) {
        if (looping) {
            index = Math.floorMod(index, slides.size());
        }
        final SlideContent content = slides.get(index);
        return PreparedSlide.tryPrepare(content, mediaCache, syncType).exceptionally(throwable -> {
            LOGGER.error("Failed to load slide {}", content, throwable);
            return new PreparedSlide.Blank(PreparedSlideDecorations.prepare(ERROR_DECORATIONS));
        });
    }

    private static double toSeconds(final Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
