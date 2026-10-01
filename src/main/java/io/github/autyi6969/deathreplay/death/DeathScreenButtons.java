package io.github.autyi6969.deathreplay.death;

import io.github.autyi6969.deathreplay.config.DeathReplayConfig;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Adds the mod's buttons below the vanilla "Respawn" / "Title Screen" buttons of the death screen.
 */
public final class DeathScreenButtons {
	/** Vanilla puts its two buttons at height/4 + 72 and + 96; this is the next free row. */
	private static final int ROW_OFFSET = 120;

	private DeathScreenButtons() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register(DeathScreenButtons::afterInit);
	}

	private static void afterInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
		// The death screen is created while the death packet is handled, before DeathView has had
		// a tick to start, so decide by the setting rather than by DeathView.isActive().
		if (!(screen instanceof DeathScreen) || !DeathReplayConfig.get().deathFreeCamera) {
			return;
		}

		int y = scaledHeight / 4 + ROW_OFFSET;
		Screens.getButtons(screen).add(
			ButtonWidget.builder(Text.translatable("deathreplay.button.free_camera"), button -> DeathView.openSpectate(client))
				.dimensions(scaledWidth / 2 - 100, y, 200, 20)
				.build()
		);
	}
}
