package io.github.autyi6969.deathreplay.selftest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import io.github.autyi6969.deathreplay.camera.CameraInput;
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
import io.github.autyi6969.deathreplay.record.ReplayFileReader;
import io.github.autyi6969.deathreplay.record.ReplayFileWriter;
import io.github.autyi6969.deathreplay.replay.PuppetPlayerEntity;
import io.github.autyi6969.deathreplay.replay.Replay;
import io.github.autyi6969.deathreplay.replay.ReplayBrowserScreen;
import io.github.autyi6969.deathreplay.replay.ReplayScreen;
import io.github.autyi6969.deathreplay.replay.ReplayView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.world.ClientWorld;
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
import org.lwjgl.glfw.GLFW;
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
	/** One replay file is kept between runs, to be played after the "restart" that the next run is. */
	private static final String KEPT_REPLAY_FILE = "death_selftest-kept.nbt";

	private final Deque<Step> steps = new ArrayDeque<>();
	private Step current;
	private int ticksInStep;
	private int screenshotIndex;
	private int failures;
	private long startMillis;
	private boolean finished;
	private int realBufferSeconds;
	private boolean realAutoSave;
	private Set<Path> replayFilesBefore = Set.of();

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
			// Somebody may be using the computer while this runs; their mouse must not steer the test.
			CameraInput.ignoreRealInput = true;
			// The "Move with WASD" tutorial toast would cover a corner of every screenshot.
			c.getTutorialManager().setStep(TutorialStep.NONE);
			// In memory only (never saved): a short buffer so the test can fill it, and auto-save on.
			this.realBufferSeconds = DeathReplayConfig.get().bufferSeconds;
			this.realAutoSave = DeathReplayConfig.get().autoSave;
			DeathReplayConfig.get().bufferSeconds = TEST_BUFFER_SECONDS;
			DeathReplayConfig.get().autoSave = true;
			deleteOldScreenshots(c);
			this.replayFilesBefore = Set.copyOf(ReplayBrowserScreen.listFiles(ReplayFileWriter.directory(c)));
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
		afterRestartScript();
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
			// A 48-block cube of solid stone with a lit room in its middle, for the "free camera
			// inside rock" check. Built once; it stays in the self-test world.
			if (!c.world.getBlockState(new BlockPos(33, -31, 33)).isOf(Blocks.STONE) || !c.world.getBlockState(new BlockPos(55, -12, 55)).isOf(Blocks.GLOWSTONE)) {
				for (int y = -32; y < 16; y += 12) {
					command(c, "fill 32 " + y + " 32 79 " + (y + 11) + " 79 minecraft:stone");
				}

				command(c, "fill 50 -12 50 61 -3 61 minecraft:air");
				command(c, "fill 54 -12 54 57 -12 57 minecraft:glowstone");
				command(c, "fill 55 -11 55 56 -9 56 minecraft:gold_block");
			}

			// Known actors for the recorder: leftovers of earlier runs out, fresh ones in.
			command(c, "kill @e[type=minecraft:pig]");
			command(c, "kill @e[type=minecraft:armor_stand]");
			command(c, "kill @e[type=minecraft:item]");
			command(c, "setblock 3 -60 8 minecraft:air");
			command(c, "summon minecraft:pig 4 -60 10");
			command(c, "summon minecraft:armor_stand -2 -60 9 {equipment:{head:{id:\"minecraft:diamond_helmet\"}}}");
		});
		waitTicks("scene settles", 45);
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
		startPacketAudit("the death screen, the replay and looking around");
		replayScript();
		deathCameraScript();
		endPacketAudit("the death screen, the replay and looking around", false);

		run("respawn", c -> c.player.requestRespawn());
		waitUntil("respawned", c -> c.player != null && !c.player.isDead() && !(c.currentScreen instanceof DeathScreen), 20 * 20);
		waitTicks("after respawn", 30);
		run("check camera is back on the player", c -> {
			Camera camera = c.gameRenderer.getCamera();
			double distance = camera.getCameraPos().distanceTo(c.player.getEyePos());
			LOGGER.info("[SelfTest] after respawn: camera is {} blocks from the player's eyes", String.format("%.2f", distance));
			check("death view ended on respawn", !DeathView.isActive());
			int puppets = 0;
			for (Entity entity : c.world.getEntities()) {
				if (entity.getId() < 0) {
					puppets++;
				}
			}

			check("the still ended on respawn and left nothing behind", !Replay.isActive() && puppets == 0 && Replay.puppetCount() == 0);
			check("camera is back at the player's eyes", distance < 0.5);
			check("camera is first person again", !camera.isThirdPerson());
			check("perspective setting untouched", c.options.getPerspective() == Perspective.FIRST_PERSON);
		});
		screenshot("after_respawn");

		respawnDuringReplayScript();
		combatScript();
		savedFileScript();
		settingsScript();
		performanceScript();
		run("delete the replay files this run saved, keeping one for the next run", c -> {
			int deleted = 0;
			boolean kept = false;
			for (Path file : ReplayBrowserScreen.listFiles(ReplayFileWriter.directory(c))) {
				if (this.replayFilesBefore.contains(file)) {
					continue;
				}

				try {
					if (!kept) {
						// Newest first: this one becomes the "saved before a restart" file of the next run.
						Files.move(file, file.resolveSibling(KEPT_REPLAY_FILE), StandardCopyOption.REPLACE_EXISTING);
						kept = true;
					} else {
						Files.delete(file);
						deleted++;
					}
				} catch (IOException e) {
					LOGGER.warn("[SelfTest] could not clean up {}", file, e);
				}
			}

			LOGGER.info("[SelfTest] deleted {} replay files saved by this run, kept one as {}", deleted, KEPT_REPLAY_FILE);
		});
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
			LOGGER.info("[SelfTest] terrain snapshot: {} chunks, radius {}", recording.snapshot().chunks().size(), recording.snapshot().radius());
			check("the terrain around the death was copied", recording.snapshot().chunks().size() >= 9);
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

	/**
	 * A death that looks like a real one: in survival, beaten to death by a zombie. Checks that
	 * a fight (mob attacks, damage, hurt animations) ends up in the recording and the replay.
	 */
	private void combatScript() {
		boolean[] killedByZombie = new boolean[1];

		run("combat scene: survival, one zombie, almost no health", c -> {
			command(c, "tp @s 0.5 -60 0.5 0 10");
			command(c, "kill @e[type=minecraft:zombie]");
			command(c, "gamemode survival");
			command(c, "summon minecraft:zombie 0.5 -60 3.5 {PersistenceRequired:1b}");
			command(c, "damage @s 17");
		});
		waitUntilSoft("the zombie kills the player", c -> c.currentScreen instanceof DeathScreen, 20 * 25);
		run("make sure the player is dead", c -> {
			killedByZombie[0] = c.currentScreen instanceof DeathScreen;
			if (!killedByZombie[0]) {
				LOGGER.info("[SelfTest] NOTE the zombie did not kill the player in 25 s; using /kill instead (not a failure)");
				command(c, "kill @s");
			}
		});
		waitUntil("combat death screen", c -> c.currentScreen instanceof DeathScreen, 20 * 10);
		waitUntil("combat recording frozen", () -> Recorder.getFrozen() != null, 60);
		run("check the fight was recorded", c -> {
			Recording recording = Recorder.getFrozen();
			int hitsOnPlayer = 0;
			boolean zombie = false;
			for (Frame frame : recording.frames()) {
				for (EntitySample sample : frame.entities()) {
					zombie |= sample.appearance.type() == EntityType.ZOMBIE;
				}

				for (RecordedEvent event : frame.events()) {
					if (event instanceof RecordedEvent.EntityDamage damage && damage.packet().entityId() == recording.playerEntityId()) {
						hitsOnPlayer++;
					}
				}
			}

			LOGGER.info("[SelfTest] combat recording: {} ticks, death message '{}', {} damage packets on the player, zombie recorded: {}",
				recording.tickCount(), recording.deathMessage() == null ? "" : recording.deathMessage().getString(), hitsOnPlayer, zombie);
			check("the zombie is in the recording", zombie);
			check("the death message was captured", recording.deathMessage() != null);
			if (killedByZombie[0]) {
				check("the hits on the player were recorded", hitsOnPlayer >= 2);
			}

			c.inGameHud.getChatHud().clear(false);
			check("combat replay opened", Replay.open(c));
			Replay.seek(Math.max(0, recording.deathFrame() - 14));
		});
		waitTicks("combat replay plays", 8);
		run("pause shortly before the death", c -> Replay.setPlaying(false));
		waitTicks("paused", 3);
		screenshot("combat_replay_before_death");
		run("play to the end", c -> Replay.setPlaying(true));
		waitUntil("combat replay reaches the end", () -> Replay.isAtEnd() && !Replay.isPlaying(), 100);
		waitTicks("end", 3);
		screenshot("combat_replay_death");
		run("close the combat replay", c -> c.currentScreen.close());
		waitTicks("closed", 3);
		screenshot("combat_death_screen_still");
		run("respawn after the fight", c -> c.player.requestRespawn());
		waitUntil("respawned after the fight", c -> c.player != null && !c.player.isDead() && !(c.currentScreen instanceof DeathScreen), 20 * 20);
		run("clean up the combat scene", c -> {
			command(c, "gamemode creative");
			command(c, "kill @e[type=minecraft:zombie]");
			command(c, "kill @e[type=minecraft:item]");
		});
		waitTicks("cleaned up", 10);
	}

	/** How much the always-on recorder costs per tick, with a normal and with a crowded scene. */
	private void performanceScript() {
		run("measure the recorder: normal scene", c -> Recorder.resetCaptureStats());
		waitTicks("recording a normal scene", 100);
		run("crowd the scene with 150 pigs", c -> {
			LOGGER.info("[SelfTest] recorder cost with {} entities nearby: average {} us per tick, worst tick {} us", countEntities(c),
				String.format("%.1f", Recorder.averageCaptureMicros()), String.format("%.1f", Recorder.maxCaptureMicros()));
			for (int i = 0; i < 150; i++) {
				c.player.networkHandler.sendChatCommand("summon minecraft:pig " + (6 + i % 15) + " -60 " + (12 + i / 15));
			}
		});
		waitTicks("pigs spawn", 40);
		run("measure the recorder: crowded scene", c -> {
			c.inGameHud.getChatHud().clear(false);
			Recorder.resetCaptureStats();
		});
		waitTicks("recording a crowded scene", 100);
		run("check the recorder stays cheap", c -> {
			double average = Recorder.averageCaptureMicros();
			LOGGER.info("[SelfTest] recorder cost with {} entities nearby: average {} us per tick, worst tick {} us (a tick is 50000 us; fps now {})",
				countEntities(c), String.format("%.1f", average), String.format("%.1f", Recorder.maxCaptureMicros()), c.getCurrentFps());
			check("recording a crowded scene costs less than 1 ms per tick (2% of a tick)", average < 1000.0);
			c.player.networkHandler.sendChatCommand("kill @e[type=minecraft:pig]");
			c.player.networkHandler.sendChatCommand("kill @e[type=minecraft:item]");
		});
		waitTicks("pigs gone", 20);
	}

	private static int countEntities(MinecraftClient client) {
		int count = 0;
		for (Entity entity : client.world.getEntities()) {
			if (entity.squaredDistanceTo(client.player) <= Recorder.CAPTURE_RADIUS * Recorder.CAPTURE_RADIUS) {
				count++;
			}
		}

		return count;
	}

	/**
	 * "Saved replays survive a restart": plays the file the previous self-test run left behind,
	 * in this freshly started game, before anything has been recorded in this session.
	 */
	private void afterRestartScript() {
		boolean[] present = new boolean[1];

		run("look for a replay saved by the previous run", c -> {
			Path file = ReplayFileWriter.directory(c).resolve(KEPT_REPLAY_FILE);
			present[0] = Files.isRegularFile(file);
			if (!present[0]) {
				LOGGER.info("[SelfTest] NOTE no replay file from a previous run; the after-restart check is skipped this time (not a failure)");
				return;
			}

			check("nothing has been recorded in this session yet", Recorder.getLast() == null && !Replay.isAvailable(c));
			c.inGameHud.getChatHud().clear(false);
			ReplayBrowserScreen browser = new ReplayBrowserScreen(null);
			c.setScreen(browser);
			browser.play(file);
		});
		waitTicks("replay from the previous run", 15);
		run("check the replay saved before the restart", c -> {
			if (!present[0]) {
				return;
			}

			check("a replay saved before the restart plays", c.currentScreen instanceof ReplayScreen && Replay.isPlayback() && Replay.getStage() != null);
			LOGGER.info("[SelfTest] replay from before the restart: {} ticks, {} puppets", Replay.getRecording() == null ? 0 : Replay.getRecording().tickCount(), Replay.puppetCount());
			check("its actors are on stage", Replay.puppetCount() >= 2 && Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			Replay.seek(Replay.getRecording().deathFrame() - 6);
			Replay.setPlaying(false);
		});
		waitTicks("paused before the death", 5);
		screenshot("replay_saved_before_restart");
		run("close the replay from the previous run", c -> {
			if (present[0]) {
				c.currentScreen.close();
			}
		});
		waitTicks("closed", 3);
		run("close the list", c -> {
			if (c.currentScreen != null) {
				c.setScreen(null);
			}
		});
		waitTicks("back in the game", 3);
	}

	/** Saved files: what was written can be read back, listed and played. Runs while alive. */
	private void savedFileScript() {
		Recording[] loaded = new Recording[1];

		run("read the saved replay back", c -> {
			Recording original = Recorder.getLast();
			Path file = ReplayFileWriter.getLastSavedFile();
			check("the latest death was saved to a file", original != null && file != null && ReplayFileWriter.isSaved(original));
			try {
				loaded[0] = ReplayFileReader.read(file, c.world.getRegistryManager());
			} catch (IOException e) {
				fail("could not read " + file + ": " + e);
				return;
			}

			Recording copy = loaded[0];
			LOGGER.info("[SelfTest] read back {} ({} bytes): {} ticks, {} chunks", file.getFileName(), file.toFile().length(), copy.tickCount(), copy.snapshot().chunks().size());
			check("same length and death tick", copy.tickCount() == original.tickCount() && copy.deathFrame() == original.deathFrame());
			check("same terrain", copy.snapshot().chunks().size() == original.snapshot().chunks().size() && copy.snapshot().dimension() == original.snapshot().dimension());
			check("same death message", original.deathMessage() != null && copy.deathMessage() != null && original.deathMessage().getString().equals(copy.deathMessage().getString()));
			boolean sameFrames = true;
			int eventsOriginal = 0;
			int eventsCopy = 0;
			for (int i = 0; i < original.tickCount(); i++) {
				Frame a = original.frames().get(i);
				Frame b = copy.frames().get(i);
				eventsOriginal += a.events().length;
				eventsCopy += b.events().length;
				sameFrames &= a.entities().length == b.entities().length;
				for (EntitySample sample : a.entities()) {
					EntitySample other = b.find(sample.entityId);
					sameFrames &= other != null
						&& other.x == sample.x && other.y == sample.y && other.z == sample.z
						&& other.yaw == sample.yaw && other.pitch == sample.pitch && other.headYaw == sample.headYaw
						&& other.eyeHeight == sample.eyeHeight && other.vehicleId == sample.vehicleId
						&& other.handSwingTicks == sample.handSwingTicks && other.deathTime == sample.deathTime
						&& other.appearance.type() == sample.appearance.type()
						&& other.appearance.trackedData().size() == sample.appearance.trackedData().size()
						&& (other.appearance.equipment() == null) == (sample.appearance.equipment() == null);
				}
			}

			check("every entity sample of every tick is identical", sameFrames);
			check("same number of events", eventsOriginal == eventsCopy && eventsCopy > 0);
		});

		run("open the replay list", c -> c.setScreen(new ReplayBrowserScreen(null)));
		waitTicks("replay list", 5);
		run("check the replay list", c -> {
			check("the replay list is open", c.currentScreen instanceof ReplayBrowserScreen);
			int saved = ReplayBrowserScreen.listFiles(ReplayFileWriter.directory(c)).size();
			LOGGER.info("[SelfTest] replay list: {} saved files, {} widgets", saved, c.currentScreen.children().size());
			check("saved replays are listed", saved >= 1 && c.currentScreen.children().size() >= 6);
		});
		screenshot("replay_list");
		run("play the newest saved file from the list", c -> {
			ReplayBrowserScreen browser = (ReplayBrowserScreen) c.currentScreen;
			browser.play(ReplayBrowserScreen.listFiles(ReplayFileWriter.directory(c)).getFirst());
		});
		waitTicks("replay from file runs", 15);
		run("check the replay from the file", c -> {
			check("a replay read from a file plays", c.currentScreen instanceof ReplayScreen && Replay.isPlayback() && Replay.getStage() != null);
			check("it is the file's recording, not the one in memory", Replay.getRecording() != null && Replay.getRecording() != Recorder.getLast());
			LOGGER.info("[SelfTest] replay from file: {} puppets at tick {}", Replay.puppetCount(), Replay.getTick());
			check("its actors are on stage", Replay.puppetCount() >= 3 && Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			check("its terrain is on stage", Replay.getStage().getBlockState(new BlockPos(2, -60, 3)).isOf(Blocks.GOLD_BLOCK));
			Replay.seek(Replay.getRecording().deathFrame() - 6);
			Replay.setPlaying(false);
		});
		waitTicks("paused before the death", 5);
		screenshot("replay_from_file");
		run("close the replay from the file", c -> c.currentScreen.close());
		waitTicks("back in the list", 3);
		run("check the list is back, then close it", c -> {
			check("closing the replay returns to the list", c.currentScreen instanceof ReplayBrowserScreen && !Replay.isActive());
			c.currentScreen.close();
		});
		waitTicks("list closed", 3);
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
			check("settings screen has its 6 options, the replay list button and Done", c.currentScreen.children().size() == 8);
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
			check("replay is running and shows the past (block not placed yet)", Replay.isPlayback() && Replay.getStage().getBlockState(testBlock).isAir());
			check("the real world still has the block while the replay shows the past", c.world.getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK));
			// Exactly what the vanilla Respawn button sends; here it stands for any respawn the
			// server decides on while a replay is open.
			c.player.requestRespawn();
		});
		waitUntil("respawned", c -> c.player != null && !c.player.isDead(), 20 * 20);
		waitTicks("after the respawn", 5);
		run("check the replay ended with the respawn", c -> {
			Camera camera = c.gameRenderer.getCamera();
			double distance = camera.getCameraPos().distanceTo(c.player.getEyePos());
			check("replay stopped by itself", !Replay.isActive() && Replay.getStage() == null);
			check("replay screen closed by itself", c.currentScreen == null);
			check("the death is over: no still any more", Recorder.getFrozen() == null);
			check("the recording is kept for watching after the respawn", Recorder.getLast() != null && Replay.isAvailable(c));
			check("camera is back at the living player's eyes", distance < 0.5 && !camera.isThirdPerson());
			check("the real world was never changed: block is placed", c.world.getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK));
			int leftovers = 0;
			for (Entity entity : c.world.getEntities()) {
				if (entity.getId() < 0) {
					leftovers++;
				}
			}

			check("no puppet survived the respawn", leftovers == 0 && Replay.puppetCount() == 0);
		});
		waitTicks("settle", 20);
		screenshot("after_respawn_during_replay");

		// ---- watching the replay after respawning, alive, standing somewhere else
		Vec3d[] playerPos = new Vec3d[1];
		startPacketAudit("the replay after respawning");
		run("open the replay after respawning", c -> {
			c.inGameHud.getChatHud().clear(false);
			playerPos[0] = c.player.getEntityPos();
			LOGGER.info("[SelfTest] alive at {}, death was at {}", playerPos[0], Recorder.getLast().deathPos());
			check("the player is alive", !c.player.isDead());
			check("replay opened after respawning", Replay.open(c));
		});
		waitTicks("replay after respawn runs", 12);
		run("check the replay after respawning", c -> {
			check("replay screen is open", c.currentScreen instanceof ReplayScreen);
			check("playback runs on its stage", Replay.isPlayback() && Replay.getStage() != null);
			check("the stage shows the past: block not placed yet", Replay.getStage().getBlockState(testBlock).isAir());
			check("the real world shows the present: block is placed", c.world.getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK));
			check("the player has a puppet on the stage", Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			check("the real player is still alive and in the real world", !c.player.isDead() && c.player.getEntityWorld() == c.world);
			double camToDeath = c.gameRenderer.getCamera().getCameraPos().distanceTo(Recorder.getLast().playerAt(Replay.getTick()).pos());
			LOGGER.info("[SelfTest] replay camera is {} blocks from the recorded player", String.format("%.2f", camToDeath));
			check("the camera is at the recorded scene", camToDeath < 12.0);
		});
		screenshot("replay_after_respawn");
		waitUntil("replay after respawn reaches the end", () -> Replay.isAtEnd() && !Replay.isPlaying(), 200);
		waitTicks("end", 3);
		screenshot("replay_after_respawn_end");
		run("close the replay after respawning", c -> c.currentScreen.close());
		waitTicks("closed", 5);
		run("check the game is back to normal", c -> {
			Camera camera = c.gameRenderer.getCamera();
			check("back in the game, no screen", c.currentScreen == null);
			check("replay is over and the stage is gone", !Replay.isActive() && Replay.getStage() == null);
			check("camera is back at the player's eyes, first person", camera.getCameraPos().distanceTo(c.player.getEyePos()) < 0.5 && !camera.isThirdPerson());
			// The real world keeps running underneath: the slimes of this flat world may shove the
			// real player a little while the replay is watched. That is the game, not the mod.
			double moved = c.player.getEntityPos().distanceTo(playerPos[0]);
			LOGGER.info("[SelfTest] the real player is {} blocks from where it stood when the replay was opened", String.format("%.2f", moved));
			check("the real player stayed where it was (apart from being pushed by mobs)", moved < 4.0);
			check("the real world shows the present: block is placed", c.world.getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK));
			check("the recording can be watched again", Replay.isAvailable(c));
		});
		endPacketAudit("the replay after respawning", true);
		waitTicks("real world on screen again", 20);
		screenshot("back_in_game_after_replay");
		run("remove the test block", c -> command(c, "setblock 3 -60 8 minecraft:air"));
		waitTicks("settle", 5);
	}

	/** The replay screen's own controls, driven through the same handlers real mouse input goes through. */
	private void replayControlsScript() {
		Click[] lastClick = new Click[1];

		run("click the timeline at one quarter", c -> {
			ReplayScreen screen = (ReplayScreen) c.currentScreen;
			Replay.setPlaying(true);
			int barWidth = Math.min(300, screen.width - 40);
			int barLeft = screen.width / 2 - barWidth / 2;
			lastClick[0] = new Click(barLeft + barWidth * 0.25, 25.0, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
			check("a click on the timeline is taken", screen.mouseClicked(lastClick[0], false));
		});
		waitTicks("seek applied", 2);
		run("check the click seeked, then drag to three quarters", c -> {
			ReplayScreen screen = (ReplayScreen) c.currentScreen;
			int last = Replay.getRecording().tickCount() - 1;
			LOGGER.info("[SelfTest] timeline click at 25% -> tick {} of {}", Replay.getTick(), last);
			check("clicking the timeline jumps there", Math.abs(Replay.getTick() - last * 0.25) <= 2.0);
			check("playback holds while the timeline is held", !Replay.isPlaying());
			int barWidth = Math.min(300, screen.width - 40);
			int barLeft = screen.width / 2 - barWidth / 2;
			Click drag = new Click(barLeft + barWidth * 0.75, 40.0, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
			check("dragging the timeline is taken", screen.mouseDragged(drag, drag.x() - lastClick[0].x(), 15.0));
			lastClick[0] = drag;
		});
		waitTicks("drag applied", 2);
		run("check the drag seeked, then let go", c -> {
			ReplayScreen screen = (ReplayScreen) c.currentScreen;
			int last = Replay.getRecording().tickCount() - 1;
			LOGGER.info("[SelfTest] timeline drag to 75% -> tick {} of {}", Replay.getTick(), last);
			check("dragging the timeline follows the mouse", Math.abs(Replay.getTick() - last * 0.75) <= 2.0);
			check("the stage follows the drag (block removed again by then)", Replay.getStage().getBlockState(new BlockPos(3, -60, 8)).isAir());
			screen.mouseReleased(lastClick[0]);
			check("playback resumes when the timeline is let go", Replay.isPlaying());
		});
		screenshot("replay_after_timeline_drag");

		run("click the Pause button", c -> {
			ReplayScreen screen = (ReplayScreen) c.currentScreen;
			check("the Pause button is hit", screen.mouseClicked(buttonClick(screen, 1), false));
			check("the Pause button pauses", !Replay.isPlaying());
		});
		waitTicks("paused by button", 5);
		run("click the view button", c -> {
			ReplayScreen screen = (ReplayScreen) c.currentScreen;
			ReplayView before = Replay.getView();
			check("the view button is hit", screen.mouseClicked(buttonClick(screen, 2), false));
			check("the view button switches the view", Replay.getView() == before.next());
			Replay.setView(ReplayView.THIRD_PERSON);
		});
		run("hold the right mouse button to look", c -> {
			ReplayScreen screen = (ReplayScreen) c.currentScreen;
			Click right = new Click(screen.width / 2.0, screen.height / 2.0, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0));
			screen.mouseClicked(right, false);
			// The mouse can only be grabbed while the window has focus, which an unattended run may not have.
			// The self-test keeps its hands off the real mouse, so only the handler path is exercised.
			screen.mouseReleased(right);
			check("releasing the right button frees the mouse", !Replay.isLooking());
		});
		screenshot("replay_paused_by_button");
	}

	/** A left click in the middle of the n-th button of the replay screen's bottom row. */
	private static Click buttonClick(ReplayScreen screen, int index) {
		int count = 5;
		int gap = 4;
		int buttonWidth = Math.min(78, (screen.width - 16 - gap * (count - 1)) / count);
		int x = (screen.width - (buttonWidth * count + gap * (count - 1))) / 2 + index * (buttonWidth + gap) + buttonWidth / 2;
		int y = screen.height - 20 - 6 + 10;
		return new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
	}

	/** Feature (c): the replay. Runs on the death screen, after the recorder checks. */
	private void replayScript() {
		BlockPos testBlock = new BlockPos(3, -60, 8);

		// The still appears once the server has removed the live entities (20 ticks after death).
		waitUntil("the frozen last frame is shown", Replay::isStill, 60);
		run("open replay", c -> {
			// Chat lines would cover the lower half of every screenshot.
			c.inGameHud.getChatHud().clear(false);
			check("replay is available on the death screen", Replay.isAvailable(c) && Recorder.getFrozen() == Recorder.getLast());
			LOGGER.info("[SelfTest] still: {} puppets", Replay.puppetCount());
			check("the frozen last frame is shown on the death screen", Replay.isStill() && Replay.puppetCount() >= 3);
			check("replay opened", Replay.open(c));
		});
		waitTicks("replay starts", 2);
		run("check replay start", c -> {
			check("replay screen is open", c.currentScreen instanceof ReplayScreen);
			check("replay is playing back", Replay.isPlayback() && !Replay.isStill());
			check("replay starts in third person", Replay.getView() == ReplayView.THIRD_PERSON);
			LOGGER.info("[SelfTest] replay: {} puppets at tick {}", Replay.puppetCount(), Replay.getTick());
			check("puppets were created", Replay.puppetCount() >= 3);
			check("the player has a puppet", Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			ClientWorld stage = Replay.getStage();
			check("the replay plays on a stage of its own, not in the game's world", stage != null && stage != c.world);
			check("the stage was rewound: test block not placed yet", stage.getBlockState(testBlock).isAir());
			check("the stage has the recorded terrain", stage.getBlockState(new BlockPos(2, -60, 3)).isOf(Blocks.GOLD_BLOCK));
			int onStage = 0;
			int strangers = 0;
			for (Entity entity : stage.getEntities()) {
				if (Replay.isPuppet(entity)) {
					onStage++;
				} else {
					strangers++;
				}
			}

			int puppetsInRealWorld = 0;
			for (Entity entity : c.world.getEntities()) {
				if (entity.getId() < 0) {
					puppetsInRealWorld++;
				}
			}

			check("the stage holds the puppets and nothing else", onStage == Replay.puppetCount() && strangers == 0);
			check("no puppet is in the real world during playback", puppetsInRealWorld == 0);
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
			check("test block is on the stage while the real world is untouched",
				Replay.getStage().getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK) && c.world.getBlockState(testBlock).isAir()));
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
			check("test block is gone again during the replay", Replay.getStage().getBlockState(testBlock).isAir()));

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
			check("the stage was rewound by the seek", Replay.getStage().getBlockState(testBlock).isAir());
		});
		screenshot("replay_paused_after_seek");

		run("seek into the block window", c -> Replay.seek(66));
		waitTicks("after seek", 2);
		run("check seeking forward applies block changes", c ->
			check("test block is there after seeking forward", Replay.getStage().getBlockState(testBlock).isOf(Blocks.DIAMOND_BLOCK)));

		replayControlsScript();

		run("close the replay", c -> c.currentScreen.close());
		waitTicks("replay closed", 3);
		run("check everything is put back", c -> {
			check("playback is over and the stage is gone", !Replay.isPlayback() && Replay.getStage() == null);
			check("death screen is back", c.currentScreen instanceof DeathScreen);
			check("the real world is as it was: test block is gone", c.world.getBlockState(testBlock).isAir());
			int puppets = 0;
			for (Entity entity : c.world.getEntities()) {
				if (entity.getId() < 0) {
					puppets++;
				}
			}

			check("the frozen last frame is shown again, and nothing else", Replay.isStill() && puppets == Replay.puppetCount() && puppets >= 3);
			Entity corpse = Replay.getPlayerPuppet();
			check("the still contains the player's body", corpse instanceof LivingEntity living && living.isDead());
		});
		screenshot("death_screen_after_replay");
	}

	/**
	 * Free camera inside solid rock: a hollow room in the middle of a 48-block stone cube must
	 * be visible from inside the rock, as it is for a vanilla spectator.
	 */
	private void rockCullingScript() {
		run("put the free camera inside solid stone, facing the hidden room", c -> {
			DeathView.setMode(DeathCameraMode.FREE);
			DetachedCamera camera = DeathView.getCamera();
			camera.setPos(new Vec3d(40.5, -23.5, 40.5));
			// The room's centre is 16 blocks away on every axis.
			camera.setRotation(-45.0F, -35.0F);
		});
		waitTicks("sections build", 60);
		run("check the room is drawn from inside the rock", c -> {
			boolean inRock = c.world.getBlockState(BlockPos.ofFloored(DeathView.getCamera().getPos())).isOpaqueFullCube();
			int drawn = c.worldRenderer.getCompletedChunkCount();
			LOGGER.info("[SelfTest] camera inside solid stone: {}; chunk sections drawn: {}", inRock, drawn);
			check("the camera is inside solid stone", inRock);
			check("looking out of solid rock still draws the world (more than 8 sections)", drawn > 8);
		});
		screenshot("free_camera_inside_rock");
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

		rockCullingScript();

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

	// ---------------------------------------------------------------- packet audit

	/** Packets a vanilla client sends on its own, dead or alive, without the player doing anything. */
	private static final Set<String> IDLE_PACKETS = Set.of("ClientTickEndC2SPacket", "KeepAliveC2SPacket", "CommonPongC2SPacket");

	/** Starts counting what the client sends to the server. */
	private void startPacketAudit(String what) {
		run("start counting outgoing packets: " + what, c -> PacketAudit.start());
	}

	/**
	 * Stops counting and fails if anything was sent that an idle vanilla client would not send.
	 * {@code alive}: a living player's client also reports its position.
	 */
	private void endPacketAudit(String what, boolean alive) {
		run("check outgoing packets: " + what, c -> {
			Map<String, Integer> sent = PacketAudit.stop();
			LOGGER.info("[SelfTest] packets sent to the server during {}: {}", what, sent);
			List<String> unexpected = new ArrayList<>();
			for (String type : sent.keySet()) {
				boolean vanillaIdle = IDLE_PACKETS.contains(type) || alive && (type.startsWith("PlayerMoveC2SPacket") || type.equals("PlayerInputC2SPacket"));
				if (!vanillaIdle) {
					unexpected.add(type);
				}
			}

			check("nothing but vanilla's idle packets was sent during " + what + (unexpected.isEmpty() ? "" : ", but found " + unexpected), unexpected.isEmpty());
		});
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

	/** Like {@link #waitUntil}, but running out of time is not a failure: the script just goes on. */
	void waitUntilSoft(String name, Predicate<MinecraftClient> condition, int maxTicks) {
		int[] waited = {0};
		this.steps.add(new Step("wait up to " + maxTicks + " ticks for " + name, c -> condition.test(c) || ++waited[0] >= maxTicks, maxTicks + 5));
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
