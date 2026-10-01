package io.github.autyi6969.deathreplay.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.autyi6969.deathreplay.camera.CameraOverride;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A detached camera can be inside solid rock. Vanilla (and Sodium, which receives the same
 * flag) only looks through rock for spectators: with the camera in an opaque block they stop
 * hiding the chunk sections "behind" it. Without this, caves seen from inside the rock are
 * drawn only partly or not at all.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@ModifyExpressionValue(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isSpectator()Z")
	)
	private boolean deathreplay$cullLikeASpectator(boolean original) {
		return original || CameraOverride.isActive();
	}
}
