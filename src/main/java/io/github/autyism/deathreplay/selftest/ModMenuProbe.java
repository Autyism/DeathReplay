package io.github.autyism.deathreplay.selftest;

import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.autyism.deathreplay.DeathReplayClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.gui.screen.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * The only self-test code that touches Mod Menu classes. Kept in its own class so that it is
 * loaded only when the self-test has confirmed that Mod Menu is installed.
 */
final class ModMenuProbe {
	private ModMenuProbe() {
	}

	/** Asks this mod's Mod Menu entry point for its settings screen, the way Mod Menu does. */
	@Nullable
	static Screen createConfigScreen(@Nullable Screen parent) {
		for (EntrypointContainer<ModMenuApi> entrypoint : FabricLoader.getInstance().getEntrypointContainers("modmenu", ModMenuApi.class)) {
			if (entrypoint.getProvider().getMetadata().getId().equals(DeathReplayClient.MOD_ID)) {
				return entrypoint.getEntrypoint().getModConfigScreenFactory().create(parent);
			}
		}

		return null;
	}
}
