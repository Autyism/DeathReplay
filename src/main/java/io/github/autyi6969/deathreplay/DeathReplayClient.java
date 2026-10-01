package io.github.autyi6969.deathreplay;

import io.github.autyi6969.deathreplay.config.DeathReplayConfig;
import io.github.autyi6969.deathreplay.death.DeathScreenButtons;
import io.github.autyi6969.deathreplay.death.DeathView;
import io.github.autyi6969.deathreplay.record.Recorder;
import io.github.autyi6969.deathreplay.selftest.SelfTest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeathReplayClient implements ClientModInitializer {
	public static final String MOD_ID = "deathreplay";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		DeathReplayConfig.get();
		ClientTickEvents.END_CLIENT_TICK.register(Recorder::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DeathView::tick);
		DeathScreenButtons.register();
		LOGGER.info("Death Replay initialized");

		if (SelfTest.ENABLED) {
			SelfTest.register();
		}
	}
}
