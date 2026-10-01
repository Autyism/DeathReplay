package io.github.autyi6969.deathreplay.selftest;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import io.github.autyi6969.deathreplay.camera.DeathCameraMode;
import io.github.autyi6969.deathreplay.camera.DetachedCamera;
import io.github.autyi6969.deathreplay.death.DeathSpectateScreen;
import io.github.autyi6969.deathreplay.death.DeathView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.tutorial.TutorialStep;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automated in-game smoke test, enabled with {@code -Ddr.selftest=true}.
 *
 * <p>Loads (or creates) the creative flat world "selftest", kills the player with
 * {@code /kill @s}, takes screenshots on the death screen, respawns and stops the client.
 * Every step is logged with the {@code [SelfTest]} prefix; the run always ends with
 * {@code [SelfTest] DONE}.
 */
public final class SelfTest {
	public static final boolean ENABLED = Boolean.getBoolean("dr.selftest");

	private static final Logger LOGGER = LoggerFactory.getLogger("deathreplay/selftest");
	private static final String WORLD_NAME = "selftest";
	private static final String SCREENSHOT_PREFIX = "selftest_";
	/** Hard stop for the whole run, in wall-clock time. */
	private static final long MAX_RUN_MILLIS = 4 * 60 * 1000L;

	private final Deque<Step> steps = new ArrayDeque<>();
	private Step current;
	private int ticksInStep;
	private int screenshotIndex;
	private int failures;
	private long startMillis;
	private boolean finished;

	private SelfTest() {
	}

	public static void register() {
		SelfTest test = new SelfTest();
		test.buildScript();
		ClientTickEvents.END_CLIENT_TICK.register(test::tick);
		LOGGER.info("[SelfTest] enabled, waiting for the main menu");
	}

	// ---------------------------------------------------------------- script

	private void buildScript() {
		waitUntil("main menu", c -> c.getOverlay() == null && c.currentScreen != null && c.world == null, 20 * 120);
		run("prepare client", c -> {
			// The window is usually unfocused during an unattended run; do not let that pause the game.
			c.options.pauseOnLostFocus = false;
			// The "Move with WASD" tutorial toast would cover a corner of every screenshot.
			c.getTutorialManager().setStep(TutorialStep.NONE);
			deleteOldScreenshots(c);
		});
		run("open world", this::openWorld);
		waitUntil("in world", c -> c.world != null && c.player != null && c.getOverlay() == null, 20 * 120);
		waitTicks("settle", 60);
		run("respawn if the previous run left the player dead", c -> {
			if (c.player.isDead()) {
				c.player.requestRespawn();
			}
		});
		waitUntil("alive", c -> c.player != null && !c.player.isDead() && !(c.currentScreen instanceof DeathScreen), 20 * 20);
		run("set up scene", c -> {
			if (c.currentScreen != null) {
				c.setScreen(null);
			}
			command(c, "time set noon");
			command(c, "weather clear");
			command(c, "tp @s 0.5 -60 0.5 0 10");
			// Landmarks, so the screenshots show where the camera is looking from.
			command(c, "fill 2 -60 3 2 -58 3 minecraft:gold_block");
			command(c, "fill -3 -60 4 -2 -60 4 minecraft:stone");
		});
		waitTicks("wait before death", 80);
		screenshot("alive");

		run("kill player", c -> command(c, "kill @s"));
		waitUntil("death screen", c -> c.currentScreen instanceof DeathScreen, 20 * 10);
		waitTicks("death screen t10", 10);
		screenshot("death_screen_t10");
		waitTicks("death screen t40", 30);

		deathCameraScript();

		run("respawn", c -> c.player.requestRespawn());
		waitUntil("respawned", c -> c.player != null && !c.player.isDead() && !(c.currentScreen instanceof DeathScreen), 20 * 20);
		waitTicks("after respawn", 30);
		run("check camera is back on the player", c -> {
			Camera camera = c.gameRenderer.getCamera();
			double distance = camera.getCameraPos().distanceTo(c.player.getEyePos());
			LOGGER.info("[SelfTest] after respawn: camera is {} blocks from the player's eyes", String.format("%.2f", distance));
			check("death view ended on respawn", !DeathView.isActive());
			check("camera is back at the player's eyes", distance < 0.5);
			check("camera is first person again", !camera.isThirdPerson());
			check("perspective setting untouched", c.options.getPerspective() == Perspective.FIRST_PERSON);
		});
		screenshot("after_respawn");
		// Screenshots are written on a background thread; give them time to land on disk.
		waitTicks("flush screenshots", 40);
	}

