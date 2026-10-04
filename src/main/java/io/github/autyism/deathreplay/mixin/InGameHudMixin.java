package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.camera.CameraOverride;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the in-game HUD (hotbar, hearts, chat) out while the replay or the look-around
 * screen is open, so nothing covers the scene.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void deathreplay$hideDuringFullView(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		if (CameraOverride.hidesHud()) {
			ci.cancel();
		}
	}
}
