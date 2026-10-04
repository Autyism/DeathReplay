package io.github.autyism.deathreplay.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.autyism.deathreplay.DeathReplayClient;
import io.github.autyism.deathreplay.mixin.DataTrackerAccessor;
import io.github.autyism.deathreplay.record.EntityAppearance;
import io.github.autyism.deathreplay.record.EntitySample;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.PositionInterpolator;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * A client-only stand-in entity that acts out one recorded entity during a replay.
 *
 * <p>The puppet lives in the client world like an entity the server would have sent, so it
 * is ticked, animated and rendered by vanilla code. It exists only on this client: the
 * server never hears of it.
 */
final class Puppet {
	final Entity entity;
	/** True if vanilla glides this entity towards positions it is told (mobs, players, boats...). */
	private final boolean interpolated;
	/** The entity's synced data as it is right after construction, indexed by data id. */
	private final List<DataTracker.SerializedEntry<?>> initialData;
	@Nullable
	private EntityAppearance appliedAppearance;
	@Nullable
	private EntitySample lastSample;

	private Puppet(Entity entity, List<DataTracker.SerializedEntry<?>> initialData) {
		this.entity = entity;
		this.initialData = initialData;
		PositionInterpolator interpolator = entity.getInterpolator();
		this.interpolated = interpolator != null;
		if (interpolator != null) {
			// Samples arrive every tick, so there is nothing to smooth over several ticks.
			interpolator.setLerpDuration(1);
		}
	}

	/**
	 * Builds the puppet for {@code sample} and adds it to the world, or returns {@code null}
	 * if this kind of entity cannot be rebuilt on the client.
	 */
	@Nullable
	static Puppet create(ClientWorld world, EntitySample sample, int puppetId) {
		EntityAppearance appearance = sample.appearance;
		Entity entity;
		try {
			if (appearance.type() == EntityType.PLAYER) {
				if (appearance.profile() == null) {
					return null;
				}

				ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
				entity = new PuppetPlayerEntity(world, appearance.profile(), handler == null ? null : handler.getPlayerListEntry(appearance.profile().id()));
			} else {
				entity = appearance.type().create(world, SpawnReason.LOAD);
			}

			if (entity == null) {
				return null;
			}

			List<DataTracker.SerializedEntry<?>> initialData = new ArrayList<>();
			for (DataTracker.Entry<?> entry : ((DataTrackerAccessor) entity.getDataTracker()).deathreplay$getEntries()) {
				initialData.add(entry.toSerialized());
			}

			if (appearance.spawnPacket() != null) {
				// Type-specific spawn data: facing of item frames, the block of a falling block...
				entity.onSpawnPacket(appearance.spawnPacket());
			}

			if (entity.isRemoved()) {
				// Some entities refuse to exist without their owner (fishing bobbers).
				return null;
			}

			entity.setId(puppetId);
			entity.setUuid(MathHelper.randomUuid());
			entity.refreshPositionAndAngles(sample.x, sample.y, sample.z, sample.yaw, sample.pitch);
			entity.setHeadYaw(sample.headYaw);
			entity.setBodyYaw(sample.bodyYaw);
			if (entity instanceof LivingEntity living) {
				living.lastHeadYaw = sample.headYaw;
				living.lastBodyYaw = sample.bodyYaw;
			}

			entity.setVelocity(sample.velocity());
			entity.setOnGround(sample.onGround);

			Puppet puppet = new Puppet(entity, initialData);
			puppet.applyAppearance(appearance);
			world.addEntity(entity);
			puppet.lastSample = sample;
			return puppet;
		} catch (RuntimeException e) {
			DeathReplayClient.LOGGER.warn("Could not create a replay puppet for {}", Registries.ENTITY_TYPE.getId(appearance.type()), e);
			return null;
		}
	}

	/**
	 * Moves the puppet one tick forward. Interpolated entities are given {@code sample} as their
	 * next target, which vanilla reaches during the following tick; everything else is placed
	 * directly at the previous sample, so both kinds show the same moment.
	 */
	void update(EntitySample sample) {
		if (sample.appearance != this.appliedAppearance) {
			this.applyAppearance(sample.appearance);
		}

		Entity entity = this.entity;
		if (!entity.hasVehicle()) {
			if (this.interpolated) {
				entity.updateTrackedPositionAndAngles(sample.pos(), sample.yaw, sample.pitch);
				entity.updateTrackedHeadRotation(sample.headYaw, 1);
				entity.setVelocity(sample.velocity());
				entity.setOnGround(sample.onGround);
			} else {
				EntitySample shown = this.lastSample != null ? this.lastSample : sample;
				entity.setPosition(shown.x, shown.y, shown.z);
				entity.setYaw(shown.yaw);
				entity.setPitch(shown.pitch);
				entity.setHeadYaw(shown.headYaw);
				entity.setVelocity(shown.velocity());
				entity.setOnGround(shown.onGround);
			}
		} else if (this.interpolated) {
			// A rider is carried by its vehicle; only where it looks is its own.
			entity.updateTrackedAngles(sample.yaw, sample.pitch);
			entity.updateTrackedHeadRotation(sample.headYaw, 1);
		}

		// A sample taken right after an arm swing started shows the swing at tick 0 (or -1).
		if (entity instanceof LivingEntity living && sample.handSwinging && sample.handSwingTicks <= 0) {
			living.swingHand(sample.offHandSwing ? Hand.OFF_HAND : Hand.MAIN_HAND);
		}

		this.lastSample = sample;
	}

	/**
	 * Keeps the puppet where its last sample put it. Needed for entities the client moves by
	 * itself (dropped items, arrows in flight); interpolated ones stay put on their own.
	 */
	void hold() {
		EntitySample sample = this.lastSample;
		if (sample != null && !this.interpolated && !this.entity.hasVehicle()) {
			this.entity.setPosition(sample.x, sample.y, sample.z);
			this.entity.setVelocity(Vec3d.ZERO);
		}
	}

	private void applyAppearance(EntityAppearance appearance) {
		this.appliedAppearance = appearance;
		Entity entity = this.entity;

		// Wanted state = the type's initial values, overridden by what was recorded.
		List<DataTracker.SerializedEntry<?>> wanted = new ArrayList<>(this.initialData);
		for (DataTracker.SerializedEntry<?> entry : appearance.trackedData()) {
			if (entry.id() >= 0 && entry.id() < wanted.size()) {
				wanted.set(entry.id(), entry);
			}
		}

		// Only write what actually differs: writing fires the entity's change callbacks.
		DataTracker.Entry<?>[] current = ((DataTrackerAccessor) entity.getDataTracker()).deathreplay$getEntries();
		List<DataTracker.SerializedEntry<?>> changes = new ArrayList<>();
		for (int i = 0; i < wanted.size() && i < current.length; i++) {
			if (!sameValue(current[i].get(), wanted.get(i).value())) {
				changes.add(wanted.get(i));
			}
		}

		if (!changes.isEmpty()) {
			entity.getDataTracker().writeUpdatedEntries(changes);
		}

		if (entity instanceof LivingEntity living && appearance.equipment() != null) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = appearance.equipment()[slot.ordinal()];
				if (!ItemStack.areEqual(living.getEquippedStack(slot), stack)) {
					living.equipStack(slot, stack.copy());
				}
			}
		}
	}

	private static boolean sameValue(Object a, Object b) {
		return a instanceof ItemStack left && b instanceof ItemStack right ? ItemStack.areEqual(left, right) : Objects.equals(a, b);
	}
}
