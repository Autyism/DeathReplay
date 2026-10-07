package io.github.autyism.deathreplay.mixin;

//? if >=26.3 {
/*import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/^*
 * 26.3+ only (added by {@link DeathReplayMixinPlugin#getMixins()}): arm swings moved into a swing
 * state object. The recorder reads how far a swing has got, as it did with the old swing time.
 ^/
@Mixin(LivingEntity.class)
public interface LivingEntitySwingAccessor {
	@Accessor("swingState")
	LivingEntity.SwingState deathreplay$getSwingState();
}
*///?}
