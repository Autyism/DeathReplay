package io.github.autyi6969.deathreplay.mixin;

import io.github.autyi6969.deathreplay.camera.CameraOverride;
import io.github.autyi6969.deathreplay.camera.DetachedCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
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
	private boolean thirdPerson;

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPos(Vec3d pos);

	@Inject(method = "update", at = @At("TAIL"))
	private void deathreplay$applyOverride(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
		DetachedCamera override = CameraOverride.frame(tickProgress);
		if (override != null) {
			this.setRotation(override.getYaw(), override.getPitch());
			this.setPos(override.getPos());
			// The camera is outside the body, so the body has to be drawn.
			this.thirdPerson = true;
		}
	}
}
