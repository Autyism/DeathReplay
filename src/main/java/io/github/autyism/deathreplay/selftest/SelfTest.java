package io.github.autyism.deathreplay.selftest;

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

import io.github.autyism.deathreplay.camera.CameraInput;
import io.github.autyism.deathreplay.camera.DeathCameraMode;
import io.github.autyism.deathreplay.camera.DetachedCamera;
import io.github.autyism.deathreplay.config.DeathReplayConfig;
import io.github.autyism.deathreplay.config.SettingsScreen;
import io.github.autyism.deathreplay.death.DeathScreenButtons;
import io.github.autyism.deathreplay.death.DeathSpectateScreen;
import io.github.autyism.deathreplay.death.DeathView;
import io.github.autyism.deathreplay.record.EntitySample;
import io.github.autyism.deathreplay.record.Frame;
import io.github.autyism.deathreplay.record.RecordedEvent;
import io.github.autyism.deathreplay.record.Recorder;
import io.github.autyism.deathreplay.record.Recording;
import io.github.autyism.deathreplay.record.ReplayFileReader;
import io.github.autyism.deathreplay.record.ReplayFileWriter;
import io.github.autyism.deathreplay.replay.PuppetPlayerEntity;
import io.github.autyism.deathreplay.replay.Replay;
import io.github.autyism.deathreplay.replay.ReplayBrowserScreen;
import io.github.autyism.deathreplay.replay.ReplayScreen;
import io.github.autyism.deathreplay.replay.ReplayView;
import io.github.autyism.deathreplay.waypoint.WaypointHud;
import io.github.autyism.deathreplay.waypoint.WaypointListScreen;
import io.github.autyism.deathreplay.waypoint.Waypoints;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
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
		waitUntil("main menu", c -> c.getOverlay() == null && c.screen != null && c.level == null, 20 * 120);
		run("prepare client", c -> {
			// The window is usually unfocused during an unattended run; do not let that pause the game.
			c.options.pauseOnLostFocus = false;
			// Somebody may be using the computer while this runs; their mouse must not steer the test.
			CameraInput.ignoreRealInput = true;
			// The "Move with WASD" tutorial toast would cover a corner of every screenshot.
			c.getTutorial().setStep(TutorialSteps.NONE);
			// In memory only (never saved): a short buffer so the test can fill it, and auto-save on.
			this.realBufferSeconds = DeathReplayConfig.get().bufferSeconds;
			this.realAutoSave = DeathReplayConfig.get().autoSave;
			DeathReplayConfig.get().bufferSeconds = TEST_BUFFER_SECONDS;
			DeathReplayConfig.get().autoSave = true;
			deleteOldScreenshots(c);
			this.replayFilesBefore = Set.copyOf(ReplayBrowserScreen.listFiles(ReplayFileWriter.directory(c)));
		});
		run("open world", this::openWorld);
		waitUntil("in world", c -> c.level != null && c.player != null && c.getOverlay() == null, 20 * 120);
		waitTicks("settle", 60);
		run("respawn if the previous run left the player dead", c -> {
			if (c.player.isDeadOrDying()) {
				c.player.respawn();
			}
		});
		waitUntil("alive", c -> c.player != null && !c.player.isDeadOrDying() && !(c.screen instanceof DeathScreen), 20 * 20);
		afterRestartScript();
		run("set up scene", c -> {
			if (c.screen != null) {
				c.setScreen(null);
			}
			command(c, "time set noon");
			command(c, "weather clear");
			// A run that stopped in the middle of the immediate-respawn scenario would leave the rule on.
			command(c, "gamerule immediate_respawn false");
			command(c, "tp @s 0.5 -60 0.5 0 10");
			// Landmarks, so the screenshots show where the camera is looking from.
			command(c, "fill 2 -60 3 2 -58 3 minecraft:gold_block");
			command(c, "fill -3 -60 4 -2 -60 4 minecraft:stone");
			Waypoints.clear(c);
			// A 48-block cube of solid stone with a lit room in its middle, for the "free camera
			// inside rock" check. Built once; it stays in the self-test world.
			if (!c.level.getBlockState(new BlockPos(33, -31, 33)).is(Blocks.STONE) || !c.level.getBlockState(new BlockPos(55, -12, 55)).is(Blocks.GLOWSTONE)) {
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
		run("walk forward", c -> c.options.keyUp.setDown(true));
		waitTicks("walking", 30);
		run("stop walking", c -> c.options.keyUp.setDown(false));
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
		waitUntil("death screen", c -> c.screen instanceof DeathScreen, 20 * 10);
		waitTicks("death screen t10", 10);
		screenshot("death_screen_t10");
		waitTicks("death screen t40", 30);

		recorderScript();
		startPacketAudit("the death screen, the replay and looking around");
		replayScript();
		deathCameraScript();
		endPacketAudit("the death screen, the replay and looking around", false);

		run("respawn", c -> c.player.respawn());
		waitUntil("respawned", c -> c.player != null && !c.player.isDeadOrDying() && !(c.screen instanceof DeathScreen), 20 * 20);
		waitTicks("after respawn", 30);
		run("check camera is back on the player", c -> {
			Camera camera = c.gameRenderer.getMainCamera();
			double distance = camera.position().distanceTo(c.player.getEyePosition());
			LOGGER.info("[SelfTest] after respawn: camera is {} blocks from the player's eyes", String.format("%.2f", distance));
			check("death view ended on respawn", !DeathView.isActive());
			int puppets = 0;
			for (Entity entity : c.level.entitiesForRendering()) {
				if (entity.getId() < 0) {
					puppets++;
				}
			}

			check("the still ended on respawn and left nothing behind", !Replay.isActive() && puppets == 0 && Replay.puppetCount() == 0);
			check("camera is back at the player's eyes", distance < 0.5);
			check("camera is first person again", !camera.isDetached());
			check("perspective setting untouched", c.options.getCameraType() == CameraType.FIRST_PERSON);
		});
		screenshot("after_respawn");

		run("turn towards the marker", c -> {
			List<Waypoints.Marker> markers = Waypoints.current(c);
			check("the marker survived the respawn", markers.size() == 1);
			if (!markers.isEmpty()) {
				// The client decides where the player looks; no command needed.
				c.player.lookAt(EntityAnchorArgument.Anchor.EYES, markers.getFirst().center());
			}
		});
		waitTicks("facing the marker", 10);
		run("check the marker in normal play", c -> {
			c.gui.getChat().clearMessages(false);
			List<Waypoints.Marker> markers = Waypoints.current(c);
			if (!markers.isEmpty()) {
				int width = c.getWindow().getGuiScaledWidth();
				int height = c.getWindow().getGuiScaledHeight();
				Vec3 onScreen = WaypointHud.screenPos(c, markers.getFirst(), width, height);
				LOGGER.info("[SelfTest] marker label at {} on a {}x{} screen", onScreen, width, height);
				check("looking at the marker puts its label in the middle of the screen", onScreen != null
					&& Math.abs(onScreen.x - width / 2.0) < width * 0.06 && Math.abs(onScreen.y - height / 2.0) < height * 0.08);
			}

			check("the marker is on the locator bar after respawning", c.getConnection().getWaypointManager().hasWaypoints());
		});
		waitTicks("label drawn", 3);
		screenshot("marker_after_respawn");
		run("open the marker list", c -> c.setScreen(new WaypointListScreen(null)));
		waitTicks("marker list", 5);
		screenshot("marker_list");
		run("delete the marker", c -> {
			check("the marker list is open with a delete button", c.screen instanceof WaypointListScreen && c.screen.children().size() == 5);
			Waypoints.current(c).forEach(marker -> Waypoints.remove(c, marker));
			c.setScreen(null);
		});
		waitTicks("marker gone", 3);
		run("check the marker is gone", c ->
			check("deleting removes the marker and its locator bar dot", Waypoints.current(c).isEmpty() && !c.getConnection().getWaypointManager().hasWaypoints()));

		respawnDuringReplayScript();
		farTravelScript();
		combatScript();
		savedFileScript();
		immediateRespawnScript();
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
					types.add(BuiltInRegistries.ENTITY_TYPE.getKey(sample.appearance.type()).getPath());
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
				&& stand.appearance.equipment()[EquipmentSlot.HEAD.ordinal()].is(Items.DIAMOND_HELMET));
			check("spawn packet was kept for the armor stand", stand != null && stand.appearance.spawnPacket() != null);
			LOGGER.info("[SelfTest] terrain snapshot: {} chunks, radius {}", recording.snapshot().chunks().size(), recording.snapshot().radius());
			check("the terrain around the death was copied", recording.snapshot().chunks().size() >= 9);
		});

		waitUntil("recording saved to disk", () -> ReplayFileWriter.getLastSavedFile() != null, 100);
		run("check the saved file", c -> {
			Path file = ReplayFileWriter.getLastSavedFile();
			try {
				CompoundTag nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
				LOGGER.info("[SelfTest] saved file {} ({} bytes): {} ticks, {} entity tracks, {} block changes, {} events",
					file, Files.size(file), nbt.getIntOr("TickCount", -1), nbt.getListOrEmpty("Entities").size(),
					nbt.getListOrEmpty("BlockChanges").size(), nbt.getListOrEmpty("Events").size());
				check("file is in the deathreplay folder", file.getParent().equals(c.gameDirectory.toPath().resolve("deathreplay")));
				check("file has the same tick count", nbt.getIntOr("TickCount", -1) == Recorder.getFrozen().tickCount());
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
		waitUntilSoft("the zombie kills the player", c -> c.screen instanceof DeathScreen, 20 * 25);
		run("make sure the player is dead", c -> {
			killedByZombie[0] = c.screen instanceof DeathScreen;
			if (!killedByZombie[0]) {
				LOGGER.info("[SelfTest] NOTE the zombie did not kill the player in 25 s; using /kill instead (not a failure)");
				command(c, "kill @s");
			}
		});
		waitUntil("combat death screen", c -> c.screen instanceof DeathScreen, 20 * 10);
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

			c.gui.getChat().clearMessages(false);
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
		run("close the combat replay", c -> c.screen.onClose());
		waitTicks("closed", 3);
		screenshot("combat_death_screen_still");
		run("respawn after the fight", c -> c.player.respawn());
		waitUntil("respawned after the fight", c -> c.player != null && !c.player.isDeadOrDying() && !(c.screen instanceof DeathScreen), 20 * 20);
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
				c.player.connection.sendCommand("summon minecraft:pig " + (6 + i % 15) + " -60 " + (12 + i / 15));
			}
		});
		waitTicks("pigs spawn", 40);
		run("measure the recorder: crowded scene", c -> {
			c.gui.getChat().clearMessages(false);
			Recorder.resetCaptureStats();
		});
		waitTicks("recording a crowded scene", 100);
		run("check the recorder stays cheap", c -> {
			double average = Recorder.averageCaptureMicros();
			LOGGER.info("[SelfTest] recorder cost with {} entities nearby: average {} us per tick, worst tick {} us (a tick is 50000 us; fps now {})",
				countEntities(c), String.format("%.1f", average), String.format("%.1f", Recorder.maxCaptureMicros()), c.getFps());
			check("recording a crowded scene costs less than 1 ms per tick (2% of a tick)", average < 1000.0);
			c.player.connection.sendCommand("kill @e[type=minecraft:pig]");
			c.player.connection.sendCommand("kill @e[type=minecraft:item]");
		});
		waitTicks("pigs gone", 20);
	}

	private static int countEntities(Minecraft client) {
		int count = 0;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity.distanceToSqr(client.player) <= Recorder.CAPTURE_RADIUS * Recorder.CAPTURE_RADIUS) {
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
			c.gui.getChat().clearMessages(false);
			ReplayBrowserScreen browser = new ReplayBrowserScreen(null);
			c.setScreen(browser);
			browser.play(file);
		});
		waitTicks("replay from the previous run", 15);
		run("check the replay saved before the restart", c -> {
			if (!present[0]) {
				return;
			}

			check("a replay saved before the restart plays", c.screen instanceof ReplayScreen && Replay.isPlayback() && Replay.getStage() != null);
			LOGGER.info("[SelfTest] replay from before the restart: {} ticks, {} puppets", Replay.getRecording() == null ? 0 : Replay.getRecording().tickCount(), Replay.puppetCount());
			check("its actors are on stage", Replay.puppetCount() >= 2 && Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			Replay.seek(Replay.getRecording().deathFrame() - 6);
			Replay.setPlaying(false);
		});
		waitTicks("paused before the death", 5);
		screenshot("replay_saved_before_restart");
		run("close the replay from the previous run", c -> {
			if (present[0]) {
				c.screen.onClose();
			}
		});
		waitTicks("closed", 3);
		run("close the list", c -> {
			if (c.screen != null) {
				c.setScreen(null);
			}
		});
		waitTicks("back in the game", 3);
	}

	/**
	 * The replay starts where the player was at the start of the recording, which can be far
	 * from where the player died. The terrain there must be in the replay too.
	 */
	private void farTravelScript() {
		BlockPos farGround = new BlockPos(300, -61, 300);

		run("go 420 blocks away", c -> command(c, "tp @s 300.5 -60 300.5 0 10"));
		waitTicks("stand far away", 60);
		run("come back", c -> command(c, "tp @s 0.5 -60 0.5 0 10"));
		waitTicks("stand at the origin", 50);
		run("die after travelling", c -> {
			check("the far chunks are no longer loaded on the client", c.level.getChunkSource().getChunk(18, 18, false) == null);
			command(c, "kill @s");
		});
		waitUntil("death screen after travelling", c -> c.screen instanceof DeathScreen, 20 * 10);
		waitUntil("recording after travelling frozen", () -> Recorder.getFrozen() != null, 60);
		run("open the replay at a moment when the player was far away", c -> {
			c.gui.getChat().clearMessages(false);
			Recording recording = Recorder.getFrozen();
			int farTick = -1;
			for (int i = 0; i < recording.tickCount(); i++) {
				EntitySample sample = recording.playerAt(i);
				if (sample != null && sample.x > 250.0) {
					farTick = i + 10;
					break;
				}
			}

			LOGGER.info("[SelfTest] recording after travelling: {} ticks, {} chunks of terrain, player far away from tick {}",
				recording.tickCount(), recording.snapshot().chunks().size(), farTick);
			check("the recording contains the far away part", farTick >= 0);
			check("replay opened", Replay.open(c));
			Replay.seek(Math.max(0, farTick));
			Replay.setPlaying(false);
		});
		waitTicks("far scene builds", 40);
		run("check the replay has terrain where the player was", c -> {
			Entity puppet = Replay.getPlayerPuppet();
			check("the player puppet is at the far place", puppet != null && puppet.getX() > 250.0);
			check("the terrain of the far place is in the replay", Replay.getStage().getBlockState(farGround).is(Blocks.GRASS_BLOCK));
			int drawn = c.levelRenderer.countRenderedSections();
			LOGGER.info("[SelfTest] far from the death: {} chunk sections drawn", drawn);
			check("terrain is drawn there", drawn > 0);
			int covered = 0;
			for (int x = 18 - 4; x <= 18 + 4; x++) {
				for (int z = 18 - 4; z <= 18 + 4; z++) {
					if (Replay.getStage().getChunkSource().getChunk(x, z, false) != null) {
						covered++;
					}
				}
			}

			LOGGER.info("[SelfTest] far from the death: {} of the 81 chunks around the player are in the replay", covered);
			check("the surroundings of the far place are in the replay, not just a corner", covered >= 70);
		});
		screenshot("replay_start_far_from_death");
		run("close the replay", c -> c.screen.onClose());
		waitTicks("closed", 3);
		run("respawn after travelling", c -> c.player.respawn());
		waitUntil("respawned after travelling", c -> c.player != null && !c.player.isDeadOrDying() && !(c.screen instanceof DeathScreen), 20 * 20);
		waitTicks("settle", 10);
	}

	/** Saved files: what was written can be read back, listed and played. Runs while alive. */
	private void savedFileScript() {
		Recording[] loaded = new Recording[1];

		run("read the saved replay back", c -> {
			Recording original = Recorder.getLast();
			Path file = ReplayFileWriter.getLastSavedFile();
			check("the latest death was saved to a file", original != null && file != null && ReplayFileWriter.isSaved(original));
			try {
				loaded[0] = ReplayFileReader.read(file, c.level.registryAccess());
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
			check("the replay list is open", c.screen instanceof ReplayBrowserScreen);
			int saved = ReplayBrowserScreen.listFiles(ReplayFileWriter.directory(c)).size();
			LOGGER.info("[SelfTest] replay list: {} saved files, {} widgets", saved, c.screen.children().size());
			check("saved replays are listed", saved >= 1 && c.screen.children().size() >= 6);
		});
		screenshot("replay_list");
		run("play the newest saved file from the list", c -> {
			ReplayBrowserScreen browser = (ReplayBrowserScreen) c.screen;
			browser.play(ReplayBrowserScreen.listFiles(ReplayFileWriter.directory(c)).getFirst());
		});
		waitTicks("replay from file runs", 15);
		run("check the replay from the file", c -> {
			check("a replay read from a file plays", c.screen instanceof ReplayScreen && Replay.isPlayback() && Replay.getStage() != null);
			check("it is the file's recording, not the one in memory", Replay.getRecording() != null && Replay.getRecording() != Recorder.getLast());
			LOGGER.info("[SelfTest] replay from file: {} puppets at tick {}", Replay.puppetCount(), Replay.getTick());
			check("its actors are on stage", Replay.puppetCount() >= 3 && Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			check("its terrain is on stage", Replay.getStage().getBlockState(new BlockPos(2, -60, 3)).is(Blocks.GOLD_BLOCK));
			Replay.seek(Replay.getRecording().deathFrame() - 6);
			Replay.setPlaying(false);
		});
		waitTicks("paused before the death", 5);
		screenshot("replay_from_file");
		run("close the replay from the file", c -> c.screen.onClose());
		waitTicks("back in the list", 3);
		run("check the list is back, then close it", c -> {
			check("closing the replay returns to the list", c.screen instanceof ReplayBrowserScreen && !Replay.isActive());
			c.screen.onClose();
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
			Screen screen = modMenu ? ModMenuProbe.createConfigScreen(c.screen) : new SettingsScreen(c.screen);
			check("Mod Menu entry point creates the settings screen", screen instanceof SettingsScreen);
			c.setScreen(screen);
		});
		waitTicks("settings screen", 5);
		run("check settings screen", c -> {
			check("settings screen is open", c.screen instanceof SettingsScreen);
			LOGGER.info("[SelfTest] settings screen has {} widgets", c.screen.children().size());
			check("settings screen has its 6 options, the two list buttons and Done", c.screen.children().size() == 9);
		});
		screenshot("settings_screen");
		run("close settings", c -> c.screen.onClose());
		waitTicks("settings closed", 3);
		run("check settings closed", c -> check("settings screen closed back to the game", c.screen == null));

		run("open the Mod Menu mod list", c -> {
			try {
				Screen mods = (Screen) Class.forName("com.terraformersmc.modmenu.gui.ModsScreen").getConstructor(Screen.class).newInstance(c.screen);
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
	 * The "immediate respawn" game rule skips the death screen: the client respawns before the
	 * recording's death tail is complete. The death must still be kept and be watchable with F6.
	 * Auto-save is off for this death so the saved-file checks that follow are not affected.
	 */
	private void immediateRespawnScript() {
		Recording[] before = new Recording[1];
		run("immediate respawn: game rule on, auto-save off for this death", c -> {
			DeathReplayConfig.get().autoSave = false;
			command(c, "gamerule immediate_respawn true");
		});
		waitTicks("game rule applied", 20);
		run("die with immediate respawn", c -> {
			before[0] = Recorder.getLast();
			command(c, "kill @s");
		});
		waitUntil("respawned without a death screen", c -> c.player != null && !c.player.isDeadOrDying() && Recorder.getLast() != before[0], 20 * 10);
		run("check the death was kept", c -> {
			Recording kept = Recorder.getLast();
			check("immediate respawn: the death was recorded", kept != null && kept != before[0]);
			check("immediate respawn: the recording reaches the death", kept != null && kept.deathFrame() >= 0 && kept.deathFrame() < kept.tickCount());
			check("immediate respawn: no death screen was shown", !(c.screen instanceof DeathScreen));
			check("immediate respawn: the replay can be watched", Replay.isAvailable(c));
		});
		run("immediate respawn: game rule off, auto-save back on", c -> {
			command(c, "gamerule immediate_respawn false");
			DeathReplayConfig.get().autoSave = true;
		});
		waitTicks("game rule restored", 20);
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
		waitUntil("second death screen", c -> c.screen instanceof DeathScreen, 20 * 10);
		waitUntil("second recording frozen", () -> Recorder.getFrozen() != null, 60);
		run("open the replay of the second death", c -> {
			c.gui.getChat().clearMessages(false);
			check("second replay opened", Replay.open(c));
		});
		waitTicks("second replay runs", 10);
		run("respawn while the replay is running", c -> {
			check("replay is running and shows the past (block not placed yet)", Replay.isPlayback() && Replay.getStage().getBlockState(testBlock).isAir());
			check("the real world still has the block while the replay shows the past", c.level.getBlockState(testBlock).is(Blocks.DIAMOND_BLOCK));
			// Exactly what the vanilla Respawn button sends; here it stands for any respawn the
			// server decides on while a replay is open.
			c.player.respawn();
		});
		waitUntil("respawned", c -> c.player != null && !c.player.isDeadOrDying(), 20 * 20);
		waitTicks("after the respawn", 5);
		run("check the replay ended with the respawn", c -> {
			Camera camera = c.gameRenderer.getMainCamera();
			double distance = camera.position().distanceTo(c.player.getEyePosition());
			check("replay stopped by itself", !Replay.isActive() && Replay.getStage() == null);
			check("replay screen closed by itself", c.screen == null);
			check("the death is over: no still any more", Recorder.getFrozen() == null);
			check("the recording is kept for watching after the respawn", Recorder.getLast() != null && Replay.isAvailable(c));
			check("camera is back at the living player's eyes", distance < 0.5 && !camera.isDetached());
			check("the real world was never changed: block is placed", c.level.getBlockState(testBlock).is(Blocks.DIAMOND_BLOCK));
			int leftovers = 0;
			for (Entity entity : c.level.entitiesForRendering()) {
				if (entity.getId() < 0) {
					leftovers++;
				}
			}

			check("no puppet survived the respawn", leftovers == 0 && Replay.puppetCount() == 0);
		});
		waitTicks("settle", 20);
		screenshot("after_respawn_during_replay");

		// ---- watching the replay after respawning, alive, standing somewhere else
		Vec3[] playerPos = new Vec3[1];
		//? if >=26.1 {
		/*// 26.1+: the stage has clocks of its own. Night in the real world; the replay must keep the time of the death.
		run("night in the real world", c -> command(c, "time set midnight"));
		waitTicks("night falls", 10);
		*///?}
		startPacketAudit("the replay after respawning");
		run("open the replay after respawning", c -> {
			c.gui.getChat().clearMessages(false);
			playerPos[0] = c.player.position();
			LOGGER.info("[SelfTest] alive at {}, death was at {}", playerPos[0], Recorder.getLast().deathPos());
			check("the player is alive", !c.player.isDeadOrDying());
			check("replay opened after respawning", Replay.open(c));
		});
		waitTicks("replay after respawn runs", 12);
		run("check the replay after respawning", c -> {
			check("replay screen is open", c.screen instanceof ReplayScreen);
			check("playback runs on its stage", Replay.isPlayback() && Replay.getStage() != null);
			check("the stage shows the past: block not placed yet", Replay.getStage().getBlockState(testBlock).isAir());
			check("the real world shows the present: block is placed", c.level.getBlockState(testBlock).is(Blocks.DIAMOND_BLOCK));
			check("the player has a puppet on the stage", Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			check("the real player is still alive and in the real world", !c.player.isDeadOrDying() && c.player.level() == c.level);
			double camToDeath = c.gameRenderer.getMainCamera().position().distanceTo(Recorder.getLast().playerAt(Replay.getTick()).pos());
			LOGGER.info("[SelfTest] replay camera is {} blocks from the recorded player", String.format("%.2f", camToDeath));
			check("the camera is at the recorded scene", camToDeath < 12.0);
			//? if >=26.1 {
			/*long recordedTime = Recorder.getLast().snapshot().timeOfDay();
			long stageTime = Replay.getStage().getDefaultClockTime();
			long realTime = c.level.getDefaultClockTime() % 24000L;
			LOGGER.info("[SelfTest] time of day: recorded {}, on the stage {}, in the real world {}", recordedTime, stageTime, realTime);
			check("the replay keeps the time of the death while the real world is at night", stageTime == recordedTime && Math.abs(realTime - 18000L) < 400L);
			*///?}
		});
		screenshot("replay_after_respawn");
		waitUntil("replay after respawn reaches the end", () -> Replay.isAtEnd() && !Replay.isPlaying(), 200);
		waitTicks("end", 3);
		screenshot("replay_after_respawn_end");
		run("close the replay after respawning", c -> c.screen.onClose());
		waitTicks("closed", 5);
		run("check the game is back to normal", c -> {
			Camera camera = c.gameRenderer.getMainCamera();
			check("back in the game, no screen", c.screen == null);
			check("replay is over and the stage is gone", !Replay.isActive() && Replay.getStage() == null);
			check("camera is back at the player's eyes, first person", camera.position().distanceTo(c.player.getEyePosition()) < 0.5 && !camera.isDetached());
			// The real world keeps running underneath: the slimes of this flat world may shove the
			// real player a little while the replay is watched. That is the game, not the mod.
			double moved = c.player.position().distanceTo(playerPos[0]);
			LOGGER.info("[SelfTest] the real player is {} blocks from where it stood when the replay was opened", String.format("%.2f", moved));
			check("the real player stayed where it was (apart from being pushed by mobs)", moved < 4.0);
			check("the real world shows the present: block is placed", c.level.getBlockState(testBlock).is(Blocks.DIAMOND_BLOCK));
			check("the recording can be watched again", Replay.isAvailable(c));
		});
		endPacketAudit("the replay after respawning", true);
		//? if >=26.1 {
		/*run("noon again in the real world", c -> command(c, "time set noon"));
		waitTicks("noon", 5);
		run("clear the time message", c -> c.gui.getChat().clearMessages(false));
		*///?}
		waitTicks("real world on screen again", 20);
		screenshot("back_in_game_after_replay");
		run("remove the test block", c -> command(c, "setblock 3 -60 8 minecraft:air"));
		waitTicks("settle", 5);
	}

	/** The replay screen's own controls, driven through the same handlers real mouse input goes through. */
	private void replayControlsScript() {
		MouseButtonEvent[] lastClick = new MouseButtonEvent[1];

		run("click the timeline at one quarter", c -> {
			ReplayScreen screen = (ReplayScreen) c.screen;
			Replay.setPlaying(true);
			int barWidth = Math.min(300, screen.width - 40);
			int barLeft = screen.width / 2 - barWidth / 2;
			lastClick[0] = new MouseButtonEvent(barLeft + barWidth * 0.25, 25.0, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
			check("a click on the timeline is taken", screen.mouseClicked(lastClick[0], false));
		});
		waitTicks("seek applied", 2);
		run("check the click seeked, then drag to three quarters", c -> {
			ReplayScreen screen = (ReplayScreen) c.screen;
			int last = Replay.getRecording().tickCount() - 1;
			LOGGER.info("[SelfTest] timeline click at 25% -> tick {} of {}", Replay.getTick(), last);
			check("clicking the timeline jumps there", Math.abs(Replay.getTick() - last * 0.25) <= 2.0);
			check("playback holds while the timeline is held", !Replay.isPlaying());
			int barWidth = Math.min(300, screen.width - 40);
			int barLeft = screen.width / 2 - barWidth / 2;
			MouseButtonEvent drag = new MouseButtonEvent(barLeft + barWidth * 0.75, 40.0, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
			check("dragging the timeline is taken", screen.mouseDragged(drag, drag.x() - lastClick[0].x(), 15.0));
			lastClick[0] = drag;
		});
		waitTicks("drag applied", 2);
		run("check the drag seeked, then let go", c -> {
			ReplayScreen screen = (ReplayScreen) c.screen;
			int last = Replay.getRecording().tickCount() - 1;
			LOGGER.info("[SelfTest] timeline drag to 75% -> tick {} of {}", Replay.getTick(), last);
			check("dragging the timeline follows the mouse", Math.abs(Replay.getTick() - last * 0.75) <= 2.0);
			check("the stage follows the drag (block removed again by then)", Replay.getStage().getBlockState(new BlockPos(3, -60, 8)).isAir());
			screen.mouseReleased(lastClick[0]);
			check("playback resumes when the timeline is let go", Replay.isPlaying());
		});
		screenshot("replay_after_timeline_drag");

		run("click the Pause button", c -> {
			ReplayScreen screen = (ReplayScreen) c.screen;
			check("the Pause button is hit", screen.mouseClicked(buttonClick(screen, 1), false));
			check("the Pause button pauses", !Replay.isPlaying());
		});
		waitTicks("paused by button", 5);
		run("click the view button", c -> {
			ReplayScreen screen = (ReplayScreen) c.screen;
			ReplayView before = Replay.getView();
			check("the view button is hit", screen.mouseClicked(buttonClick(screen, 2), false));
			check("the view button switches the view", Replay.getView() == before.next());
			Replay.setView(ReplayView.THIRD_PERSON);
		});
		run("hold the right mouse button to look", c -> {
			ReplayScreen screen = (ReplayScreen) c.screen;
			MouseButtonEvent right = new MouseButtonEvent(screen.width / 2.0, screen.height / 2.0, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0));
			int markersBefore = Waypoints.current(c).size();
			screen.mouseClicked(right, false);
			// The self-test keeps its hands off the real mouse, so only the handler path is exercised.
			screen.mouseReleased(right);
			check("releasing the right button frees the mouse", !Replay.isLooking());
			// Pressed and released at once, without turning: that is a click, and a click marks the spot.
			List<Waypoints.Marker> markers = Waypoints.current(c);
			check("a short right click in the replay marks the spot under the cursor", markers.size() == markersBefore + 1);
			if (markers.size() > markersBefore) {
				Waypoints.Marker marker = markers.getLast();
				LOGGER.info("[SelfTest] replay marker '{}' at {}, {}, {}", marker.name, marker.x, marker.y, marker.z);
				Vec3 onScreen = WaypointHud.screenPos(c, marker, screen.width, screen.height);
				check("the replay marker is where the cursor was (the middle of the screen)", onScreen != null
					&& Math.abs(onScreen.x - screen.width / 2.0) < screen.width * 0.06 && Math.abs(onScreen.y - screen.height / 2.0) < screen.height * 0.08);
				Waypoints.remove(c, marker);
			}
		});
		screenshot("replay_paused_by_button");
	}

	/** A left click in the middle of the n-th button of the replay screen's bottom row. */
	private static MouseButtonEvent buttonClick(ReplayScreen screen, int index) {
		int count = 5;
		int gap = 4;
		int buttonWidth = Math.min(78, (screen.width - 16 - gap * (count - 1)) / count);
		int x = (screen.width - (buttonWidth * count + gap * (count - 1))) / 2 + index * (buttonWidth + gap) + buttonWidth / 2;
		int y = screen.height - 20 - 6 + 10;
		return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
	}

	/** Feature (c): the replay. Runs on the death screen, after the recorder checks. */
	private void replayScript() {
		BlockPos testBlock = new BlockPos(3, -60, 8);

		// The still appears once the server has removed the live entities (20 ticks after death).
		waitUntil("the frozen last frame is shown", Replay::isStill, 60);
		run("open replay", c -> {
			// Chat lines would cover the lower half of every screenshot.
			c.gui.getChat().clearMessages(false);
			check("replay is available on the death screen", Replay.isAvailable(c) && Recorder.getFrozen() == Recorder.getLast());
			LOGGER.info("[SelfTest] still: {} puppets", Replay.puppetCount());
			check("the frozen last frame is shown on the death screen", Replay.isStill() && Replay.puppetCount() >= 3);
			check("replay opened", Replay.open(c));
		});
		waitTicks("replay starts", 2);
		run("check replay start", c -> {
			check("replay screen is open", c.screen instanceof ReplayScreen);
			check("replay is playing back", Replay.isPlayback() && !Replay.isStill());
			check("replay starts in third person", Replay.getView() == ReplayView.THIRD_PERSON);
			LOGGER.info("[SelfTest] replay: {} puppets at tick {}", Replay.puppetCount(), Replay.getTick());
			check("puppets were created", Replay.puppetCount() >= 3);
			check("the player has a puppet", Replay.getPlayerPuppet() instanceof PuppetPlayerEntity);
			ClientLevel stage = Replay.getStage();
			check("the replay plays on a stage of its own, not in the game's world", stage != null && stage != c.level);
			check("the stage was rewound: test block not placed yet", stage.getBlockState(testBlock).isAir());
			check("the stage has the recorded terrain", stage.getBlockState(new BlockPos(2, -60, 3)).is(Blocks.GOLD_BLOCK));
			int onStage = 0;
			int strangers = 0;
			for (Entity entity : stage.entitiesForRendering()) {
				if (Replay.isPuppet(entity)) {
					onStage++;
				} else {
					strangers++;
				}
			}

			int puppetsInRealWorld = 0;
			for (Entity entity : c.level.entitiesForRendering()) {
				if (entity.getId() < 0) {
					puppetsInRealWorld++;
				}
			}

			check("the stage holds the puppets and nothing else", onStage == Replay.puppetCount() && strangers == 0);
			check("no puppet is in the real world during playback", puppetsInRealWorld == 0);
		});

		Vec3[] puppetStart = new Vec3[1];
		waitUntil("replay tick 22 (walk has begun)", () -> Replay.getTick() >= 22, 100);
		run("remember where the player puppet is", c -> puppetStart[0] = Replay.getPlayerPuppet().position());
		waitUntil("replay tick 36 (mid walk)", () -> Replay.getTick() >= 36, 100);
		run("check the player puppet walks", c -> {
			double moved = Replay.getPlayerPuppet().position().distanceTo(puppetStart[0]);
			LOGGER.info("[SelfTest] player puppet moved {} blocks in 14 ticks", String.format("%.2f", moved));
			check("the player puppet walks", moved > 1.5);
		});
		screenshot("replay_third_person_walking");

		waitUntil("replay tick 70 (block placed)", () -> Replay.getTick() >= 70, 100);
		run("check the block change is replayed", c ->
			check("test block is on the stage while the real world is untouched",
				Replay.getStage().getBlockState(testBlock).is(Blocks.DIAMOND_BLOCK) && c.level.getBlockState(testBlock).isAir()));
		screenshot("replay_third_person_block_placed");

		run("first person", c -> Replay.setView(ReplayView.FIRST_PERSON));
		waitTicks("first person", 3);
		run("check first person camera", c -> {
			Entity puppet = Replay.getPlayerPuppet();
			Vec3 eyes = puppet.position().add(0.0, puppet.getEyeHeight(), 0.0);
			double distance = c.gameRenderer.getMainCamera().position().distanceTo(eyes);
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
			double distance = camera.getPos().distanceTo(Replay.getPlayerPuppet().position());
			LOGGER.info("[SelfTest] replay free camera is {} blocks from the player puppet", String.format("%.2f", distance));
			check("free view flew away from the player", distance > 5.0);
		});
		waitTicks("free view", 3);
		screenshot("replay_free");

		waitUntil("replay reaches the end", () -> Replay.isAtEnd() && !Replay.isPlaying(), 100);
		run("check the end of the replay", c -> {
			Entity puppet = Replay.getPlayerPuppet();
			check("the player puppet is dead at the end", puppet instanceof LivingEntity living && living.isDeadOrDying() && living.deathTime > 0);
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
			check("test block is there after seeking forward", Replay.getStage().getBlockState(testBlock).is(Blocks.DIAMOND_BLOCK)));

		replayControlsScript();

		run("close the replay", c -> c.screen.onClose());
		waitTicks("replay closed", 3);
		run("check everything is put back", c -> {
			check("playback is over and the stage is gone", !Replay.isPlayback() && Replay.getStage() == null);
			check("death screen is back", c.screen instanceof DeathScreen);
			check("the real world is as it was: test block is gone", c.level.getBlockState(testBlock).isAir());
			int puppets = 0;
			for (Entity entity : c.level.entitiesForRendering()) {
				if (entity.getId() < 0) {
					puppets++;
				}
			}

			check("the frozen last frame is shown again, and nothing else", Replay.isStill() && puppets == Replay.puppetCount() && puppets >= 3);
			Entity corpse = Replay.getPlayerPuppet();
			check("the still contains the player's body", corpse instanceof LivingEntity living && living.isDeadOrDying());
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
			camera.setPos(new Vec3(40.5, -23.5, 40.5));
			// The room's centre is 16 blocks away on every axis.
			camera.setRotation(-45.0F, -35.0F);
		});
		waitTicks("sections build", 60);
		run("check the room is drawn from inside the rock", c -> {
			boolean inRock = c.level.getBlockState(BlockPos.containing(DeathView.getCamera().getPos())).isSolidRender();
			int drawn = c.levelRenderer.countRenderedSections();
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
			Camera camera = c.gameRenderer.getMainCamera();
			double distance = camera.position().distanceTo(c.player.getEyePosition());
			LOGGER.info("[SelfTest] death screen: camera at {} is {} blocks from the corpse's eyes", camera.position(), String.format("%.2f", distance));
			check("death view active", DeathView.isActive());
			check("camera left the corpse (more than 2 blocks away)", distance > 2.0);
			check("camera is third person", camera.isDetached());
		});
		screenshot("death_screen_third_person");

		run("open spectate screen", DeathView::openSpectate);
		waitTicks("spectate screen", 10);
		run("check spectate screen", c -> check("spectate screen is open", c.screen instanceof DeathSpectateScreen));
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
			Vec3 corpse = c.player.position();
			LOGGER.info("[SelfTest] free camera at {}, corpse at {}", camera.getPos(), corpse);
			check("free camera flew away (more than 8 blocks)", camera.getPos().distanceTo(corpse) > 8.0);
			check("free camera rose (more than 5 blocks up)", camera.getPos().y > corpse.y + 5.0);
		});
		waitTicks("free view", 5);
		screenshot("spectate_free");

		run("right click in the free camera to mark the spot", c -> {
			Screen screen = c.screen;
			int before = Waypoints.current(c).size();
			check("the right click is taken", screen.mouseClicked(new MouseButtonEvent(screen.width / 2.0, screen.height / 2.0, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0)), false));
			List<Waypoints.Marker> markers = Waypoints.current(c);
			check("a marker was created", markers.size() == before + 1);
			if (!markers.isEmpty()) {
				Waypoints.Marker marker = markers.getLast();
				LOGGER.info("[SelfTest] marker '{}' at {}, {}, {} in {}", marker.name, marker.x, marker.y, marker.z, marker.dimension);
				check("the marker sits on the ground the camera looked at", marker.y == -60 && marker.dimension.equals("minecraft:overworld"));
				Vec3 onScreen = WaypointHud.screenPos(c, marker, screen.width, screen.height);
				check("its label is drawn where the crosshair is", onScreen != null
					&& Math.abs(onScreen.x - screen.width / 2.0) < screen.width * 0.06 && Math.abs(onScreen.y - screen.height / 2.0) < screen.height * 0.08);
			}
		});
		waitTicks("marker shown", 3);
		run("check the marker is on the locator bar", c ->
			check("the marker was added to the client's locator bar", c.getConnection().getWaypointManager().hasWaypoints()));
		screenshot("marker_from_free_camera");

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

		run("leave spectate screen", c -> c.screen.onClose());
		waitTicks("back on death screen", 5);
		run("check death screen is back", c -> check("death screen is back", c.screen instanceof DeathScreen));
		screenshot("back_on_death_screen");

		Screen[] deathScreen = new Screen[1];
		run("open the game menu from the death screen", c -> {
			deathScreen[0] = c.screen;
			DeathScreenButtons.openGameMenu(c);
		});
		waitTicks("game menu", 5);
		run("check the game menu", c -> check("the game menu opens on the death screen", c.screen instanceof PauseScreen));
		screenshot("game_menu_on_death_screen");
		run("close the game menu", c -> c.screen.onClose());
		waitTicks("game menu closed", 5);
		run("check the same death screen is back", c ->
			check("closing the game menu returns to the same death screen", c.screen == deathScreen[0] && c.screen instanceof DeathScreen));
	}

	private void openWorld(Minecraft client) {
		if (client.getLevelSource().levelExists(WORLD_NAME)) {
			LOGGER.info("[SelfTest] loading existing world '{}'", WORLD_NAME);
			client.createWorldOpenFlows().openWorld(WORLD_NAME, () -> LOGGER.error("[SelfTest] FAIL world load was cancelled"));
		} else {
			LOGGER.info("[SelfTest] creating flat creative world '{}'", WORLD_NAME);
			//? if >=26.1 {
			/*LevelSettings levelInfo = new LevelSettings(
				WORLD_NAME,
				GameType.CREATIVE,
				new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
				true,
				WorldDataConfiguration.DEFAULT
			);
			*///?} else {
			LevelSettings levelInfo = new LevelSettings(
				WORLD_NAME,
				GameType.CREATIVE,
				false,
				Difficulty.NORMAL,
				true,
				new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()),
				WorldDataConfiguration.DEFAULT
			);
			//?}
			Screen parent = client.screen;
			//? if >=26.2 {
			/*// The game's own flat-world helper now makes the other dimensions flat too; this is the plain flat preset.
			client.createWorldOpenFlows().createFreshLevel(WORLD_NAME, levelInfo, WorldOptions.testWorldWithRandomSeed(),
				registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), parent);
			*///?} else {
			client.createWorldOpenFlows()
				.createFreshLevel(WORLD_NAME, levelInfo, WorldOptions.testWorldWithRandomSeed(), WorldPresets::createFlatWorldDimensions, parent);
			//?}
		}
	}

	// ---------------------------------------------------------------- engine

	private void tick(Minecraft client) {
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
					+ (client.screen == null ? "none" : client.screen.getClass().getSimpleName()) + ")");
				// Nothing after a timed-out wait can be trusted; stop here.
				finish(client);
			}
		} catch (Throwable t) {
			LOGGER.error("[SelfTest] exception in step '{}'", step.name, t);
			fail("step '" + step.name + "' threw " + t);
			finish(client);
		}
	}

	private void finish(Minecraft client) {
		this.finished = true;
		LOGGER.info("[SelfTest] RESULT {} ({} failure(s), {} screenshot(s))", this.failures == 0 ? "PASS" : "FAIL", this.failures, this.screenshotIndex);
		LOGGER.info("[SelfTest] DONE");
		client.stop();
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
	private static final Set<String> IDLE_PACKETS = Set.of("ServerboundClientTickEndPacket", "ServerboundKeepAlivePacket", "ServerboundPongPacket");

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
				boolean vanillaIdle = IDLE_PACKETS.contains(type) || alive && (type.startsWith("ServerboundMovePlayerPacket") || type.equals("ServerboundPlayerInputPacket"));
				if (!vanillaIdle) {
					unexpected.add(type);
				}
			}

			check("nothing but vanilla's idle packets was sent during " + what + (unexpected.isEmpty() ? "" : ", but found " + unexpected), unexpected.isEmpty());
		});
	}

	// ---------------------------------------------------------------- step helpers

	void run(String name, java.util.function.Consumer<Minecraft> action) {
		this.steps.add(new Step(name, c -> {
			action.accept(c);
			return true;
		}, 0));
	}

	void waitTicks(String name, int ticks) {
		int[] remaining = {ticks};
		this.steps.add(new Step("wait " + ticks + " ticks (" + name + ")", c -> --remaining[0] <= 0, ticks + 5));
	}

	void waitUntil(String name, Predicate<Minecraft> condition, int timeoutTicks) {
		this.steps.add(new Step("wait for " + name, condition, timeoutTicks));
	}

	/** Like {@link #waitUntil}, but running out of time is not a failure: the script just goes on. */
	void waitUntilSoft(String name, Predicate<Minecraft> condition, int maxTicks) {
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
			Screenshot.grab(c.gameDirectory, fileName, c.getMainRenderTarget(), 1,
				message -> LOGGER.info("[SelfTest] screenshot saved: {}", fileName));
		});
	}

	static void command(Minecraft client, String command) {
		LOGGER.info("[SelfTest] command: /{}", command);
		client.player.connection.sendCommand(command);
	}

	private static void deleteOldScreenshots(Minecraft client) {
		File[] old = new File(client.gameDirectory, Screenshot.SCREENSHOT_DIR)
			.listFiles((dir, name) -> name.startsWith(SCREENSHOT_PREFIX) && name.endsWith(".png"));
		if (old != null) {
			for (File file : old) {
				file.delete();
			}
		}
	}

	private record Step(String name, Predicate<Minecraft> body, int timeoutTicks) {
	}
}