	/** Feature (a): death screen free camera. Runs while the death screen is open. */
	private void deathCameraScript() {
		float[] yawBefore = new float[1];

		run("check death camera is detached", c -> {
			Camera camera = c.gameRenderer.getCamera();
			double distance = camera.getCameraPos().distanceTo(c.player.getEyePos());
			LOGGER.info("[SelfTest] death screen: camera at {} is {} blocks from the corpse's eyes", camera.getCameraPos(), String.format("%.2f", distance));
			check("death view active", DeathView.isActive());
			check("camera left the corpse (more than 2 blocks away)", distance > 2.0);
			check("camera is third person", camera.isThirdPerson());
		});
		screenshot("death_screen_third_person");

		run("open spectate screen", DeathView::openSpectate);
		waitTicks("spectate screen", 10);
		run("check spectate screen", c -> check("spectate screen is open", c.currentScreen instanceof DeathSpectateScreen));
		screenshot("spectate_third_person");

		run("switch to orbit", c -> {
			yawBefore[0] = DeathView.getCamera().getYaw();
			DeathView.setMode(DeathCameraMode.ORBIT);
		});
		waitTicks("orbit", 40);
		run("check orbit turned the camera", c -> {
			float turned = DeathView.getCamera().getYaw() - yawBefore[0];
			LOGGER.info("[SelfTest] orbit turned the camera by {} degrees in 2 s", String.format("%.1f", turned));
			check("orbit turns by itself", turned > 15.0F);
		});
		screenshot("spectate_orbit");

		run("switch to free camera, fly up and backwards", c -> {
			DeathView.setMode(DeathCameraMode.FREE);
			DeathView.getSyntheticInput().up = 1.0;
			DeathView.getSyntheticInput().forward = -1.0;
		});
		waitTicks("free flight", 30);
		run("stop and check free flight", c -> {
			DeathView.getSyntheticInput().up = 0.0;
			DeathView.getSyntheticInput().forward = 0.0;
			DetachedCamera camera = DeathView.getCamera();
			camera.setRotation(camera.getYaw(), 40.0F);
			Vec3d corpse = c.player.getEntityPos();
			LOGGER.info("[SelfTest] free camera at {}, corpse at {}", camera.getPos(), corpse);
			check("free camera flew away (more than 8 blocks)", camera.getPos().distanceTo(corpse) > 8.0);
			check("free camera rose (more than 5 blocks up)", camera.getPos().y > corpse.y + 5.0);
		});
		waitTicks("free view", 5);
		screenshot("spectate_free");

		run("fly straight down through the ground", c -> DeathView.getSyntheticInput().up = -1.0);
		waitTicks("descend", 45);
		run("check the camera passed through the ground", c -> {
			DeathView.getSyntheticInput().up = 0.0;
			DetachedCamera camera = DeathView.getCamera();
			// Look back up at the underside of the world from below.
			camera.setRotation(camera.getYaw(), -60.0F);
			double y = camera.getPos().y;
			LOGGER.info("[SelfTest] free camera is at y={} (ground surface is y=-60, bedrock bottom is y=-64)", String.format("%.2f", y));
			check("free camera passes through blocks", y < -64.0);
		});
		waitTicks("view from below", 5);
		screenshot("spectate_free_below_ground");

		run("leave spectate screen", c -> c.currentScreen.close());
		waitTicks("back on death screen", 5);
		run("check death screen is back", c -> check("death screen is back", c.currentScreen instanceof DeathScreen));
		screenshot("back_on_death_screen");
	}

	private void openWorld(MinecraftClient client) {
		if (client.getLevelStorage().levelExists(WORLD_NAME)) {
			LOGGER.info("[SelfTest] loading existing world '{}'", WORLD_NAME);
			client.createIntegratedServerLoader().start(WORLD_NAME, () -> LOGGER.error("[SelfTest] FAIL world load was cancelled"));
		} else {
			LOGGER.info("[SelfTest] creating flat creative world '{}'", WORLD_NAME);
			LevelInfo levelInfo = new LevelInfo(
				WORLD_NAME,
				GameMode.CREATIVE,
				false,
				Difficulty.NORMAL,
				true,
				new GameRules(DataConfiguration.SAFE_MODE.enabledFeatures()),
				DataConfiguration.SAFE_MODE
			);
			Screen parent = client.currentScreen;
			client.createIntegratedServerLoader()
				.createAndStart(WORLD_NAME, levelInfo, GeneratorOptions.createTestWorld(), WorldPresets::createTestOptions, parent);
		}
	}

