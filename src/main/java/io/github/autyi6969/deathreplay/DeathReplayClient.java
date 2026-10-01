package io.github.autyi6969.deathreplay;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeathReplayClient implements ClientModInitializer {
	public static final String MOD_ID = "deathreplay";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOGGER.info("Death Replay initialized");
	}
}
