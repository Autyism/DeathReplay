package io.github.autyi6969.deathreplay;

import io.github.autyi6969.deathreplay.config.DeathReplayConfig;
import io.github.autyi6969.deathreplay.config.SettingsScreen;
import io.github.autyi6969.deathreplay.death.DeathScreenButtons;
import io.github.autyi6969.deathreplay.death.DeathView;
import io.github.autyi6969.deathreplay.record.Recorder;
import io.github.autyi6969.deathreplay.replay.Replay;
import io.github.autyi6969.deathreplay.replay.ReplayBrowserScreen;
import io.github.autyi6969.deathreplay.selftest.SelfTest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeathReplayClient implements ClientModInitializer {
	public static final String MOD_ID = "deathreplay";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Opens the settings without Mod Menu. Unbound by default. */
	private static KeyBinding openSettingsKey;
	/** Plays the latest death replay; the way in after respawning. */
	private static KeyBinding watchReplayKey;

	@Override
	public void onInitializeClient() {
		DeathReplayConfig.get();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.deathreplay.open_settings", InputUtil.UNKNOWN_KEY.getCode(), category));
		watchReplayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.deathreplay.watch_replay", GLFW.GLFW_KEY_F6, category));

		ClientTickEvents.END_CLIENT_TICK.register(Recorder::tick);
		ClientTickEvents.END_CLIENT_TICK.register(Replay::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DeathView::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DeathReplayClient::handleKeys);
		DeathScreenButtons.register();
		LOGGER.info("Death Replay initialized");

		if (SelfTest.ENABLED) {
			SelfTest.register();
		}
	}

	private static void handleKeys(MinecraftClient client) {
		while (openSettingsKey.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new SettingsScreen(null));
			}
		}

		while (watchReplayKey.wasPressed()) {
			// No death in memory (for example right after starting the game): offer the saved ones.
			if (client.currentScreen == null && client.player != null && !Replay.open(client)) {
				client.setScreen(new ReplayBrowserScreen(null));
			}
		}
	}

	/** Tells the freshly respawned player how to watch the replay of the death just now. */
	public static void showRespawnHint(MinecraftClient client) {
		if (client.player != null && DeathReplayConfig.get().respawnHint && watchReplayKey != null && !watchReplayKey.isUnbound()) {
			client.player.sendMessage(Text.translatable("deathreplay.message.respawn_hint", watchReplayKey.getBoundKeyLocalizedText()), false);
		}
	}
}
