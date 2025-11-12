package org.lovetropics.multimedia.mod.client.slideshow;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lovetropics.multimedia.mod.PlaybackClock;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.client.playback.AudioWorldSource;
import org.lovetropics.multimedia.mod.client.playback.PlaybackSyncType;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class SlideshowDriver implements AutoCloseable {
    private static final double PREPARE_TIME_MARGIN = 5.0;

    private final Slideshow slideshow;
    private final PlaybackSyncType syncType;

    private final SlideQueue slideQueue;
    private final PlaybackClock clock = new PlaybackClock();

    private float audioVolume = 1.0f;
    @Nullable
    private AudioWorldSource audioSource;

    @Nullable
    private State state;

    public SlideshowDriver(final MediaFileCache mediaCache, final Slideshow slideshow, final PlaybackSyncType syncType) {
        this.slideshow = slideshow;
        slideQueue = SlideQueue.load(mediaCache, slideshow);
        this.syncType = syncType;
    }

    public void setAudioVolume(final float volume) {
        if (volume == audioVolume) {
            return;
        }
        audioVolume = volume;
        if (state != null) {
            state.updateAudio();
        }
    }

    public void setAudioSource(final AudioWorldSource source) {
        if (source.equals(audioSource)) {
            return;
        }
        audioSource = source;
        if (state != null) {
            state.updateAudio();
        }
    }

    public void seekTo(final double time, final boolean paused) {
        clock.set(time, paused);

        if (state != null && state.trySeek(clock)) {
            return;
        }

        final int index = slideQueue.getSlideIndexAt(time);
        if (state != null) {
            state.close();
        }
        final Slide currentSlide = prepareSlide(index);
        if (time < currentSlide.startTime() + currentSlide.transitionIn()) {
            state = startFading(prepareSlide(index - 1), currentSlide);
        } else {
            state = startPlaying(currentSlide);
        }
    }

    private Slide prepareSlide(final int index) {
        if (index < 0) {
            return new Slide(index, null, 0.0, 0.0, 0.0);
        } else if (index >= slideQueue.size()) {
            final double startTime = slideQueue.getSlideStartTime(index);
            final double transitionIn = slideQueue.getTransitionIn(index);
            return new Slide(index, null, startTime, startTime, transitionIn);
        }
        final Slide slide = new Slide(
                index,
                slideQueue.prepareSlide(index, syncType),
                slideQueue.getSlideStartTime(index),
                slideQueue.getSlideEndTime(index),
                slideQueue.getTransitionIn(index)
        );
        slide.setAudioVolume(audioVolume);
        if (audioSource != null) {
            slide.setAudioSource(audioSource);
        }
        return slide;
    }

    @Nullable
    private State startPlaying(final Slide slide) {
        if (slide.isEmpty()) {
            return null;
        }
        slide.setAudioVolume(audioVolume);
        slide.startOrSync(clock);
        return new Playing(slide);
    }

    @Nullable
    private State startFading(final Slide fromSlide, final Slide toSlide) {
        if (fromSlide.isEmpty() && toSlide.isEmpty()) {
            return null;
        }
        fromSlide.startOrSync(clock);
        toSlide.setAudioVolume(0.0f);
        toSlide.startOrSync(clock);
        return new Fading(fromSlide, toSlide);
    }

    public boolean tick() {
        if (state != null) {
            syncToPlayback();
            state = state.tick();
        }
        return state == null;
    }

    private void syncToPlayback() {
        if (state == null || syncType != PlaybackSyncType.PLAYBACK) {
            return;
        }
        final double playbackTime = state.getPlaybackTime();
        if (!Double.isNaN(playbackTime)) {
            clock.setElapsedTime(playbackTime);
        }
    }

    @Nullable
    public SlideshowRenderState extractRenderState(final float partialTicks) {
        if (state != null) {
            return state.extractRenderState(partialTicks);
        }
        return null;
    }

    public boolean hasFadedIn() {
        return state != null && state.hasFadedIn();
    }

    @Override
    public void close() {
        if (state != null) {
            state.close();
            state = null;
        }
    }

    public Slideshow slideshow() {
        return slideshow;
    }

    private interface State extends AutoCloseable {
        @Nullable
        State tick();

        boolean trySeek(PlaybackClock clock);

        void updateAudio();

        boolean hasFadedIn();

        double getPlaybackTime();

        @Nullable
        SlideshowRenderState extractRenderState(float partialTicks);

        @Override
        void close();
    }

    private class Playing implements State {
        private final Slide slide;
        @Nullable
        private Slide nextSlide;

        private Playing(final Slide slide) {
            this.slide = slide;
        }

        @Override
        @Nullable
        public State tick() {
            final double remainingTime = slide.endTime() - clock.getElapsedTime();
            if (nextSlide == null && remainingTime < PREPARE_TIME_MARGIN) {
                nextSlide = prepareSlide(slide.index() + 1);
            }
            if (remainingTime <= 0.0) {
                return startFading(slide, nextSlide);
            }
            return this;
        }

        @Override
        public boolean trySeek(final PlaybackClock clock) {
            final double time = clock.getElapsedTime();
            if (time >= slide.startTime() && time < slide.endTime()) {
                slide.startOrSync(clock);
                return true;
            }
            return false;
        }

        @Override
        public void updateAudio() {
            slide.setAudioVolume(audioVolume);
            if (nextSlide != null) {
                nextSlide.setAudioVolume(audioVolume);
            }
            if (audioSource != null) {
                slide.setAudioSource(audioSource);
                if (nextSlide != null) {
                    nextSlide.setAudioSource(audioSource);
                }
            }
        }

        @Override
        public boolean hasFadedIn() {
            return true;
        }

        @Override
        public double getPlaybackTime() {
            return slide.getPlaybackTime();
        }

        @Override
        @Nullable
        public SlideshowRenderState extractRenderState(final float partialTicks) {
            return new SlideshowRenderState(slide.getNow(), null, 0.0f);
        }

        @Override
        public void close() {
            slide.close();
            if (nextSlide != null) {
                nextSlide.close();
            }
        }
    }

    private class Fading implements State {
        private final Slide fromSlide;
        private final Slide toSlide;

        private float lastFade;
        private float fade;

        private Fading(final Slide fromSlide, final Slide toSlide) {
            this.fromSlide = fromSlide;
            this.toSlide = toSlide;
        }

        @Override
        @Nullable
        public State tick() {
            final double elapsedTime = clock.getElapsedTime();
            final double fromTime = toSlide.startTime();
            final double toTime = toSlide.startTime() + toSlide.transitionIn();
            if (elapsedTime >= toTime) {
                fromSlide.close();
                return startPlaying(toSlide);
            }

            lastFade = fade;
            fade = (float) Mth.inverseLerp(
                    Mth.clamp(elapsedTime, fromTime, toTime),
                    fromTime,
                    toTime
            );
            updateAudio();
            return this;
        }

        @Override
        public boolean trySeek(final PlaybackClock clock) {
            return false;
        }

        @Override
        public void updateAudio() {
            fromSlide.setAudioVolume(audioVolume * (1.0f - fade));
            toSlide.setAudioVolume(audioVolume * fade);
            if (audioSource != null) {
                fromSlide.setAudioSource(audioSource);
                toSlide.setAudioSource(audioSource);
            }
        }

        @Override
        public boolean hasFadedIn() {
            return fromSlide.getNow() != null;
        }

        @Override
        public double getPlaybackTime() {
            return fromSlide.getPlaybackTime();
        }

        @Override
        public SlideshowRenderState extractRenderState(final float partialTicks) {
            return new SlideshowRenderState(
                    fromSlide.getNow(),
                    toSlide.getNow(),
                    Mth.lerp(partialTicks, lastFade, fade)
            );
        }

        @Override
        public void close() {
            fromSlide.close();
            toSlide.close();
        }
    }

    private record Slide(
            int index,
            @Nullable
            CompletableFuture<PreparedSlide> future,
            double startTime,
            double endTime,
            double transitionIn
    ) implements AutoCloseable {
        public boolean isEmpty() {
            return future == null;
        }

        @Nullable
        public PreparedSlide getNow() {
            if (future == null) {
                return null;
            }
            return future.getNow(null);
        }

        private void execute(final Consumer<PreparedSlide> handler) {
            if (future == null) {
                return;
            }
            if (future.isDone()) {
                handler.accept(future.join());
            } else {
                future.thenAcceptAsync(handler, Minecraft.getInstance());
            }
        }

        public void startOrSync(final PlaybackClock clock) {
            execute(slide -> slide.startOrSync(
                    clock.getElapsedTime() - startTime,
                    clock.isPaused()
            ));
        }

        public void setAudioVolume(final float volume) {
            execute(slide -> slide.setAudioVolume(volume));
        }

        public void setAudioSource(final AudioWorldSource source) {
            execute(slide -> slide.setAudioSource(source));
        }

        public double getPlaybackTime() {
            final PreparedSlide slide = getNow();
            if (slide == null) {
                return Double.NaN;
            }
            return slide.getPlaybackTime() + startTime;
        }

        @Override
        public void close() {
            execute(PreparedSlide::close);
        }
    }
}
