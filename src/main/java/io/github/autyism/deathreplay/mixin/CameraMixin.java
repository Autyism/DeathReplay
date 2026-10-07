package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.camera.CameraOverride;
import io.github.autyism.deathreplay.camera.DetachedCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the mod place the render camera after vanilla has positioned it on the player.
 * Only the camera object is changed; the player entity itself is never moved.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Inject(method = "setup", at = @At("TAIL"))
	private void deathreplay$applyOverride(Level area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
		DetachedCamera override = CameraOverride.frame(tickProgress);
		if (override != null) {
			this.setRotation(override.getYaw(), override.getPitch());
			this.setPosition(override.getPos());
			// The camera is outside the body, so the body has to be drawn.
			this.detached = true;
		}
	}
}
