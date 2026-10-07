package io.github.autyism.deathreplay;

import com.mojang.blaze3d.platform.InputConstants;
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
//? if >=1.21.6
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
//? if <26.3
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeathReplayClient implements ClientModInitializer {
	public static final String MOD_ID = "autyism-deathreplay";
	/** Name used for files, key categories and HUD ids. Kept from before the mod id changed, so existing settings and replays still load. */
	public static final String NAMESPACE = "deathreplay";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAMESPACE);

	/** Opens the settings without Mod Menu. Unbound by default. */
	private static KeyMapping openSettingsKey;
	/** Plays the latest death replay; the way in after respawning. */
	private static KeyMapping watchReplayKey;
	/** Opens the list of markers. Unbound by default. */
	private static KeyMapping waypointListKey;

	@Override
	public void onInitializeClient() {
		DeathReplayConfig.get();
		//? if >=1.21.9 {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(NAMESPACE, "main"));
		//?} else
		/*String category = "key.category." + NAMESPACE + ".main";*/
		openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.deathreplay.open_settings", InputConstants.UNKNOWN.getValue(), category));
		watchReplayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.deathreplay.watch_replay", GLFW.GLFW_KEY_F6, category));

		waypointListKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.deathreplay.waypoints", InputConstants.UNKNOWN.getValue(), category));
		//? if >=1.21.6 {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(NAMESPACE, "waypoints"), (context, tickCounter) -> {
		//?} else {
		/*net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback.EVENT.register(layers -> layers.addLayer(
			net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer.of(Identifier.fromNamespaceAndPath(NAMESPACE, "waypoints"), (context, tickCounter) -> {
		*///?}
			Minecraft client = Minecraft.getInstance();
			if (client.level != null && !client.options.hideGui) {
				WaypointHud.render(context, client, client.level.dimension());
			}
		//? if >=1.21.6 {
		});
		//?} else
		/*})));*/

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

	private static void handleKeys(Minecraft client) {
		while (openSettingsKey.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new SettingsScreen(null));
			}
		}

		while (waypointListKey.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new WaypointListScreen(null));
			}
		}

		while (watchReplayKey.consumeClick()) {
			// No death in memory (for example right after starting the game): offer the saved ones.
			if (client.screen == null && client.player != null && !Replay.open(client)) {
				client.setScreen(new ReplayBrowserScreen(null));
			}
		}
	}

	/** Tells the freshly respawned player how to watch the replay of the death just now. */
	public static void showRespawnHint(Minecraft client) {
		if (client.player != null && DeathReplayConfig.get().respawnHint && watchReplayKey != null && !watchReplayKey.isUnbound()) {
			client.player.displayClientMessage(Component.translatable("deathreplay.message.respawn_hint", watchReplayKey.getTranslatedKeyMessage()), false);
		}
	}
}
