package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.death.DeathScreenKeeper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When a screen is closed while the player is dead, go back to the death screen that was
 * there before (with its death message) instead of a newly made, blank one.
 * (26.2+: screens are set through Gui.)
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Gui.class)
*///?} else
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen deathreplay$returnToTheSameDeathScreen(Screen screen) {
		//? if >=26.2 {
		/*return DeathScreenKeeper.replace(Minecraft.getInstance(), screen);
		*///?} else
		return DeathScreenKeeper.replace((Minecraft) (Object) this, screen);
	}
}
