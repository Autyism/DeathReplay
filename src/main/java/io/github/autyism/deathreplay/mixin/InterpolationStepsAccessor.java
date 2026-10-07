package io.github.autyism.deathreplay.mixin;

//? if >=26.3 {
/*import net.minecraft.world.entity.AbstractInterpolationHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/^*
 * 26.3+ only (added by {@link DeathReplayMixinPlugin#getMixins()}): living entities glide towards a
 * new position over several ticks and no longer offer a setter for it. A replay puppet is given a
 * new position every tick, so it is set to one tick, as before.
 ^/
@Mixin(AbstractInterpolationHandler.class)
public interface InterpolationStepsAccessor {
	@Accessor("interpolationSteps")
	void deathreplay$setInterpolationSteps(int steps);
}
*///?}
