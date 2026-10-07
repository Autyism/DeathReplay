package io.github.autyism.deathreplay.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.autyism.deathreplay.camera.CameraOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
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
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	//? if >=26.1 {
	/*private void deathreplay$noTiltWhileDetached(net.minecraft.client.renderer.state.level.CameraRenderState cameraState, PoseStack matrices, CallbackInfo ci) {
	*///?} else
	private void deathreplay$noTiltWhileDetached(PoseStack matrices, float tickProgress, CallbackInfo ci) {
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
	 * (26.1+: the camera takes its level from GameRenderer.setLevel; the fog is set up in extractCamera.)
	 */
	@ModifyExpressionValue(
		//? if >=26.1 {
		/*method = "extractCamera",
		*///?} elif >=1.21.11 {
		method = {"updateCamera", "renderLevel"},
		//?} else {
		/*method = "renderLevel",
		*///?}
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;")
	)
	private ClientLevel deathreplay$renderTheStage(ClientLevel original) {
		ClientLevel stage = CameraOverride.getStage();
		return stage != null ? stage : original;
	}
}
