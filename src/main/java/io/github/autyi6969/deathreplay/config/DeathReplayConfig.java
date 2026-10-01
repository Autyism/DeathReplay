package io.github.autyi6969.deathreplay.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.autyi6969.deathreplay.DeathReplayClient;
import io.github.autyi6969.deathreplay.camera.DeathCameraMode;
import io.github.autyi6969.deathreplay.replay.ReplayView;
import net.fabricmc.loader.api.FabricLoader;

/**
 * User settings, stored as JSON in {@code config/deathreplay.json}.
 */
public final class DeathReplayConfig {
	public static final int MIN_BUFFER_SECONDS = 5;
	public static final int MAX_BUFFER_SECONDS = 120;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static DeathReplayConfig instance;

	/** How many seconds before the death are kept in memory. */
	public int bufferSeconds = 30;
	/** Write every death recording to disk automatically. */
	public boolean autoSave = false;
	/** Camera the replay starts in. */
	public ReplayView replayView = ReplayView.THIRD_PERSON;
	/** Death screen free camera on/off. */
	public boolean deathFreeCamera = true;
	/** Camera mode the death screen starts in. */
	public DeathCameraMode deathCameraMode = DeathCameraMode.THIRD_PERSON;

	public static DeathReplayConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(DeathReplayClient.MOD_ID + ".json");
	}

	private static DeathReplayConfig load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				DeathReplayConfig config = GSON.fromJson(reader, DeathReplayConfig.class);
				if (config != null) {
					config.sanitize();
					return config;
				}
			} catch (IOException | JsonParseException e) {
				DeathReplayClient.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}

		return new DeathReplayConfig();
	}

	public void save() {
		this.sanitize();
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			DeathReplayClient.LOGGER.warn("Could not write {}", path, e);
		}
	}

	/** Repairs values a hand-edited file may have broken. */
	private void sanitize() {
		this.bufferSeconds = Math.max(MIN_BUFFER_SECONDS, Math.min(MAX_BUFFER_SECONDS, this.bufferSeconds));
		if (this.replayView == null) {
			this.replayView = ReplayView.THIRD_PERSON;
		}

		if (this.deathCameraMode == null) {
			this.deathCameraMode = DeathCameraMode.THIRD_PERSON;
		}
	}
}
