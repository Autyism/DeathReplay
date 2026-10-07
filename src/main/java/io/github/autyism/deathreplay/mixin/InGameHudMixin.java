package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.camera.CameraOverride;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the in-game HUD (hotbar, hearts, chat) out while the replay or the look-around
 * screen is open, so nothing covers the scene.
 */
@Mixin(Gui.class)
public abstract class InGameHudMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	*///?} else
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void deathreplay$hideDuringFullView(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (CameraOverride.hidesHud()) {
			ci.cancel();
		}
	}
}
