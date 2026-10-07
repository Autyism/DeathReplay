package io.github.autyism.deathreplay.death;

import io.github.autyism.deathreplay.config.DeathReplayConfig;
import io.github.autyism.deathreplay.record.Recorder;
import io.github.autyism.deathreplay.record.Recording;
import io.github.autyism.deathreplay.record.ReplayFileWriter;
import io.github.autyism.deathreplay.replay.Replay;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if <26.3
import org.lwjgl.glfw.GLFW;

/**
 * Adds the mod's buttons below the vanilla "Respawn" / "Title Screen" buttons of the death
 * screen: "Replay" and "Look Around" side by side, "Save Replay" underneath.
 */
public final class DeathScreenButtons {
	/** Vanilla puts its two buttons at height/4 + 72 and + 96; these are the next free rows. */
	private static final int FIRST_ROW_OFFSET = 120;
	private static final int SECOND_ROW_OFFSET = 144;
	private static final int FULL_WIDTH = 200;
	private static final int HALF_WIDTH = 98;

	private DeathScreenButtons() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register(DeathScreenButtons::afterInit);
	}

	private static void afterInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
		if (!(screen instanceof DeathScreen deathScreen)) {
			return;
		}

		DeathScreenKeeper.remember(deathScreen, client.player);
		// Vanilla ignores Esc on the death screen. Here it opens the game menu, so options, Mod Menu
		// and the like can be reached without respawning; closing the menu comes back here.
		//? if >=1.21.9 {
		ScreenKeyboardEvents.allowKeyPress(screen).register((pressedOn, input) -> {
			if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
		//?} else {
		/*ScreenKeyboardEvents.allowKeyPress(screen).register((pressedOn, key, scancode, modifiers) -> {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
		*///?}
				openGameMenu(client);
				return false;
			}

			return true;
		});
		ScreenEvents.afterRender(screen).register((rendered, context, mouseX, mouseY, tickDelta) ->
			context.drawString(Screens.getTextRenderer(rendered), Component.translatable("deathreplay.death.hint.menu"), 4, rendered.height - 12, 0xFFC0C0C0));

		// The death screen is created while the death packet is handled, before DeathView has had
		// a tick to start, so decide by the setting rather than by DeathView.isActive().
		boolean freeCamera = DeathReplayConfig.get().deathFreeCamera;
		int left = scaledWidth / 2 - FULL_WIDTH / 2;
		int firstRow = scaledHeight / 4 + FIRST_ROW_OFFSET;
		int secondRow = scaledHeight / 4 + SECOND_ROW_OFFSET;

		Button replay = Button.builder(Component.translatable("deathreplay.button.replay"), button -> Replay.open(client))
			.bounds(left, firstRow, freeCamera ? HALF_WIDTH : FULL_WIDTH, 20)
			.build();
		Button save = Button.builder(Component.translatable("deathreplay.button.save"), button -> save(client))
			.bounds(left, secondRow, FULL_WIDTH, 20)
			.build();
		Screens.getButtons(screen).add(replay);
		Screens.getButtons(screen).add(save);

		if (freeCamera) {
			Screens.getButtons(screen).add(
				Button.builder(Component.translatable("deathreplay.button.free_camera"), button -> DeathView.openSpectate(client))
					.bounds(left + FULL_WIDTH - HALF_WIDTH, firstRow, HALF_WIDTH, 20)
					.build()
			);
		}

		// The recording is frozen a moment after the death screen opens, and saving finishes on
		// another thread, so the buttons follow the state tick by tick.
		Runnable refresh = () -> {
			Recording recording = Recorder.getFrozen();
			// On the death screen "Replay" means this death, not an older one still in memory.
			replay.active = recording != null && Replay.canPlay(client);
			boolean saved = recording != null && ReplayFileWriter.isSaved(recording);
			save.active = recording != null && !saved && !ReplayFileWriter.isSaving(recording);
			save.setMessage(Component.translatable(saved ? "deathreplay.button.saved" : "deathreplay.button.save"));
		};
		refresh.run();
		ScreenEvents.afterTick(screen).register(tickedScreen -> refresh.run());
	}

	/** Opens the game menu on top of the death screen. */
	public static void openGameMenu(Minecraft client) {
		if (client.screen instanceof DeathScreen) {
			client.setScreen(new PauseScreen(true));
		}
	}

	private static void save(Minecraft client) {
		Recording recording = Recorder.getFrozen();
		if (recording != null) {
			ReplayFileWriter.saveAndAnnounce(client, recording);
		}
	}
}
