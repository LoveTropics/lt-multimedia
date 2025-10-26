package org.lovetropics.multimedia.mod.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.mod.network.MultimediaModNetwork;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;
import org.lovetropics.multimedia.mod.slideshow.SlideshowHolder;
import org.lovetropics.multimedia.mod.slideshow.Slideshows;

import java.util.Optional;

public class ScreenEntity extends Entity {
    public static final float DEFAULT_WIDTH = 4.0f;
    public static final float DEFAULT_HEIGHT = 2.25f;

    private static final EntityDataAccessor<Float> DATA_WIDTH = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Optional<Slideshow>> DATA_CLIENT_SLIDESHOW = SynchedEntityData.defineId(ScreenEntity.class, MultimediaModNetwork.SLIDESHOW_SERIALIZER.get());

    @Nullable
    private SlideshowHolder slideshow;

    private float lastXRot;
    private float lastYRot;

    public ScreenEntity(final EntityType<? extends ScreenEntity> type, final Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder builder) {
        builder.define(DATA_CLIENT_SLIDESHOW, Optional.empty());
        builder.define(DATA_WIDTH, DEFAULT_WIDTH);
        builder.define(DATA_HEIGHT, DEFAULT_HEIGHT);
    }

    private void setSlideshow(@Nullable final SlideshowHolder slideshow) {
        this.slideshow = slideshow;
        getEntityData().set(DATA_CLIENT_SLIDESHOW, Optional.ofNullable(slideshow).map(SlideshowHolder::value));
    }

    public Optional<Slideshow> getSlideshow() {
        return getEntityData().get(DATA_CLIENT_SLIDESHOW);
    }

    public float getWidth() {
        return entityData.get(DATA_WIDTH);
    }

    public float getHeight() {
        return entityData.get(DATA_HEIGHT);
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

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        output.putFloat("width", entityData.get(DATA_WIDTH));
        output.putFloat("height", entityData.get(DATA_HEIGHT));
        if (slideshow != null) {
            output.store("slideshow", ResourceLocation.CODEC, slideshow.id());
        }
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        entityData.set(DATA_WIDTH, input.getFloatOr("width", DEFAULT_WIDTH));
        entityData.set(DATA_HEIGHT, input.getFloatOr("height", DEFAULT_HEIGHT));
        setSlideshow(input.read("slideshow", ResourceLocation.CODEC)
                .map(Slideshows.REGISTRY::get)
                .orElse(null));
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
