package io.github.autyism.deathreplay.record;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * Where one entity was, and how it was posed, at the end of one client tick.
 */
public final class EntitySample {
	public static final int NO_VEHICLE = Integer.MIN_VALUE;

	public final int entityId;
	public final double x;
	public final double y;
	public final double z;
	public final float yaw;
	public final float pitch;
	public final float headYaw;
	public final float bodyYaw;
	public final float velocityX;
	public final float velocityY;
	public final float velocityZ;
	/** Entity id of the vehicle, or {@link #NO_VEHICLE}. */
	public final int vehicleId;
	public final boolean onGround;
	public final float eyeHeight;
	// Living entities only; zero / false otherwise.
	public final int hurtTime;
	public final int deathTime;
	public final boolean handSwinging;
	public final int handSwingTicks;
	public final boolean offHandSwing;
	public final EntityAppearance appearance;

	EntitySample(Entity entity, EntityAppearance appearance) {
		this.entityId = entity.getId();
		this.x = entity.getX();
		this.y = entity.getY();
		this.z = entity.getZ();
		this.yaw = entity.getYaw();
		this.pitch = entity.getPitch();
		Vec3d velocity = entity.getVelocity();
		this.velocityX = (float) velocity.x;
		this.velocityY = (float) velocity.y;
		this.velocityZ = (float) velocity.z;
		Entity vehicle = entity.getVehicle();
		this.vehicleId = vehicle == null ? NO_VEHICLE : vehicle.getId();
		this.onGround = entity.isOnGround();
		this.eyeHeight = entity.getStandingEyeHeight();
		this.appearance = appearance;

		if (entity instanceof LivingEntity living) {
			this.headYaw = living.headYaw;
			this.bodyYaw = living.bodyYaw;
			this.hurtTime = living.hurtTime;
			this.deathTime = living.deathTime;
			this.handSwinging = living.handSwinging;
			this.handSwingTicks = living.handSwingTicks;
			this.offHandSwing = living.preferredHand == Hand.OFF_HAND;
		} else {
			this.headYaw = entity.getHeadYaw();
			this.bodyYaw = entity.getBodyYaw();
			this.hurtTime = 0;
			this.deathTime = 0;
			this.handSwinging = false;
			this.handSwingTicks = 0;
			this.offHandSwing = false;
		}
	}

	/** Rebuilds a sample from stored values (a replay read from a file). */
	EntitySample(
		int entityId, double x, double y, double z, float yaw, float pitch, float headYaw, float bodyYaw,
		float velocityX, float velocityY, float velocityZ, int vehicleId, boolean onGround, float eyeHeight,
		int hurtTime, int deathTime, boolean handSwinging, int handSwingTicks, boolean offHandSwing, EntityAppearance appearance
	) {
		this.entityId = entityId;
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
		this.headYaw = headYaw;
		this.bodyYaw = bodyYaw;
		this.velocityX = velocityX;
		this.velocityY = velocityY;
		this.velocityZ = velocityZ;
		this.vehicleId = vehicleId;
		this.onGround = onGround;
		this.eyeHeight = eyeHeight;
		this.hurtTime = hurtTime;
		this.deathTime = deathTime;
		this.handSwinging = handSwinging;
		this.handSwingTicks = handSwingTicks;
		this.offHandSwing = offHandSwing;
		this.appearance = appearance;
	}

	public Vec3d pos() {
		return new Vec3d(this.x, this.y, this.z);
	}

	public Vec3d velocity() {
		return new Vec3d(this.velocityX, this.velocityY, this.velocityZ);
	}
}
