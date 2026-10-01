package io.github.autyi6969.deathreplay.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.autyi6969.deathreplay.camera.CameraOverride;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a replay plays, brightness is computed for the replay's stage (its dimension, its
 * time of day), not for wherever the real player is now. A death at night replays as night.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerMixin {
	@ModifyExpressionValue(
		method = "update",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;world:Lnet/minecraft/client/world/ClientWorld;")
	)
	private ClientWorld deathreplay$lightTheStage(ClientWorld original) {
		ClientWorld stage = CameraOverride.getStage();
		return stage != null ? stage : original;
	}
}
