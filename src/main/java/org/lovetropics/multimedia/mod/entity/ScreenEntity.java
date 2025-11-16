package org.lovetropics.multimedia.mod.entity;

import com.lovetropics.lib.slideshow.SlideshowInstanceHandle;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.lovetropics.multimedia.mod.PlaybackClock;
import org.lovetropics.multimedia.mod.client.playback.AudioWorldSource;
import org.lovetropics.multimedia.mod.network.ClientboundClearSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundSeekSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundStartSlideshowPacket;
import org.lovetropics.multimedia.mod.network.SlideshowNetworkId;
import org.lovetropics.multimedia.mod.slideshow.SlideshowHolder;
import org.lovetropics.multimedia.mod.slideshow.SlideshowRegistry;

import java.util.Set;

public class ScreenEntity extends Entity {
    public static final float DEFAULT_WIDTH = 4.0f;
    public static final float DEFAULT_HEIGHT = 2.25f;
    public static final float DEFAULT_AUDIO_RADIUS = 64.0f;

    private static final EntityDataAccessor<Float> DATA_WIDTH = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AUDIO_RADIUS = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);

    @Nullable
    private SlideshowHolder slideshow;
    private final PlaybackClock clock = new PlaybackClock();

    private float lastXRot;
    private float lastYRot;

    public ScreenEntity(final EntityType<? extends ScreenEntity> type, final Level level) {
        super(type, level);
    }

    private SlideshowNetworkId networkId() {
        return SlideshowNetworkId.of(this);
    }

    @Override
    public void startSeenByPlayer(final ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (slideshow != null) {
            player.connection.send(new ClientboundStartSlideshowPacket(networkId(), slideshow.value(), clock.getElapsedTime(), clock.isPaused()));
        }
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder builder) {
        builder.define(DATA_WIDTH, DEFAULT_WIDTH);
        builder.define(DATA_HEIGHT, DEFAULT_HEIGHT);
        builder.define(DATA_AUDIO_RADIUS, DEFAULT_AUDIO_RADIUS);
    }

    public @Nullable SlideshowInstanceHandle asHandle() {
        if (slideshow == null) {
            return null;
        }
        final double totalTime = slideshow.value().duration().toMillis() / 1000.0;
        return new SlideshowInstanceHandle() {
            @Override
            public void addPlayer(final ServerPlayer player) {
            }

            @Override
            public void removePlayer(final ServerPlayer player) {
            }

            @Override
            public void seekTo(final double time, final boolean paused) {
                ScreenEntity.this.seekTo(time, paused);
            }

            @Override
            public double currentTime() {
                return clock.getElapsedTime();
            }

            @Override
            public double totalTime() {
                return totalTime;
            }

            @Override
            public boolean isPaused() {
                return clock.isPaused();
            }

            @Override
            public void close() {
                setSlideshow(null);
            }
        };
    }

    public void setSlideshow(@Nullable final SlideshowHolder slideshow) {
        clock.set(0.0, false);
        this.slideshow = slideshow;
        final CustomPacketPayload packet;
        if (slideshow != null) {
            packet = new ClientboundStartSlideshowPacket(networkId(), slideshow.value(), 0.0, clock.isPaused());
        } else {
            packet = new ClientboundClearSlideshowPacket(networkId());
        }
        PacketDistributor.sendToPlayersTrackingEntity(this, packet);
    }

    private void loadSlideshow(@Nullable final SlideshowHolder slideshow, final double time, final boolean paused) {
        this.slideshow = slideshow;
        if (slideshow != null) {
            clock.set(time, paused);
        }
    }

    private void seekTo(final double time, final boolean paused) {
        if (slideshow != null) {
            clock.set(time, paused);
            PacketDistributor.sendToPlayersTrackingEntity(this, new ClientboundSeekSlideshowPacket(networkId(), time, paused));
        }
    }

    public float getWidth() {
        return entityData.get(DATA_WIDTH);
    }

    public float getHeight() {
        return entityData.get(DATA_HEIGHT);
    }

    private float getAudioRadius() {
        return entityData.get(DATA_AUDIO_RADIUS);
    }

    @Override
    public void onSyncedDataUpdated(final EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key.equals(DATA_WIDTH) || key.equals(DATA_HEIGHT)) {
            refreshDimensions();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (getXRot() != lastXRot || getYRot() != lastYRot) {
            refreshDimensions();
            lastXRot = getXRot();
            lastYRot = getYRot();
            hasImpulse = true;
        }
    }

    @Override
    public void teleportSetPosition(final PositionMoveRotation positionMovementRotation, final Set<Relative> relatives) {
        super.teleportSetPosition(positionMovementRotation, relatives);
        hasImpulse = true;
    }

    @Override
    protected AABB makeBoundingBox(final Vec3 center) {
        final Vec3[] corners = new Vec3[]{
                new Vec3(-getWidth() / 2.0, -getHeight() / 2.0, 0.0),
                new Vec3(getWidth() / 2.0, -getHeight() / 2.0, 0.0),
                new Vec3(getWidth() / 2.0, getHeight() / 2.0, 0.0),
                new Vec3(-getWidth() / 2.0, getHeight() / 2.0, 0.0)
        };
        final AABB.Builder box = new AABB.Builder();
        for (final Vec3 corner : corners) {
            box.include(corner
                    .xRot(getXRot() * Mth.DEG_TO_RAD)
                    .yRot(-getYRot() * Mth.DEG_TO_RAD)
                    .toVector3f()
            );
        }
        return box.build().move(center);
    }

    public AudioWorldSource asAudioSource() {
        return new AudioWorldSource.Rectangle(
                position(),
                new Quaternionf().rotationXYZ(
                        getXRot() * Mth.DEG_TO_RAD,
                        -getYRot() * Mth.DEG_TO_RAD,
                        0.0f
                ),
                getWidth(),
                getHeight(),
                getAudioRadius()
        );
    }

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        output.putFloat("width", getWidth());
        output.putFloat("height", getHeight());
        output.putFloat("audio_radius", getAudioRadius());
        if (slideshow != null) {
            output.store("slideshow", ResourceLocation.CODEC, slideshow.id());
            output.putDouble("time", clock.getElapsedTime());
            output.putBoolean("paused", clock.isPaused());
        }
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        entityData.set(DATA_WIDTH, input.getFloatOr("width", DEFAULT_WIDTH));
        entityData.set(DATA_HEIGHT, input.getFloatOr("height", DEFAULT_HEIGHT));
        entityData.set(DATA_AUDIO_RADIUS, input.getFloatOr("audio_radius", DEFAULT_AUDIO_RADIUS));
        loadSlideshow(
                input.read("slideshow", ResourceLocation.CODEC)
                        .map(SlideshowRegistry.REGISTRY::get)
                        .orElse(null),
                input.getDoubleOr("time", 0.0),
                input.getBooleanOr("paused", false)
        );
    }

    @Override
    public boolean hurtServer(final ServerLevel level, final DamageSource damageSource, final float amount) {
        return false;
    }

    @Override
    public void move(final MoverType type, final Vec3 movement) {
    }

    @Override
    public void push(final double x, final double y, final double z) {
    }
}
