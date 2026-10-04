package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.death.DeathScreenKeeper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When a screen is closed while the player is dead, go back to the death screen that was
 * there before (with its death message) instead of a newly made, blank one.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen deathreplay$returnToTheSameDeathScreen(Screen screen) {
		return DeathScreenKeeper.replace((MinecraftClient) (Object) this, screen);
	}
}
