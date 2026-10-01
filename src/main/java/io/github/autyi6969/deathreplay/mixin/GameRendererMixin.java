package io.github.autyi6969.deathreplay.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.autyi6969.deathreplay.camera.CameraOverride;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rendering adjustments while the mod steers the camera.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * Vanilla rolls the view sideways while the player is dead or hurt. That belongs to the
	 * player's own eyes, not to a detached camera.
	 */
	@Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
	private void deathreplay$noTiltWhileDetached(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
		if (CameraOverride.isActive()) {
			ci.cancel();
		}
	}

	/** The block the real player happens to look at must not be outlined in a detached view. */
	@Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
	private void deathreplay$noBlockOutlineWhileDetached(CallbackInfoReturnable<Boolean> cir) {
		if (CameraOverride.isActive()) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * While a replay plays, the camera (water / lava fog, sky light colour) and the fog must be
	 * computed from the replay's stage, not from the place the real player is standing in.
	 * Only what this renderer reads is swapped; the game's world field stays as it is.
	 */
	@ModifyExpressionValue(
		method = {"updateCamera", "renderWorld"},
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;world:Lnet/minecraft/client/world/ClientWorld;")
	)
	private ClientWorld deathreplay$renderTheStage(ClientWorld original) {
		ClientWorld stage = CameraOverride.getStage();
		return stage != null ? stage : original;
	}
}
