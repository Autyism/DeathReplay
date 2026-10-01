package io.github.autyi6969.deathreplay.selftest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import io.github.autyi6969.deathreplay.camera.DeathCameraMode;
import io.github.autyi6969.deathreplay.camera.DetachedCamera;
import io.github.autyi6969.deathreplay.config.DeathReplayConfig;
import io.github.autyi6969.deathreplay.config.SettingsScreen;
import io.github.autyi6969.deathreplay.death.DeathSpectateScreen;
import io.github.autyi6969.deathreplay.death.DeathView;
import io.github.autyi6969.deathreplay.record.EntitySample;
import io.github.autyi6969.deathreplay.record.Frame;
import io.github.autyi6969.deathreplay.record.RecordedEvent;
import io.github.autyi6969.deathreplay.record.Recorder;
import io.github.autyi6969.deathreplay.record.Recording;
import io.github.autyi6969.deathreplay.record.ReplayFileWriter;
import io.github.autyi6969.deathreplay.replay.PuppetPlayerEntity;
import io.github.autyi6969.deathreplay.replay.Replay;
import io.github.autyi6969.deathreplay.replay.ReplayScreen;
import io.github.autyi6969.deathreplay.replay.ReplayView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.tutorial.TutorialStep;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.util.math.BlockPos;
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
	private static final int TEST_BUFFER_SECONDS = 6;

	private final Deque<Step> steps = new ArrayDeque<>();
	private Step current;
	private int ticksInStep;
	private int screenshotIndex;
	private int failures;
	private long startMillis;
	private boolean finished;
	private int realBufferSeconds;
	private boolean realAutoSave;

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
			// In memory only (never saved): a short buffer so the test can fill it, and auto-save on.
			this.realBufferSeconds = DeathReplayConfig.get().bufferSeconds;
			this.realAutoSave = DeathReplayConfig.get().autoSave;
			DeathReplayConfig.get().bufferSeconds = TEST_BUFFER_SECONDS;
			DeathReplayConfig.get().autoSave = true;
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
			// Known actors for the recorder: leftovers of earlier runs out, fresh ones in.
			command(c, "kill @e[type=minecraft:pig]");
			command(c, "kill @e[type=minecraft:armor_stand]");
			command(c, "kill @e[type=minecraft:item]");
			command(c, "setblock 3 -60 8 minecraft:air");
			command(c, "summon minecraft:pig 4 -60 10");
			command(c, "summon minecraft:armor_stand -2 -60 9 {equipment:{head:{id:\"minecraft:diamond_helmet\"}}}");
		});
		waitTicks("scene settles", 20);
		run("walk forward", c -> c.options.forwardKey.setPressed(true));
		waitTicks("walking", 30);
		run("stop walking", c -> c.options.forwardKey.setPressed(false));
		waitTicks("stand", 10);
		run("make things happen", c -> {
			command(c, "setblock 3 -60 8 minecraft:diamond_block");
			command(c, "particle minecraft:flame 0.5 -58 10 0.3 0.3 0.3 0.02 30");
			command(c, "playsound minecraft:block.anvil.land master @s 0.5 -60 10");
		});
		waitTicks("block stays", 20);
		run("remove the block again", c -> command(c, "setblock 3 -60 8 minecraft:air"));
		waitTicks("wait before death", 40);
		screenshot("alive");

		run("kill player", c -> command(c, "kill @s"));
		waitUntil("death screen", c -> c.currentScreen instanceof DeathScreen, 20 * 10);
		waitTicks("death screen t10", 10);
		screenshot("death_screen_t10");
		waitTicks("death screen t40", 30);

		recorderScript();
		replayScript();
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

		respawnDuringReplayScript();
		settingsScript();
		// Screenshots are written on a background thread; give them time to land on disk.
		waitTicks("flush screenshots", 40);
	}

	/** Feature (b): the recorder. Runs on the death screen, after the recording has been frozen. */
	private void recorderScript() {
		waitUntil("recording frozen", () -> Recorder.getFrozen() != null, 60);
		run("check the frozen recording", c -> {
			Recording recording = Recorder.getFrozen();
			int window = TEST_BUFFER_SECONDS * Recording.TICKS_PER_SECOND;
			LOGGER.info("[SelfTest] recording: {} ticks, death at tick {}", recording.tickCount(), recording.deathFrame());
			check("ring buffer kept exactly the last " + TEST_BUFFER_SECONDS + " s before death", recording.deathFrame() == window - 1);
			check("death tail was appended", recording.tickCount() == window + Recorder.DEATH_TAIL_TICKS);

			int blockChanges = 0;
			int particles = 0;
			int sounds = 0;
			int damage = 0;
			Set<String> types = new TreeSet<>();
			for (Frame frame : recording.frames()) {
				for (EntitySample sample : frame.entities()) {
					types.add(Registries.ENTITY_TYPE.getId(sample.appearance.type()).getPath());
				}

				for (RecordedEvent event : frame.events()) {
					switch (event) {
						case RecordedEvent.BlockChange e -> blockChanges++;
						case RecordedEvent.Particle e -> particles++;
						case RecordedEvent.Sound e -> sounds++;
						case RecordedEvent.EntityDamage e -> damage++;
						default -> {
						}
					}
				}
			}

			LOGGER.info("[SelfTest] recorded entity types: {}", types);
			LOGGER.info("[SelfTest] recorded events: {} block changes, {} particle packets, {} sound packets, {} damage packets", blockChanges, particles, sounds, damage);
			check("the player was recorded", types.contains("player"));
			check("the pig was recorded", types.contains("pig"));
			check("the armor stand was recorded", types.contains("armor_stand"));
			check("both block changes were recorded", blockChanges >= 2);
			check("the particle packet was recorded", particles >= 1);
			check("the sound packet was recorded", sounds >= 1);

			EntitySample first = recording.playerAt(0);
			EntitySample atDeath = recording.playerAt(recording.deathFrame());
			EntitySample last = recording.playerAt(recording.tickCount() - 1);
			double walked = first.pos().distanceTo(atDeath.pos());
			LOGGER.info("[SelfTest] player moved {} blocks inside the recording", String.format("%.2f", walked));
			check("the player's walk was recorded", walked > 3.0);
			check("the death animation is in the tail", last.deathTime > 0);

			EntitySample stand = null;
			for (EntitySample sample : recording.frames().get(recording.deathFrame()).entities()) {
				if (sample.appearance.type() == EntityType.ARMOR_STAND) {
					stand = sample;
				}
			}

			check("armor stand equipment was recorded", stand != null && stand.appearance.equipment() != null
				&& stand.appearance.equipment()[EquipmentSlot.HEAD.ordinal()].isOf(Items.DIAMOND_HELMET));
			check("spawn packet was kept for the armor stand", stand != null && stand.appearance.spawnPacket() != null);
		});

		waitUntil("recording saved to disk", () -> ReplayFileWriter.getLastSavedFile() != null, 100);
		run("check the saved file", c -> {
			Path file = ReplayFileWriter.getLastSavedFile();
			try {
				NbtCompound nbt = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
				LOGGER.info("[SelfTest] saved file {} ({} bytes): {} ticks, {} entity tracks, {} block changes, {} events",
					file, Files.size(file), nbt.getInt("TickCount", -1), nbt.getListOrEmpty("Entities").size(),
					nbt.getListOrEmpty("BlockChanges").size(), nbt.getListOrEmpty("Events").size());
				check("file is in the deathreplay folder", file.getParent().equals(c.runDirectory.toPath().resolve("deathreplay")));
				check("file has the same tick count", nbt.getInt("TickCount", -1) == Recorder.getFrozen().tickCount());
				check("file has entity tracks", nbt.getListOrEmpty("Entities").size() >= 3);
				check("file has the block changes", nbt.getListOrEmpty("BlockChanges").size() >= 2);
				check("file has events", !nbt.getListOrEmpty("Events").isEmpty());
			} catch (IOException e) {
				fail("could not read back " + file + ": " + e);
			}
		});
	}

	/** Feature (d): settings screen and Mod Menu entry. Runs while alive, with no screen open. */
	private void settingsScript() {
		run("restore the real settings", c -> {
			// The test changed two settings in memory; the settings screen saves on close and
			// must write back what the user actually had.
			DeathReplayConfig.get().bufferSeconds = this.realBufferSeconds;
			DeathReplayConfig.get().autoSave = this.realAutoSave;
		});
		run("check settings file round trip", c -> {
			Path file = DeathReplayConfig.defaultPath().resolveSibling("deathreplay-selftest.json");
			DeathReplayConfig written = new DeathReplayConfig();
			written.bufferSeconds = 45;
			written.autoSave = true;
			written.replayView = ReplayView.FREE;
			written.deathFreeCamera = false;
			written.deathCameraMode = DeathCameraMode.ORBIT;
			written.writeTo(file);
			DeathReplayConfig read = DeathReplayConfig.readFrom(file);
			check("settings survive a save and load", read.bufferSeconds == 45 && read.autoSave && read.replayView == ReplayView.FREE
				&& !read.deathFreeCamera && read.deathCameraMode == DeathCameraMode.ORBIT);
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				fail("could not delete " + file);
			}
		});
		run("open settings through the Mod Menu entry point", c -> {
			boolean modMenu = FabricLoader.getInstance().isModLoaded("modmenu");
			check("Mod Menu is loaded in the dev client", modMenu);
			Screen screen = modMenu ? ModMenuProbe.createConfigScreen(c.currentScreen) : new SettingsScreen(c.currentScreen);
			check("Mod Menu entry point creates the settings screen", screen instanceof SettingsScreen);
			c.setScreen(screen);
		});
		waitTicks("settings screen", 5);
		run("check settings screen", c -> {
			check("settings screen is open", c.currentScreen instanceof SettingsScreen);
			LOGGER.info("[SelfTest] settings screen has {} widgets", c.currentScreen.children().size());
			check("settings screen has its 5 options and the Done button", c.currentScreen.children().size() == 6);
		});
		screenshot("settings_screen");
		run("close settings", c -> c.currentScreen.close());
		waitTicks("settings closed", 3);
		run("check settings closed", c -> check("settings screen closed back to the game", c.currentScreen == null));

		run("open the Mod Menu mod list", c -> {
			try {
				Screen mods = (Screen) Class.forName("com.terraformersmc.modmenu.gui.ModsScreen").getConstructor(Screen.class).newInstance(c.currentScreen);
				c.setScreen(mods);
			} catch (ReflectiveOperationException e) {
				LOGGER.warn("[SelfTest] could not open the Mod Menu list (not a failure): {}", e.toString());
			}
		});
		waitTicks("mod list", 10);
		screenshot("modmenu_list");
		run("close the mod list", c -> c.setScreen(null));
		waitTicks("mod list closed", 3);
	}

	/**
	 * Red line check: a respawn that arrives in the middle of a replay must end the replay at
	 * once and leave nothing of it behind. Uses a second death.
	 */
	private void respawnDuringReplayScript() {
		BlockPos testBlock = new BlockPos(3, -60, 8);

		run("second death: place a block, then die", c -> {
			command(c, "tp @s 0.5 -60 0.5 0 10");
			command(c, "setblock 3 -60 8 minecraft:diamond_block");
		});
		waitTicks("alive again for a while", 60);
		run("kill player again", c -> command(c, "kill @s"));
		waitUntil("second death screen", c -> c.currentScreen instanceof DeathScreen, 20 * 10);
		waitUntil("second recording frozen", () -> Recorder.getFrozen() != null, 60);
		run("open the replay of the second death", c -> {
			c.inGameHud.getChatHud().clear(false);
			check("second replay opened", Replay.open(c));
		});
		waitTicks("second replay runs", 10);
		run("respawn while the replay is running", c -> {
			check("replay is running and shows the past (block not placed yet)", Replay.isActive() && c.world.getBlockState(testBlock).isAir());
			// Exactly what the vanilla Respawn button sends; here it stands for any respawn the
			// server decides on while a replay is open.
			c.player.requestRespawn();
		});
		waitUntil("respawned", c -> c.player != null && !c.player.isDead(), 20 * 20);
		waitTicks("after the respawn", 5);
		run("check the replay ended with the respawn", c -> {
			Camera camera = c.gameRenderer.getCamera();
			double distance = camera.getCameraPos().distanceTo(c.player.getEyePos());
			check("replay stopped by itself", !Replay.isActive());
			check("replay screen closed by itself", c.currentScreen == null);
			check("the recording was dropped", Recorder.getFrozen() == null);
			check("replay is no longer offered", !Replay.isAvailable(c));
			check("camera is back at the living player's eyes", distance < 0.5 && !camera.isThirdPerson());
			check("world is back in the present: block is placed", c.world.getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK));
			int leftovers = 0;
			for (Entity entity : c.world.getEntities()) {
				if (entity.getId() < 0) {
					leftovers++;
				}
			}

			check("no puppet survived the respawn", leftovers == 0 && Replay.puppetCount() == 0);
			command(c, "setblock 3 -60 8 minecraft:air");
		});
		waitTicks("settle", 20);
		screenshot("after_respawn_during_replay");
	}

	/** Feature (c): the replay. Runs on the death screen, after the recorder checks. */
	private void replayScript() {
		BlockPos testBlock = new BlockPos(3, -60, 8);

		run("open replay", c -> {
			// Chat lines would cover the lower half of every screenshot.
			c.inGameHud.getChatHud().clear(false);
			check("replay is available on the death screen", Replay.isAvailable(c));
			check("replay opened", Replay.open(c));
		});
		waitTicks("replay starts", 2);
		run("check replay start", c -> {
			check("replay screen is open", c.currentScreen instanceof ReplayScreen);
			check("replay is active", Replay.isActive());
			check("replay starts in third person", Replay.getView() == ReplayView.THIRD_PERSON);
			LOGGER.info("[SelfTest] replay: {} puppets at tick {}", Replay.puppetCount(), Replay.getTick());
			check("puppets were created", Replay.puppetCount() >= 3);
			check("the player has a puppet", Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			check("world was rewound: test block not placed yet", c.world.getBlockState(testBlock).isAir());
			int realVisible = 0;
			int puppets = 0;
			for (Entity entity : c.world.getEntities()) {
				if (Replay.isPuppet(entity)) {
					puppets++;
				} else if (!Replay.hidesEntity(entity)) {
					realVisible++;
				}
			}

			check("puppets are in the world", puppets == Replay.puppetCount());
			check("real entities are hidden during the replay", realVisible == 0);
		});

		Vec3d[] puppetStart = new Vec3d[1];
		waitUntil("replay tick 22 (walk has begun)", () -> Replay.getTick() >= 22, 100);
		run("remember where the player puppet is", c -> puppetStart[0] = Replay.getPlayerPuppet().getEntityPos());
		waitUntil("replay tick 36 (mid walk)", () -> Replay.getTick() >= 36, 100);
		run("check the player puppet walks", c -> {
			double moved = Replay.getPlayerPuppet().getEntityPos().distanceTo(puppetStart[0]);
			LOGGER.info("[SelfTest] player puppet moved {} blocks in 14 ticks", String.format("%.2f", moved));
			check("the player puppet walks", moved > 1.5);
		});
		screenshot("replay_third_person_walking");

		waitUntil("replay tick 70 (block placed)", () -> Replay.getTick() >= 70, 100);
		run("check the block change is replayed", c ->
			check("test block is there during the replay", c.world.getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK)));
		screenshot("replay_third_person_block_placed");

		run("first person", c -> Replay.setView(ReplayView.FIRST_PERSON));
		waitTicks("first person", 3);
		run("check first person camera", c -> {
			Entity puppet = Replay.getPlayerPuppet();
			Vec3d eyes = puppet.getEntityPos().add(0.0, puppet.getStandingEyeHeight(), 0.0);
			double distance = c.gameRenderer.getCamera().getCameraPos().distanceTo(eyes);
			LOGGER.info("[SelfTest] first person: camera is {} blocks from the puppet's eyes", String.format("%.2f", distance));
			check("first person camera sits in the puppet's eyes", distance < 0.3);
			check("the player's own puppet is hidden in first person", Replay.hidesEntity(puppet));
		});
		screenshot("replay_first_person");

		waitUntil("replay tick 96 (block removed)", () -> Replay.getTick() >= 96, 100);
		run("check the block removal is replayed", c ->
			check("test block is gone again during the replay", c.world.getBlockState(testBlock).isAir()));

		run("free view, fly up and backwards", c -> {
			Replay.setView(ReplayView.FREE);
			Replay.getSyntheticInput().up = 1.0;
			Replay.getSyntheticInput().forward = -1.0;
		});
		waitTicks("free flight", 16);
		run("stop flying and look down", c -> {
			Replay.getSyntheticInput().up = 0.0;
			Replay.getSyntheticInput().forward = 0.0;
			DetachedCamera camera = Replay.getCamera();
			camera.setRotation(camera.getYaw(), 40.0F);
			double distance = camera.getPos().distanceTo(Replay.getPlayerPuppet().getEntityPos());
			LOGGER.info("[SelfTest] replay free camera is {} blocks from the player puppet", String.format("%.2f", distance));
			check("free view flew away from the player", distance > 5.0);
		});
		waitTicks("free view", 3);
		screenshot("replay_free");

		waitUntil("replay reaches the end", () -> Replay.isAtEnd() && !Replay.isPlaying(), 100);
		run("check the end of the replay", c -> {
			Entity puppet = Replay.getPlayerPuppet();
			check("the player puppet is dead at the end", puppet instanceof LivingEntity living && living.isDead() && living.deathTime > 0);
			Replay.setView(ReplayView.THIRD_PERSON);
		});
		waitTicks("death view", 3);
		screenshot("replay_end_death");

		run("seek back and pause", c -> {
			Replay.seek(40);
			Replay.setPlaying(false);
		});
		waitTicks("paused", 10);
		run("check seek and pause", c -> {
			check("seek jumped to the requested tick and pause holds it", Replay.getTick() == 40 && !Replay.isPlaying());
			check("puppets exist after seeking", Replay.puppetCount() >= 3);
			check("world was rewound by the seek", c.world.getBlockState(testBlock).isAir());
		});
		screenshot("replay_paused_after_seek");

		run("seek into the block window", c -> Replay.seek(66));
		waitTicks("after seek", 2);
		run("check seeking forward applies block changes", c ->
			check("test block is there after seeking forward", c.world.getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK)));

		run("close the replay", c -> c.currentScreen.close());
		waitTicks("replay closed", 3);
		run("check everything is put back", c -> {
			check("replay is over", !Replay.isActive());
			check("death screen is back", c.currentScreen instanceof DeathScreen);
			check("world is back in the present: test block is gone", c.world.getBlockState(testBlock).isAir());
			int leftovers = 0;
			for (Entity entity : c.world.getEntities()) {
				if (entity.getId() < 0) {
					leftovers++;
				}
			}

			check("no puppet is left in the world", leftovers == 0 && Replay.puppetCount() == 0);
		});
		screenshot("death_screen_after_replay");
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
