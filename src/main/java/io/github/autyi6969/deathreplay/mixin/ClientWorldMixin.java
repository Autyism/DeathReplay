package io.github.autyi6969.deathreplay.mixin;

import io.github.autyi6969.deathreplay.replay.Replay;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a replay shows the past, block updates from the server are put in a queue instead of
 * being written into that past. They are applied, in order, the moment the replay ends.
 * This only postpones a change to the client's own picture of the world; nothing is held back
 * from, or sent to, the server.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
	@Inject(method = "handleBlockUpdate", at = @At("HEAD"), cancellable = true)
	private void deathreplay$deferDuringReplay(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		if (Replay.deferBlockUpdate((ClientWorld) (Object) this, pos, state, flags)) {
			ci.cancel();
		}
	}
}
