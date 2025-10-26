package org.lovetropics.multimedia.mod.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lovetropics.multimedia.mod.MultimediaMod;

@EventBusSubscriber(modid = MultimediaMod.ID, value = Dist.CLIENT)
public class ScreenSizeCapture {
    private static final Matrix4f CAPTURED_PROJECTION = new Matrix4f();

    @SubscribeEvent
    public static void onFrameGraphSetup(final FrameGraphSetupEvent event) {
        CAPTURED_PROJECTION.set(event.getProjectionMatrix());
    }

    @Nullable
    public static Vector2fc sizeInScreenPixels(final PoseStack.Pose pose, final Vector3fc... vertices) {
        if (vertices.length == 0) {
            return null;
        }

        final Matrix4f modelToNdc = new Matrix4f(CAPTURED_PROJECTION)
                .mul(RenderSystem.getModelViewMatrix())
                .mul(pose.pose());

        final Vector3f minNdc = new Vector3f(Float.MAX_VALUE);
        final Vector3f maxNdc = new Vector3f(-Float.MAX_VALUE);

        final Vector3f vertexNdc = new Vector3f();
        for (final Vector3fc vertex : vertices) {
            modelToNdc.transformProject(vertex, vertexNdc);
            vertexNdc.set(
                    Mth.clamp(vertexNdc.x(), -1.0f, 1.0f),
                    Mth.clamp(vertexNdc.y(), -1.0f, 1.0f),
                    vertexNdc.z()
            );
            minNdc.min(vertexNdc);
            maxNdc.max(vertexNdc);
        }

        // Fully behind the camera
        if (maxNdc.z() < 0.0f) {
            return null;
        }

        final Window window = Minecraft.getInstance().getWindow();
        return new Vector2f(
                (maxNdc.x - minNdc.x) / 2.0f * window.getWidth(),
                (maxNdc.y - minNdc.y) / 2.0f * window.getHeight()
        );
    }
}
