package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.audio.ListenerTransform;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

public interface AudioWorldSource {
    Vec3 resolveSourcePos(ListenerTransform listenerTransform);

    float attenuationDistance();

    record Point(Vec3 origin, float attenuationDistance) implements AudioWorldSource {
        @Override
        public Vec3 resolveSourcePos(final ListenerTransform listenerTransform) {
            return origin;
        }
    }

    record Rectangle(Vec3 origin, Quaternionfc rotation, float width, float height, float attenuationDistance) implements AudioWorldSource {
        @Override
        public Vec3 resolveSourcePos(final ListenerTransform listenerTransform) {
            final Vector3f relativeListenerPos = listenerTransform.position().subtract(origin).toVector3f();
            final Vector3f localListenerPos = rotation.transformInverseUnit(relativeListenerPos);

            final Vector3f localListenerDirection = rotation.transformInverseUnit(listenerTransform.forward().toVector3f());
            if (Math.abs(localListenerDirection.z()) > 0.1) {
                localListenerDirection.mul(Math.abs(localListenerPos.z()) / Math.abs(localListenerDirection.z()));
            } else {
                localListenerDirection.set(0.0f);
            }

            rotation.transformUnit(
                    Mth.clamp(localListenerPos.x + localListenerDirection.x, -width / 2.0f, width / 2.0f),
                    Mth.clamp(localListenerPos.z + localListenerDirection.y, -height / 2.0f, height / 2.0f),
                    0.0f,
                    localListenerPos
            );
            return new Vec3(localListenerPos).add(origin);
        }
    }
}
