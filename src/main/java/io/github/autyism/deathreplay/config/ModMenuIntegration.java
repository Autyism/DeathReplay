package io.github.autyism.deathreplay.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Makes Mod Menu show a "configure" button for this mod that opens {@link SettingsScreen}.
 * Only loaded when Mod Menu is installed; the mod works without it.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