	// ---------------------------------------------------------------- engine

	private void tick(MinecraftClient client) {
		if (this.finished) {
			return;
		}

		if (this.startMillis == 0L) {
			this.startMillis = System.currentTimeMillis();
		} else if (System.currentTimeMillis() - this.startMillis > MAX_RUN_MILLIS) {
			fail("overall timeout" + (this.current != null ? " during step '" + this.current.name + "'" : ""));
			finish(client);
			return;
		}

		if (this.current == null) {
			this.current = this.steps.poll();
			this.ticksInStep = 0;
			if (this.current == null) {
				finish(client);
				return;
			}
		}

		Step step = this.current;
		try {
			if (step.body.test(client)) {
				LOGGER.info("[SelfTest] OK   {}", step.name);
				this.current = null;
			} else if (++this.ticksInStep > step.timeoutTicks) {
				fail("step '" + step.name + "' timed out after " + step.timeoutTicks + " ticks (screen="
					+ (client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName()) + ")");
				// Nothing after a timed-out wait can be trusted; stop here.
				finish(client);
			}
		} catch (Throwable t) {
			LOGGER.error("[SelfTest] exception in step '{}'", step.name, t);
			fail("step '" + step.name + "' threw " + t);
			finish(client);
		}
	}

	private void finish(MinecraftClient client) {
		this.finished = true;
		LOGGER.info("[SelfTest] RESULT {} ({} failure(s), {} screenshot(s))", this.failures == 0 ? "PASS" : "FAIL", this.failures, this.screenshotIndex);
		LOGGER.info("[SelfTest] DONE");
		client.scheduleStop();
	}

	void fail(String reason) {
		this.failures++;
		LOGGER.error("[SelfTest] FAIL {}", reason);
	}

	/** Records a failed check without aborting the run. */
	void check(String what, boolean ok) {
		if (ok) {
			LOGGER.info("[SelfTest] CHECK ok: {}", what);
		} else {
			fail("check failed: " + what);
		}
	}

	// ---------------------------------------------------------------- step helpers

	void run(String name, java.util.function.Consumer<MinecraftClient> action) {
		this.steps.add(new Step(name, c -> {
			action.accept(c);
			return true;
		}, 0));
	}

	void waitTicks(String name, int ticks) {
		int[] remaining = {ticks};
		this.steps.add(new Step("wait " + ticks + " ticks (" + name + ")", c -> --remaining[0] <= 0, ticks + 5));
	}

	void waitUntil(String name, Predicate<MinecraftClient> condition, int timeoutTicks) {
		this.steps.add(new Step("wait for " + name, condition, timeoutTicks));
	}

	void waitUntil(String name, BooleanSupplier condition, int timeoutTicks) {
		waitUntil(name, c -> condition.getAsBoolean(), timeoutTicks);
	}

	/** Saves the last rendered frame to {@code run/screenshots/selftest_NN_<stage>.png}. */
	void screenshot(String stage) {
		run("screenshot " + stage, c -> {
			String fileName = String.format("%s%02d_%s.png", SCREENSHOT_PREFIX, ++this.screenshotIndex, stage);
			ScreenshotRecorder.saveScreenshot(c.runDirectory, fileName, c.getFramebuffer(), 1,
				message -> LOGGER.info("[SelfTest] screenshot saved: {}", fileName));
		});
	}

	static void command(MinecraftClient client, String command) {
		LOGGER.info("[SelfTest] command: /{}", command);
		client.player.networkHandler.sendChatCommand(command);
	}

	private static void deleteOldScreenshots(MinecraftClient client) {
		File[] old = new File(client.runDirectory, ScreenshotRecorder.SCREENSHOTS_DIRECTORY)
			.listFiles((dir, name) -> name.startsWith(SCREENSHOT_PREFIX) && name.endsWith(".png"));
		if (old != null) {
			for (File file : old) {
				file.delete();
			}
		}
	}

	private record Step(String name, Predicate<MinecraftClient> body, int timeoutTicks) {
	}
}
