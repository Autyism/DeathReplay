package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.camera.CameraOverride;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the mod steers the camera, everything that asks "is this first person?" (the
 * first-person hand, the in-wall / fire / underwater overlays, the crosshair) must hear "no".
 * The stored perspective setting is never touched, so the player's own F5 choice is back
 * automatically when the override ends.
 */
@Mixin(Options.class)
public abstract class GameOptionsMixin {
	@Inject(method = "getCameraType", at = @At("HEAD"), cancellable = true)
	private void deathreplay$thirdPersonWhileDetached(CallbackInfoReturnable<CameraType> cir) {
		if (CameraOverride.isActive()) {
			cir.setReturnValue(CameraType.THIRD_PERSON_BACK);
		}
	}
}
