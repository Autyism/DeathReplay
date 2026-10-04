package io.github.autyism.deathreplay.death;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Remembers the death screen of the current death, so that it can be left for another screen
 * (the game menu, and from there the options or Mod Menu) and come back as it was.
 *
 * <p>Vanilla makes a fresh death screen whenever a screen is closed while the player is dead,
 * but the fresh one has lost the death message. With this, "close" leads back to the original.
 */
public final class DeathScreenKeeper {
	@Nullable
	private static DeathScreen screen;
	@Nullable
	private static ClientPlayerEntity player;

	private DeathScreenKeeper() {
	}

	/** Called when a death screen is shown. The first one of a death is the one with the message. */
	static void remember(DeathScreen deathScreen, @Nullable ClientPlayerEntity deadPlayer) {
		if (deadPlayer != null && deadPlayer != player) {
			screen = deathScreen;
			player = deadPlayer;
		}
	}

	/**
	 * Called for every screen change. When vanilla is about to fall back to a fresh death screen
	 * ({@code next == null} while dead), returns the remembered one instead.
	 */
	@Nullable
	public static Screen replace(MinecraftClient client, @Nullable Screen next) {
		if (screen == null) {
			return next;
		}

		if (client.player != player || client.world == null) {
			// Respawned or left: that death is over.
			screen = null;
			player = null;
			return next;
		}

		return next == null && player.isDead() ? screen : next;
	}
}
