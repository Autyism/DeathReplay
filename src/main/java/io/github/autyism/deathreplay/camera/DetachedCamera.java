package io.github.autyism.deathreplay.camera;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A camera pose that is not tied to an entity, plus the two ways of moving it:
 * on a leash around a target ({@link #orbit}) and free flight ({@link #fly}).
 *
 * <p>The leash is shortened by walls, like the vanilla third-person camera, so the view is
 * not buried inside a block. Free flight passes through blocks, like any free camera.
 */
public final class DetachedCamera {
	public static final float MIN_PITCH = -89.0F;
	public static final float MAX_PITCH = 89.0F;

	private Vec3 pos = Vec3.ZERO;
	private float yaw;
	private float pitch;

	public Vec3 getPos() {
		return this.pos;
	}

	public float getYaw() {
		return this.yaw;
	}

	public float getPitch() {
		return this.pitch;
	}

	public void setPos(Vec3 pos) {
		this.pos = pos;
	}

	public void setRotation(float yaw, float pitch) {
		this.yaw = yaw;
		this.pitch = Mth.clamp(pitch, MIN_PITCH, MAX_PITCH);
	}

	public void rotate(float yawDelta, float pitchDelta) {
		this.setRotation(this.yaw + yawDelta, this.pitch + pitchDelta);
	}

	/**
	 * Places the camera {@code distance} blocks away from {@code target}, looking at it along the
	 * current yaw/pitch. Walls between the target and the camera shorten the leash, like the
	 * vanilla third-person camera.
	 */
	public void orbit(Level world, Entity ignored, Vec3 target, double distance) {
		Vec3 backwards = Vec3.directionFromRotation(this.pitch, this.yaw).scale(-1.0);
		double clipped = distance;

		for (int i = 0; i < 8; i++) {
			Vec3 offset = new Vec3(((i & 1) * 2 - 1) * 0.1, ((i >> 1 & 1) * 2 - 1) * 0.1, ((i >> 2 & 1) * 2 - 1) * 0.1);
			Vec3 start = target.add(offset);
			Vec3 end = start.add(backwards.scale(distance));
			HitResult hit = world.clip(new ClipContext(start, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ignored));
			if (hit.getType() != HitResult.Type.MISS) {
				clipped = Math.min(clipped, hit.getLocation().distanceTo(target));
			}
		}

		this.pos = target.add(backwards.scale(clipped));
	}

	/**
	 * Free flight. {@code forward}/{@code strafe} are relative to the current yaw (strafe is
	 * positive to the left), {@code up} is world-vertical; each in -1..1. Nothing stops the
	 * camera: not blocks, not distance.
	 */
	public void fly(double forward, double strafe, double up, double blocks) {
		if (forward == 0.0 && strafe == 0.0 && up == 0.0) {
			return;
		}

		float yawRad = this.yaw * (float) (Math.PI / 180.0);
		double sin = Mth.sin(yawRad);
		double cos = Mth.cos(yawRad);
		// yaw 0 looks towards +Z; "left" (positive strafe) is +X at yaw 0.
		Vec3 wish = new Vec3(strafe * cos - forward * sin, up, forward * cos + strafe * sin);
		if (wish.lengthSqr() > 1.0) {
			wish = wish.normalize();
		}

		this.pos = this.pos.add(wish.scale(blocks));
	}
}
