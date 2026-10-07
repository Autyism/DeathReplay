package io.github.autyism.deathreplay.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.autyism.deathreplay.camera.CameraOverride;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A detached camera can be inside solid rock. Vanilla (and Sodium, which receives the same
 * flag) only looks through rock for spectators: with the camera in an opaque block they stop
 * hiding the chunk sections "behind" it. Without this, caves seen from inside the rock are
 * drawn only partly or not at all. (26.2+: the camera decides this itself.)
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.Camera.class)
*///?} else
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
	@ModifyExpressionValue(
		//? if >=26.2 {
		/*method = "extractRenderState",
		*///?} elif >=26.1 {
		/*method = "update",
		*///?} else
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z")
	)
	private boolean deathreplay$cullLikeASpectator(boolean original) {
		return original || CameraOverride.isActive();
	}
}
