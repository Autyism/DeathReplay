package io.github.autyism.deathreplay.mixin;

import net.minecraft.world.biome.source.BiomeAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the hashed seed the client already holds for blending biome colours, so the replay's
 * copy of the terrain gets the same grass and water tints as the original.
 */
@Mixin(BiomeAccess.class)
public interface BiomeAccessAccessor {
	@Accessor("seed")
	long deathreplay$getSeed();
}
