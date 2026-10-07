package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the recorder about every block that changes in the client's copy of the world
 * (server updates and the player's own predicted changes alike), with the state it had before.
 */
@Mixin(Level.class)
public abstract class WorldMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void deathreplay$recordBlockChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
		// In single player the integrated server's worlds come through here too, on another thread.
		if ((Object) this instanceof ClientLevel clientWorld && Recorder.isCapturing() && Minecraft.getInstance().isSameThread()) {
			Recorder.onBlockChange(clientWorld, pos, clientWorld.getBlockState(pos), state);
		}
	}
}
