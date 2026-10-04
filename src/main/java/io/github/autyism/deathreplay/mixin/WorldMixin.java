package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.record.Recorder;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the recorder about every block that changes in the client's copy of the world
 * (server updates and the player's own predicted changes alike), with the state it had before.
 */
@Mixin(World.class)
public abstract class WorldMixin {
	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"))
	private void deathreplay$recordBlockChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
		// In single player the integrated server's worlds come through here too, on another thread.
		if ((Object) this instanceof ClientWorld clientWorld && Recorder.isCapturing() && MinecraftClient.getInstance().isOnThread()) {
			Recorder.onBlockChange(clientWorld, pos, clientWorld.getBlockState(pos), state);
		}
	}
}
