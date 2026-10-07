package io.github.autyism.deathreplay.mixin;

import java.util.List;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import io.github.autyism.deathreplay.record.Recorder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the recorder a last look at a chunk before the client forgets it. A fast traveller
 * (elytra, teleport) leaves the start of the recording behind and its chunks are unloaded
 * before the death; the replay still needs that terrain.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkManagerMixin {
	@Shadow
	@Final
	ClientLevel level;

	/** Chunks near the recorded route, noted before the storage's centre moves. */
	@Unique
	private List<LevelChunk> deathreplay$nearRoute = List.of();

	@Inject(method = "drop", at = @At("HEAD"))
	private void deathreplay$keepForReplay(ChunkPos pos, CallbackInfo ci) {
		Recorder.onChunkUnload(this.level, pos);
	}

	@Inject(method = "updateViewCenter", at = @At("HEAD"))
	private void deathreplay$beforeCenterChange(int x, int z, CallbackInfo ci) {
		this.deathreplay$nearRoute = Recorder.beforeChunkCenterChange(this.level);
	}

	@Inject(method = "updateViewCenter", at = @At("TAIL"))
	private void deathreplay$afterCenterChange(int x, int z, CallbackInfo ci) {
		Recorder.afterChunkCenterChange(this.level, this.deathreplay$nearRoute);
		this.deathreplay$nearRoute = List.of();
	}
}
