package io.github.autyism.deathreplay.mixin;

import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to all synced-data entries of an entity, so a replay puppet can be reset to a
 * recorded look, including values that went back to their defaults.
 */
@Mixin(DataTracker.class)
public interface DataTrackerAccessor {
	@Accessor("entries")
	DataTracker.Entry<?>[] deathreplay$getEntries();
}
