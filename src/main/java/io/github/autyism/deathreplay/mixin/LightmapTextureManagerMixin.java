package io.github.autyism.deathreplay.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.autyism.deathreplay.camera.CameraOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a replay plays, brightness is computed for the replay's stage (its dimension, its
 * time of day), not for wherever the real player is now. A death at night replays as night.
 */
@Mixin(LightTexture.class)
public abstract class LightmapTextureManagerMixin {
	@ModifyExpressionValue(
		method = "updateLightTexture",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;")
	)
	private ClientLevel deathreplay$lightTheStage(ClientLevel original) {
		ClientLevel stage = CameraOverride.getStage();
		return stage != null ? stage : original;
	}
}
