package io.github.autyism.deathreplay;

import io.github.autyism.deathreplay.config.DeathReplayConfig;
import io.github.autyism.deathreplay.config.SettingsScreen;
import io.github.autyism.deathreplay.death.DeathScreenButtons;
import io.github.autyism.deathreplay.death.DeathView;
import io.github.autyism.deathreplay.record.Recorder;
import io.github.autyism.deathreplay.replay.Replay;
import io.github.autyism.deathreplay.replay.ReplayBrowserScreen;
import io.github.autyism.deathreplay.selftest.SelfTest;
import io.github.autyism.deathreplay.waypoint.WaypointHud;
import io.github.autyism.deathreplay.waypoint.WaypointListScreen;
import io.github.autyism.deathreplay.waypoint.Waypoints;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeathReplayClient implements ClientModInitializer {
	public static final String MOD_ID = "autyism-deathreplay";
	/** Name used for files, key categories and HUD ids. Kept from before the mod id changed, so existing settings and replays still load. */
	public static final String NAMESPACE = "deathreplay";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAMESPACE);

	/** Opens the settings without Mod Menu. Unbound by default. */
	private static KeyBinding openSettingsKey;
	/** Plays the latest death replay; the way in after respawning. */
	private static KeyBinding watchReplayKey;
	/** Opens the list of markers. Unbound by default. */
	private static KeyBinding waypointListKey;

	@Override
	public void onInitializeClient() {
		DeathReplayConfig.get();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(NAMESPACE, "main"));
		openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.deathreplay.open_settings", InputUtil.UNKNOWN_KEY.getCode(), category));
		watchReplayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.deathreplay.watch_replay", GLFW.GLFW_KEY_F6, category));

		waypointListKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.deathreplay.waypoints", InputUtil.UNKNOWN_KEY.getCode(), category));
		HudElementRegistry.addLast(Identifier.of(NAMESPACE, "waypoints"), (context, tickCounter) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.world != null && !client.options.hudHidden) {
				WaypointHud.render(context, client, client.world.getRegistryKey());
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(Waypoints::tick);
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

		while (waypointListKey.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new WaypointListScreen(null));
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
