package io.github.autyism.deathreplay.camera;

import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

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

	private Vec3d pos = Vec3d.ZERO;
	private float yaw;
	private float pitch;

	public Vec3d getPos() {
		return this.pos;
	}

	public float getYaw() {
		return this.yaw;
	}

	public float getPitch() {
		return this.pitch;
	}

	public void setPos(Vec3d pos) {
		this.pos = pos;
	}

	public void setRotation(float yaw, float pitch) {
		this.yaw = yaw;
		this.pitch = MathHelper.clamp(pitch, MIN_PITCH, MAX_PITCH);
	}

	public void rotate(float yawDelta, float pitchDelta) {
		this.setRotation(this.yaw + yawDelta, this.pitch + pitchDelta);
	}

	/**
	 * Places the camera {@code distance} blocks away from {@code target}, looking at it along the
	 * current yaw/pitch. Walls between the target and the camera shorten the leash, like the
	 * vanilla third-person camera.
	 */
	public void orbit(World world, Entity ignored, Vec3d target, double distance) {
		Vec3d backwards = Vec3d.fromPolar(this.pitch, this.yaw).multiply(-1.0);
		double clipped = distance;

		for (int i = 0; i < 8; i++) {
			Vec3d offset = new Vec3d(((i & 1) * 2 - 1) * 0.1, ((i >> 1 & 1) * 2 - 1) * 0.1, ((i >> 2 & 1) * 2 - 1) * 0.1);
			Vec3d start = target.add(offset);
			Vec3d end = start.add(backwards.multiply(distance));
			HitResult hit = world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, ignored));
			if (hit.getType() != HitResult.Type.MISS) {
				clipped = Math.min(clipped, hit.getPos().distanceTo(target));
			}
		}

		this.pos = target.add(backwards.multiply(clipped));
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
		double sin = MathHelper.sin(yawRad);
		double cos = MathHelper.cos(yawRad);
		// yaw 0 looks towards +Z; "left" (positive strafe) is +X at yaw 0.
		Vec3d wish = new Vec3d(strafe * cos - forward * sin, up, forward * cos + strafe * sin);
		if (wish.lengthSquared() > 1.0) {
			wish = wish.normalize();
		}

		this.pos = this.pos.add(wish.multiply(blocks));
	}
}
