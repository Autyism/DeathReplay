package io.github.autyi6969.deathreplay.mixin;

import io.github.autyi6969.deathreplay.camera.CameraOverride;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.Perspective;
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
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
	@Inject(method = "getPerspective", at = @At("HEAD"), cancellable = true)
	private void deathreplay$thirdPersonWhileDetached(CallbackInfoReturnable<Perspective> cir) {
		if (CameraOverride.isActive()) {
			cir.setReturnValue(Perspective.THIRD_PERSON_BACK);
		}
	}
}
