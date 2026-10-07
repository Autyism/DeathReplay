package io.github.autyism.deathreplay.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.github.autyism.deathreplay.DeathReplayClient;
import io.github.autyism.deathreplay.mixin.DataTrackerAccessor;
import io.github.autyism.deathreplay.record.EntityAppearance;
import io.github.autyism.deathreplay.record.EntitySample;
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
	private final List<SynchedEntityData.DataValue<?>> initialData;
	@Nullable
	private EntityAppearance appliedAppearance;
	@Nullable
	private EntitySample lastSample;

	private Puppet(Entity entity, List<SynchedEntityData.DataValue<?>> initialData) {
		this.entity = entity;
		this.initialData = initialData;
		InterpolationHandler interpolator = entity.getInterpolation();
		this.interpolated = interpolator != null;
		if (interpolator != null) {
			// Samples arrive every tick, so there is nothing to smooth over several ticks.
			interpolator.setInterpolationLength(1);
		}
	}

	/**
	 * Builds the puppet for {@code sample} and adds it to the world, or returns {@code null}
	 * if this kind of entity cannot be rebuilt on the client.
	 */
	@Nullable
	static Puppet create(ClientLevel world, EntitySample sample, int puppetId) {
		EntityAppearance appearance = sample.appearance;
		Entity entity;
		try {
			if (appearance.type() == EntityType.PLAYER) {
				if (appearance.profile() == null) {
					return null;
				}

				ClientPacketListener handler = Minecraft.getInstance().getConnection();
				entity = new PuppetPlayerEntity(world, appearance.profile(), handler == null ? null : handler.getPlayerInfo(appearance.profile().id()));
			} else {
				entity = appearance.type().create(world, EntitySpawnReason.LOAD);
			}

			if (entity == null) {
				return null;
			}

			List<SynchedEntityData.DataValue<?>> initialData = new ArrayList<>();
			for (SynchedEntityData.DataItem<?> entry : ((DataTrackerAccessor) entity.getEntityData()).deathreplay$getEntries()) {
				initialData.add(entry.value());
			}

			if (appearance.spawnPacket() != null) {
				// Type-specific spawn data: facing of item frames, the block of a falling block...
				entity.recreateFromPacket(appearance.spawnPacket());
			}

			if (entity.isRemoved()) {
				// Some entities refuse to exist without their owner (fishing bobbers).
				return null;
			}

			entity.setId(puppetId);
			entity.setUUID(Mth.createInsecureUUID());
			entity.snapTo(sample.x, sample.y, sample.z, sample.yaw, sample.pitch);
			entity.setYHeadRot(sample.headYaw);
			entity.setYBodyRot(sample.bodyYaw);
			if (entity instanceof LivingEntity living) {
				living.yHeadRotO = sample.headYaw;
				living.yBodyRotO = sample.bodyYaw;
			}

			entity.setDeltaMovement(sample.velocity());
			entity.setOnGround(sample.onGround);

			Puppet puppet = new Puppet(entity, initialData);
			puppet.applyAppearance(appearance);
			world.addEntity(entity);
			puppet.lastSample = sample;
			return puppet;
		} catch (RuntimeException e) {
			DeathReplayClient.LOGGER.warn("Could not create a replay puppet for {}", BuiltInRegistries.ENTITY_TYPE.getKey(appearance.type()), e);
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
		if (!entity.isPassenger()) {
			if (this.interpolated) {
				entity.moveOrInterpolateTo(sample.pos(), sample.yaw, sample.pitch);
				entity.lerpHeadTo(sample.headYaw, 1);
				entity.setDeltaMovement(sample.velocity());
				entity.setOnGround(sample.onGround);
			} else {
				EntitySample shown = this.lastSample != null ? this.lastSample : sample;
				entity.setPos(shown.x, shown.y, shown.z);
				entity.setYRot(shown.yaw);
				entity.setXRot(shown.pitch);
				entity.setYHeadRot(shown.headYaw);
				entity.setDeltaMovement(shown.velocity());
				entity.setOnGround(shown.onGround);
			}
		} else if (this.interpolated) {
			// A rider is carried by its vehicle; only where it looks is its own.
			entity.moveOrInterpolateTo(sample.yaw, sample.pitch);
			entity.lerpHeadTo(sample.headYaw, 1);
		}

		// A sample taken right after an arm swing started shows the swing at tick 0 (or -1).
		if (entity instanceof LivingEntity living && sample.handSwinging && sample.handSwingTicks <= 0) {
			living.swing(sample.offHandSwing ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		}

		this.lastSample = sample;
	}

	/**
	 * Keeps the puppet where its last sample put it. Needed for entities the client moves by
	 * itself (dropped items, arrows in flight); interpolated ones stay put on their own.
	 */
	void hold() {
		EntitySample sample = this.lastSample;
		if (sample != null && !this.interpolated && !this.entity.isPassenger()) {
			this.entity.setPos(sample.x, sample.y, sample.z);
			this.entity.setDeltaMovement(Vec3.ZERO);
		}
	}

	private void applyAppearance(EntityAppearance appearance) {
		this.appliedAppearance = appearance;
		Entity entity = this.entity;

		// Wanted state = the type's initial values, overridden by what was recorded.
		List<SynchedEntityData.DataValue<?>> wanted = new ArrayList<>(this.initialData);
		for (SynchedEntityData.DataValue<?> entry : appearance.trackedData()) {
			if (entry.id() >= 0 && entry.id() < wanted.size()) {
				wanted.set(entry.id(), entry);
			}
		}

		// Only write what actually differs: writing fires the entity's change callbacks.
		SynchedEntityData.DataItem<?>[] current = ((DataTrackerAccessor) entity.getEntityData()).deathreplay$getEntries();
		List<SynchedEntityData.DataValue<?>> changes = new ArrayList<>();
		for (int i = 0; i < wanted.size() && i < current.length; i++) {
			if (!sameValue(current[i].getValue(), wanted.get(i).value())) {
				changes.add(wanted.get(i));
			}
		}

		if (!changes.isEmpty()) {
			entity.getEntityData().assignValues(changes);
		}

		if (entity instanceof LivingEntity living && appearance.equipment() != null) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = appearance.equipment()[slot.ordinal()];
				if (!ItemStack.matches(living.getItemBySlot(slot), stack)) {
					living.setItemSlot(slot, stack.copy());
				}
			}
		}
	}

	private static boolean sameValue(Object a, Object b) {
		return a instanceof ItemStack left && b instanceof ItemStack right ? ItemStack.matches(left, right) : Objects.equals(a, b);
	}
}
