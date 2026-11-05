package io.github.autyism.deathreplay.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The synced-data key of a living entity's health, to read the recorded player's health for the damage timeline. */
@Mixin(LivingEntity.class)
public interface LivingHealthAccessor {
	@Accessor("DATA_HEALTH_ID")
	static EntityDataAccessor<Float> deathreplay$healthId() {
		throw new AssertionError();
	}
}
