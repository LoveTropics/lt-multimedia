package org.lovetropics.multimedia.mod.entity;

import com.lovetropics.lib.slideshow.SlideshowInstanceHandle;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2BooleanMap;
import it.unimi.dsi.fastutil.objects.Reference2BooleanMaps;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.PlaybackClock;
import org.lovetropics.multimedia.mod.client.playback.AudioWorldSource;
import org.lovetropics.multimedia.mod.network.ClientboundClearSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundSeekSlideshowPacket;
import org.lovetropics.multimedia.mod.network.ClientboundStartSlideshowPacket;
import org.lovetropics.multimedia.mod.network.SlideshowNetworkId;
import org.lovetropics.multimedia.mod.slideshow.SlideshowHolder;

import java.util.List;
import java.util.Set;

public class ScreenEntity extends Entity {
    private static final int PERMISSION_CHECK_INTERVAL = 10;

    public static final float DEFAULT_WIDTH = 4.0f;
    public static final float DEFAULT_HEIGHT = 2.25f;
    public static final float DEFAULT_AUDIO_RADIUS = 64.0f;

    private static final EntityDataAccessor<Float> DATA_WIDTH = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AUDIO_RADIUS = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);

    private static final Codec<AABB> AABB_CODEC = Codec.DOUBLE.listOf(6, 6).xmap(
            coordinates -> new AABB(coordinates.get(0), coordinates.get(1), coordinates.get(2), coordinates.get(3), coordinates.get(4), coordinates.get(5)),
            aabb -> List.of(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ)
    );

    @Nullable
    private SlideshowHolder slideshow;
    private final PlaybackClock clock = new PlaybackClock();

    private boolean requiresItemToView;
    @Nullable
    private AABB insideBoxToView;

    @Nullable
    private SlideshowHolder fallbackSlideshow;

    private final Reference2BooleanMap<ServerPlayer> trackingPlayers = new Reference2BooleanOpenHashMap<>();

    private float lastXRot;
    private float lastYRot;

    public ScreenEntity(final EntityType<? extends ScreenEntity> type, final Level level) {
        super(type, level);
    }

    private SlideshowNetworkId networkId() {
        return SlideshowNetworkId.of(this);
    }

    public boolean isPermittedToView(final ServerPlayer player) {
        if (slideshow == null) {
            return true;
        }
        return (!requiresItemToView || hasItemToView(player, slideshow))
                && (insideBoxToView == null || insideBoxToView.contains(player.position()));
    }

    private boolean hasItemToView(final ServerPlayer player, final SlideshowHolder slideshow) {
        for (final EquipmentSlot slot : EquipmentSlot.VALUES) {
            final ItemStack itemStack = player.getItemBySlot(slot);
            final List<Identifier> slideshows = itemStack.getOrDefault(MultimediaMod.SLIDESHOW_VIEWER, List.of());
            if (slideshows.contains(slideshow.id()) && player.isEquippableInSlot(itemStack, slot)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void startSeenByPlayer(final ServerPlayer player) {
        super.startSeenByPlayer(player);
        final boolean permittedToView = isPermittedToView(player);
        sendSlideshowTo(player, permittedToView, true);
        trackingPlayers.put(player, permittedToView);
    }

    private void sendSlideshowTo(final ServerPlayer player, final boolean permittedToView, final boolean initialTrack) {
        final SlideshowHolder slideshow = permittedToView ? this.slideshow : fallbackSlideshow;
        if (slideshow != null) {
            player.connection.send(new ClientboundStartSlideshowPacket(networkId(), slideshow.value(), clock.getElapsedTime(), clock.isPaused()));
        } else if (!initialTrack) {
            player.connection.send(new ClientboundClearSlideshowPacket(networkId()));
        }
    }

    @Override
    public void stopSeenByPlayer(final ServerPlayer serverPlayer) {
        super.stopSeenByPlayer(serverPlayer);
        trackingPlayers.removeBoolean(serverPlayer);
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
        for (final Reference2BooleanMap.Entry<ServerPlayer> entry : Reference2BooleanMaps.fastIterable(trackingPlayers)) {
            sendSlideshowTo(entry.getKey(), entry.getBooleanValue(), false);
        }
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
            needsSync = true;
        }

        if (!level().isClientSide() && tickCount % PERMISSION_CHECK_INTERVAL == 0) {
            checkViewerPermissions();
        }
    }

    private void checkViewerPermissions() {
        for (final Reference2BooleanMap.Entry<ServerPlayer> entry : Reference2BooleanMaps.fastIterable(trackingPlayers)) {
            final boolean wasPermittedToView = entry.getBooleanValue();
            final boolean isPermittedToView = isPermittedToView(entry.getKey());
            if (wasPermittedToView == isPermittedToView) {
                continue;
            }
            sendSlideshowTo(entry.getKey(), isPermittedToView, false);
            entry.setValue(isPermittedToView);
        }
    }

    @Override
    public void teleportSetPosition(final PositionMoveRotation positionMovementRotation, final Set<Relative> relatives) {
        super.teleportSetPosition(positionMovementRotation, relatives);
        needsSync = true;
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
        output.storeNullable("slideshow", SlideshowHolder.CODEC, slideshow);
        if (slideshow != null) {
            output.putDouble("time", clock.getElapsedTime());
            output.putBoolean("paused", clock.isPaused());
        }

        output.storeNullable("fallback_slideshow", SlideshowHolder.CODEC, fallbackSlideshow);
        output.putBoolean("requires_item_to_view", requiresItemToView);

        output.storeNullable("inside_box_to_view", AABB_CODEC, insideBoxToView);
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        entityData.set(DATA_WIDTH, input.getFloatOr("width", DEFAULT_WIDTH));
        entityData.set(DATA_HEIGHT, input.getFloatOr("height", DEFAULT_HEIGHT));
        entityData.set(DATA_AUDIO_RADIUS, input.getFloatOr("audio_radius", DEFAULT_AUDIO_RADIUS));
        loadSlideshow(
                input.read("slideshow", SlideshowHolder.CODEC).orElse(null),
                input.getDoubleOr("time", 0.0),
                input.getBooleanOr("paused", false)
        );

        fallbackSlideshow = input.read("fallback_slideshow", SlideshowHolder.CODEC).orElse(null);
        requiresItemToView = input.getBooleanOr("requires_item_to_view", false);

        insideBoxToView = input.read("inside_box_to_view", AABB_CODEC).orElse(null);
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
