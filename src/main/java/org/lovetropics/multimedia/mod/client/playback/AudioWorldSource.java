package org.lovetropics.multimedia.mod.client.playback;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

public interface AudioWorldSource {
    Vec3 resolveSourcePos(Vec3 listenerPos);

    float attenuationDistance();

    record Point(Vec3 origin, float attenuationDistance) implements AudioWorldSource {
        @Override
        public Vec3 resolveSourcePos(final Vec3 listenerPos) {
            return origin;
        }
    }

    record Rectangle(Vec3 origin, Quaternionfc rotation, float width, float height, float attenuationDistance) implements AudioWorldSource {
        @Override
        public Vec3 resolveSourcePos(final Vec3 listenerPos) {
            final Vector3f relativeListenerPos = listenerPos.subtract(origin).toVector3f();
            final Vector3f localListenerPos = rotation.transformInverseUnit(relativeListenerPos);
            localListenerPos.set(
                    Mth.clamp(relativeListenerPos.x, -width / 2.0f, width / 2.0f),
                    Mth.clamp(relativeListenerPos.y, -height / 2.0f, height / 2.0f),
                    0.0f
            );
            rotation.transformUnit(localListenerPos);
            return new Vec3(localListenerPos).add(origin);
        }
    }
}
