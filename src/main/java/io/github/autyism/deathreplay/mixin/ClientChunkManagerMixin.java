package io.github.autyism.deathreplay.mixin;

import java.util.List;

import io.github.autyism.deathreplay.record.Recorder;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
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
@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin {
	@Shadow
	@Final
	ClientWorld world;

	/** Chunks near the recorded route, noted before the storage's centre moves. */
	@Unique
	private List<WorldChunk> deathreplay$nearRoute = List.of();

	@Inject(method = "unload", at = @At("HEAD"))
	private void deathreplay$keepForReplay(ChunkPos pos, CallbackInfo ci) {
		Recorder.onChunkUnload(this.world, pos);
	}

	@Inject(method = "setChunkMapCenter", at = @At("HEAD"))
	private void deathreplay$beforeCenterChange(int x, int z, CallbackInfo ci) {
		this.deathreplay$nearRoute = Recorder.beforeChunkCenterChange(this.world);
	}

	@Inject(method = "setChunkMapCenter", at = @At("TAIL"))
	private void deathreplay$afterCenterChange(int x, int z, CallbackInfo ci) {
		Recorder.afterChunkCenterChange(this.world, this.deathreplay$nearRoute);
		this.deathreplay$nearRoute = List.of();
	}
}
