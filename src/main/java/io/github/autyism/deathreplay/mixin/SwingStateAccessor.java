package io.github.autyism.deathreplay.mixin;

//? if >=26.3 {
/*import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/^*
 * 26.3+ only (added by {@link DeathReplayMixinPlugin#getMixins()}): ticks since the current arm
 * swing started; 1 right after the first tick, like the old swing time plus one.
 ^/
@Mixin(LivingEntity.SwingState.class)
public interface SwingStateAccessor {
	@Accessor("ticks")
	int deathreplay$getTicks();
}
*///?}
