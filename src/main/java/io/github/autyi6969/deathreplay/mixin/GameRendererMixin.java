package io.github.autyi6969.deathreplay.mixin;

import io.github.autyi6969.deathreplay.camera.CameraOverride;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla rolls the view sideways while the player is dead or hurt. That belongs to the
 * player's own eyes, not to a detached camera.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
	private void deathreplay$noTiltWhileDetached(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
		if (CameraOverride.isActive()) {
			ci.cancel();
		}
	}
}
