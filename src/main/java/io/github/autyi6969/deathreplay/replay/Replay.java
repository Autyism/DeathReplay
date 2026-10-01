package io.github.autyi6969.deathreplay.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.autyi6969.deathreplay.DeathReplayClient;
import io.github.autyi6969.deathreplay.camera.CameraInput;
import io.github.autyi6969.deathreplay.camera.DetachedCamera;
import io.github.autyi6969.deathreplay.config.DeathReplayConfig;
import io.github.autyi6969.deathreplay.death.DeathView;
import io.github.autyi6969.deathreplay.record.EntitySample;
import io.github.autyi6969.deathreplay.record.Frame;
import io.github.autyi6969.deathreplay.record.RecordedEvent;
import io.github.autyi6969.deathreplay.record.Recorder;
import io.github.autyi6969.deathreplay.record.Recording;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a frozen {@link Recording}: as playback, or as a still.
 *
 * <p><b>Playback</b> re-enacts the recording on a stage of its own: a client-side copy of the
 * terrain around the death ({@link ReplayWorld}) that is handed to the renderers for as long
 * as the replay screen is open. On that stage
 * <ul>
 * <li>blocks that changed during the recording are set back, then changed again in step;</li>
 * <li>recorded entities are acted out by client-only {@link Puppet}s;</li>
 * <li>recorded particle / sound / effect packets are shown again at their tick.</li>
 * </ul>
 * The game's real world is not touched: it keeps ticking, the real player stays where it is,
 * and everything the server sends is applied as usual. Leaving the replay simply hands the
 * renderers back to the real world. Because the stage is a copy, playback works on the death
 * screen as well as after respawning. It is a recording of the past in both cases; nothing
 * is sent to the server and nothing live is shown of the place of death.
 *
 * <p>The <b>still</b> is the last recorded frame shown as frozen puppets in the real world on
 * the death screen. One second after a death the server stops sending the dead player any
 * entities, so without it the death screen camera would look at an empty world. It exists
 * only while the player is dead and ends with the respawn.
 */
public final class Replay {
	/** Puppet entity ids count down from here; server-assigned ids are positive. */
	private static final int PUPPET_ID_BASE = -1_000_000;
	/** Set without neighbour updates or shape reactions: the recorded state is put back exactly. */
	private static final int BLOCK_FLAGS = Block.NOTIFY_LISTENERS | Block.REDRAW_ON_MAIN_THREAD | Block.FORCE_STATE;
	public static final double MIN_DISTANCE = 1.5;
	public static final double MAX_DISTANCE = 20.0;
	public static final double MIN_FLY_SPEED = 2.0;
	public static final double MAX_FLY_SPEED = 40.0;
	private static final double DEFAULT_DISTANCE = 5.0;
	private static final double DEFAULT_FLY_SPEED = 10.0;
	private static final double SPRINT_MULTIPLIER = 2.5;
	private static final int SEEK_TICKS = 2 * Recording.TICKS_PER_SECOND;
	private static final int NO_SEEK = -1;

	private static final DetachedCamera CAMERA = new DetachedCamera();
	private static final CameraInput INPUT = new CameraInput();
	private static final CameraInput.State INPUT_STATE = new CameraInput.State();
	/** Extra input injected by the self-test, which cannot press real keys. */
	private static final CameraInput.State SYNTHETIC_INPUT = new CameraInput.State();
	private static final Int2ObjectMap<Puppet> PUPPETS = new Int2ObjectOpenHashMap<>();
	private static final Set<Entity> PUPPET_ENTITIES = new ReferenceOpenHashSet<>();
	private static final List<BlockBreak> BLOCK_BREAKS = new ArrayList<>();

	@Nullable
	private static Recording recording;
	/** The world the puppets are in: the stage during playback, the real world for the still. */
	@Nullable
	private static ClientWorld world;
	/** The stage; non-null exactly while playback is running. */
	@Nullable
	private static ClientWorld stage;
	/** The game's real world and player when this replay started; if either changes, it ends. */
	@Nullable
	private static ClientWorld boundWorld;
	@Nullable
	private static ClientPlayerEntity boundPlayer;
	/** Index of the frame the puppets were last given. */
	private static int tick;
	private static boolean playing;
	/** True while only the frozen last frame is shown and the death screen camera is in charge. */
	private static boolean still;
	/** Index of the last frame whose block changes are currently applied to the stage. */
	private static int blocksAppliedThrough;
	private static int nextPuppetId;
	/** A jump asked for by dragging the timeline; carried out once per tick. */
	private static int pendingSeek = NO_SEEK;
	private static ReplayView view = ReplayView.THIRD_PERSON;
	private static double distance = DEFAULT_DISTANCE;
	private static double flySpeed = DEFAULT_FLY_SPEED;
	private static Vec3d lastTarget = Vec3d.ZERO;
	private static boolean controlling;
	private static boolean stopRequested;
	private static long lastFrameNanos;

	private record BlockBreak(int entityId, BlockPos pos) {
	}

	private Replay() {
	}

	// ---------------------------------------------------------------- queries

	/** True during playback and while the still is shown. */
	public static boolean isActive() {
		return recording != null;
	}

	/** True while a replay is being played back (or is paused) on the replay screen. */
	public static boolean isPlayback() {
		return recording != null && !still;
	}

	/** True while the frozen last frame is shown on the death screen. */
	public static boolean isStill() {
		return recording != null && still;
	}

	/**
	 * Whether the latest recording can be played right now. True on the death screen and
	 * after respawning alike, for as long as the player stays on the server.
	 */
	public static boolean isAvailable(MinecraftClient client) {
		return Recorder.getLast() != null && canPlay(client);
	}

	/** Whether any recording could be played: the stage needs a live connection to build on. */
	public static boolean canPlay(MinecraftClient client) {
		return client.world != null && client.player != null && client.getNetworkHandler() != null;
	}

	/**
	 * The world the renderers should treat as "the world" for lighting, fog and camera while
	 * playback runs, or {@code null} when the game's own world is on screen.
	 */
	@Nullable
	public static ClientWorld getStage() {
		return stage;
	}

	public static boolean isPuppet(Entity entity) {
		return PUPPET_ENTITIES.contains(entity);
	}

	public static int puppetCount() {
		return PUPPETS.size();
	}

	@Nullable
	public static Recording getRecording() {
		return recording;
	}

	public static int getTick() {
		return pendingSeek != NO_SEEK ? pendingSeek : tick;
	}

	public static boolean isPlaying() {
		return playing;
	}

	public static boolean isAtEnd() {
		return recording != null && tick >= recording.tickCount() - 1;
	}

	public static ReplayView getView() {
		return view;
	}

	public static DetachedCamera getCamera() {
		return CAMERA;
	}

	public static CameraInput.State getSyntheticInput() {
		return SYNTHETIC_INPUT;
	}

	/** The puppet acting out the recorded player, if it exists at the current tick. */
	@Nullable
	public static Entity getPlayerPuppet() {
		if (recording == null) {
			return null;
		}

		Puppet puppet = PUPPETS.get(recording.playerEntityId());
		return puppet == null ? null : puppet.entity;
	}

	/**
	 * Entities that must not be drawn: during the still everything real (it would stand in the
	 * frozen scene), in first-person playback the player's own puppet (the camera is inside it).
	 */
	public static boolean hidesEntity(Entity entity) {
		if (!isActive()) {
			return false;
		}

		if (still) {
			return !isPuppet(entity);
		}

		return view == ReplayView.FIRST_PERSON && entity == getPlayerPuppet();
	}

	// ---------------------------------------------------------------- start / stop

	/** Plays the latest recording. {@code client.currentScreen} is returned to afterwards. */
	public static boolean open(MinecraftClient client) {
		Recording latest = Recorder.getLast();
		return latest != null && open(client, latest);
	}

	/** Plays {@code toPlay} on the replay screen. Returns false if that is not possible now. */
	public static boolean open(MinecraftClient client, Recording toPlay) {
		if (isPlayback() || !canPlay(client) || toPlay.frames().isEmpty()) {
			return false;
		}

		Screen parent = client.currentScreen;
		if (isStill()) {
			stop(client);
		}

		ClientWorld newStage;
		try {
			long start = System.nanoTime();
			newStage = ReplayWorld.create(client, toPlay);
			DeathReplayClient.LOGGER.info("Built the replay stage: {} chunks in {} ms", toPlay.snapshot().chunks().size(),
				String.format("%.1f", (System.nanoTime() - start) / 1.0e6));
		} catch (RuntimeException e) {
			DeathReplayClient.LOGGER.error("Could not build the replay stage", e);
			return false;
		}

		bind(client, toPlay, newStage);
		stage = newStage;
		attachRenderers(client, newStage);
		still = false;
		view = DeathReplayConfig.get().replayView;
		distance = DEFAULT_DISTANCE;
		flySpeed = DEFAULT_FLY_SPEED;
		lastFrameNanos = 0L;

		// The stage shows the end of the recording; take it back to the start.
		blocksAppliedThrough = toPlay.tickCount() - 1;
		jumpTo(0);
		playing = true;

		EntitySample player = toPlay.playerAt(0);
		lastTarget = player != null ? player.pos().add(0.0, player.eyeHeight, 0.0) : toPlay.deathPos();
		CAMERA.setRotation(player != null ? player.yaw : 0.0F, 20.0F);
		CAMERA.orbit(newStage, client.player, lastTarget, distance);
		DeathReplayClient.LOGGER.info("Replay started: {} ticks, {} puppets", toPlay.tickCount(), PUPPETS.size());

		client.setScreen(new ReplayScreen(parent));
		return true;
	}

	/**
	 * Shows the frozen last frame on the death screen. Called automatically once the recording
	 * of the current death is ready, the death screen camera is in use, and the server has
	 * taken the live entities away.
	 */
	public static boolean showStill(MinecraftClient client) {
		Recording frozen = Recorder.getFrozen();
		if (isActive() || frozen == null || frozen.frames().isEmpty() || !canPlay(client)) {
			return false;
		}

		bind(client, frozen, client.world);
		still = true;
		tick = frozen.tickCount() - 1;
		syncPuppets(frozen.frames().get(tick));
		DeathReplayClient.LOGGER.info("Showing the last recorded frame on the death screen: {} puppets", PUPPETS.size());
		return true;
	}

	private static void bind(MinecraftClient client, Recording toShow, ClientWorld puppetWorld) {
		recording = toShow;
		world = puppetWorld;
		boundWorld = client.world;
		boundPlayer = client.player;
		playing = false;
		stopRequested = false;
		pendingSeek = NO_SEEK;
		nextPuppetId = 0;
		tick = toShow.tickCount() - 1;
		BLOCK_BREAKS.clear();
	}

	/**
	 * Called when the replay screen goes away: playback ends, the real world is back on screen,
	 * and the still is shown again if the player is still on the death screen.
	 */
	static void endPlayback(MinecraftClient client) {
		stop(client);
		if (shouldShowStill(client)) {
			showStill(client);
		}
	}

	/**
	 * The still replaces a view that has gone empty; it must never replace a live one. On a
	 * vanilla server every entity is taken away from a dead player one second after the death.
	 * A server that keeps sending the scene keeps its live view, and no still is shown.
	 */
	private static boolean shouldShowStill(MinecraftClient client) {
		Recording frozen = Recorder.getFrozen();
		return frozen != null
			&& DeathView.isActive()
			&& canPlay(client)
			&& client.player.isDead()
			&& client.world.getRegistryKey() == frozen.dimension()
			&& !hasLiveEntitiesNearby(client);
	}

	private static boolean hasLiveEntitiesNearby(MinecraftClient client) {
		double maxDistanceSquared = Recorder.CAPTURE_RADIUS * Recorder.CAPTURE_RADIUS;
		for (Entity entity : client.world.getEntities()) {
			if (entity != client.player && !entity.isRemoved() && !isPuppet(entity) && entity.squaredDistanceTo(client.player) <= maxDistanceSquared) {
				return true;
			}
		}

		return false;
	}

	/** Ends playback or the still. After this the game shows its real world, unchanged. */
	public static void stop(MinecraftClient client) {
		if (!isActive()) {
			return;
		}

		if (stage != null) {
			// Cracks drawn on blocks live in the shared world renderer, not in the stage.
			for (BlockBreak blockBreak : BLOCK_BREAKS) {
				stage.setBlockBreakingInfo(blockBreak.entityId(), blockBreak.pos(), -1);
			}

			stage = null;
			// Hand the renderers back to whatever the real world is now (possibly none).
			attachRenderers(client, client.world);
		} else if (world != null && client.world == world) {
			removeAllPuppets(world);
		}

		recording = null;
		world = null;
		boundWorld = null;
		boundPlayer = null;
		playing = false;
		still = false;
		controlling = false;
		stopRequested = false;
		pendingSeek = NO_SEEK;
		PUPPETS.clear();
		PUPPET_ENTITIES.clear();
		BLOCK_BREAKS.clear();
		INPUT.releaseMouse(client);
		DeathReplayClient.LOGGER.info("Replay stopped");
	}

	/** Points everything that draws the world at {@code target}. The game's own world field is not changed. */
	private static void attachRenderers(MinecraftClient client, @Nullable ClientWorld target) {
		client.worldRenderer.setWorld(target);
		client.particleManager.setWorld(target);
		client.gameRenderer.setWorld(target);
	}

	/**
	 * A replay belongs to the world and player it was started with. A respawn, a dimension
	 * change or a disconnect ends it; the still additionally needs the player to be dead.
	 */
	private static boolean isStillValid(MinecraftClient client) {
		return client.world == boundWorld
			&& client.player == boundPlayer
			&& boundPlayer != null
			&& (!still || boundPlayer.isDead());
	}

	// ---------------------------------------------------------------- tick

	/** Called at the end of every client tick. */
	public static void tick(MinecraftClient client) {
		if (!isActive()) {
			if (shouldShowStill(client)) {
				showStill(client);
			}

			return;
		}

		if (stopRequested || !isStillValid(client)) {
			// Respawned, kicked, changed dimension: the replay is over, no questions asked.
			stop(client);
			return;
		}

		if (still) {
			if (!DeathView.isActive()) {
				// The death screen camera was switched off in the settings.
				stop(client);
				return;
			}

			for (Puppet puppet : PUPPETS.values()) {
				puppet.hold();
			}

			return;
		}

		// The stage is not the game's world, so nobody else ticks its entities.
		stage.tickEntities();

		if (pendingSeek != NO_SEEK) {
			jumpTo(pendingSeek);
			pendingSeek = NO_SEEK;
		}

		if (playing) {
			if (isAtEnd()) {
				// The last frame's own blocks and effects have not been shown yet.
				setBlocksThrough(world, tick);
				playEvents(recording.frames().get(tick));
				playing = false;
			} else {
				step();
			}
		}
	}

	/** Advances the replay by one tick, with effects. */
	private static void step() {
		List<Frame> frames = recording.frames();
		tick++;
		// Interpolated puppets reach a sample one tick after they are given it, so everything
		// else (blocks, effects, non-interpolated entities) runs one frame behind to match.
		int shown = tick - 1;
		setBlocksThrough(world, shown);
		playEvents(frames.get(shown));
		syncPuppets(frames.get(tick));
	}

	/** Jumps straight to {@code target} without playing the effects in between. */
	private static void jumpTo(int target) {
		target = MathHelper.clamp(target, 0, recording.tickCount() - 1);
		removeAllPuppets(world);
		tick = target;
		setBlocksThrough(world, target - 1);
		syncPuppets(recording.frames().get(target));
	}

	// ---------------------------------------------------------------- blocks

	/** Applies or undoes recorded block changes until exactly frames 0..{@code target} are applied. */
	private static void setBlocksThrough(ClientWorld targetWorld, int target) {
		List<Frame> frames = recording.frames();
		while (blocksAppliedThrough > target) {
			RecordedEvent[] events = frames.get(blocksAppliedThrough).events();
			for (int i = events.length - 1; i >= 0; i--) {
				if (events[i] instanceof RecordedEvent.BlockChange change) {
					targetWorld.setBlockState(change.pos(), change.oldState(), BLOCK_FLAGS, 0);
				}
			}

			blocksAppliedThrough--;
		}

		while (blocksAppliedThrough < target) {
			blocksAppliedThrough++;
			for (RecordedEvent event : frames.get(blocksAppliedThrough).events()) {
				if (event instanceof RecordedEvent.BlockChange change) {
					targetWorld.setBlockState(change.pos(), change.newState(), BLOCK_FLAGS, 0);
				}
			}
		}
	}

	// ---------------------------------------------------------------- puppets

	private static void syncPuppets(Frame frame) {
		IntSet present = new IntOpenHashSet();
		for (EntitySample sample : frame.entities()) {
			present.add(sample.entityId);
			Puppet puppet = PUPPETS.get(sample.entityId);
			if (puppet == null) {
				puppet = Puppet.create(world, sample, PUPPET_ID_BASE - nextPuppetId++);
				if (puppet == null) {
					continue;
				}

				PUPPETS.put(sample.entityId, puppet);
				PUPPET_ENTITIES.add(puppet.entity);
			} else {
				puppet.update(sample);
			}
		}

		ObjectIterator<Int2ObjectMap.Entry<Puppet>> iterator = PUPPETS.int2ObjectEntrySet().iterator();
		while (iterator.hasNext()) {
			Int2ObjectMap.Entry<Puppet> entry = iterator.next();
			if (!present.contains(entry.getIntKey())) {
				removePuppet(world, entry.getValue());
				iterator.remove();
			}
		}

		// Riding, once every puppet of this frame exists.
		for (EntitySample sample : frame.entities()) {
			Puppet puppet = PUPPETS.get(sample.entityId);
			if (puppet == null) {
				continue;
			}

			Puppet vehicle = sample.vehicleId == EntitySample.NO_VEHICLE ? null : PUPPETS.get(sample.vehicleId);
			Entity current = puppet.entity.getVehicle();
			if (vehicle == null) {
				if (current != null) {
					puppet.entity.stopRiding();
				}
			} else if (current != vehicle.entity) {
				puppet.entity.startRiding(vehicle.entity, true, false);
			}
		}
	}

	private static void removePuppet(ClientWorld puppetWorld, Puppet puppet) {
		PUPPET_ENTITIES.remove(puppet.entity);
		puppetWorld.removeEntity(puppet.entity.getId(), Entity.RemovalReason.DISCARDED);
	}

	private static void removeAllPuppets(ClientWorld puppetWorld) {
		for (Puppet puppet : PUPPETS.values()) {
			puppetWorld.removeEntity(puppet.entity.getId(), Entity.RemovalReason.DISCARDED);
		}

		PUPPETS.clear();
		PUPPET_ENTITIES.clear();
	}

	@Nullable
	private static Entity puppetEntity(int recordedId) {
		Puppet puppet = PUPPETS.get(recordedId);
		return puppet == null ? null : puppet.entity;
	}

	// ---------------------------------------------------------------- effects

	private static void playEvents(Frame frame) {
		MinecraftClient client = MinecraftClient.getInstance();
		for (RecordedEvent event : frame.events()) {
			try {
				playEvent(client, event);
			} catch (RuntimeException e) {
				// A replay must never be able to take the game down.
				DeathReplayClient.LOGGER.debug("Skipped a replay event", e);
			}
		}
	}

	private static void playEvent(MinecraftClient client, RecordedEvent event) {
		switch (event) {
			case RecordedEvent.BlockChange e -> {
				// Applied by setBlocksThrough.
			}
			case RecordedEvent.Particle e -> playParticles(e.packet());
			case RecordedEvent.Sound e -> {
				PlaySoundS2CPacket packet = e.packet();
				world.playSound(client.player, packet.getX(), packet.getY(), packet.getZ(), packet.getSound(), packet.getCategory(),
					packet.getVolume(), packet.getPitch(), packet.getSeed());
			}
			case RecordedEvent.EntitySound e -> {
				PlaySoundFromEntityS2CPacket packet = e.packet();
				Entity entity = puppetEntity(packet.getEntityId());
				if (entity != null) {
					world.playSoundFromEntity(client.player, entity, packet.getSound(), packet.getCategory(), packet.getVolume(), packet.getPitch(), packet.getSeed());
				}
			}
			case RecordedEvent.WorldEvent e -> {
				WorldEventS2CPacket packet = e.packet();
				if (packet.isGlobal()) {
					world.syncGlobalEvent(packet.getEventId(), packet.getPos(), packet.getData());
				} else {
					world.syncWorldEvent(packet.getEventId(), packet.getPos(), packet.getData());
				}
			}
			case RecordedEvent.Explosion e -> {
				ExplosionS2CPacket packet = e.packet();
				Vec3d center = packet.center();
				world.playSoundClient(center.x, center.y, center.z, packet.explosionSound().value(), SoundCategory.BLOCKS, 4.0F,
					(1.0F + (world.random.nextFloat() - world.random.nextFloat()) * 0.2F) * 0.7F, false);
				world.addParticleClient(packet.explosionParticle(), center.x, center.y, center.z, 1.0, 0.0, 0.0);
				world.addBlockParticleEffects(center, packet.radius(), packet.blockCount(), packet.blockParticles());
			}
			case RecordedEvent.EntityStatus e -> {
				Entity entity = puppetEntity(e.entityId());
				if (entity == null) {
					return;
				}

				switch (e.status()) {
					case 35 -> {
						// Totem of undying; vanilla handles this status outside the entity.
						client.particleManager.addEmitter(entity, ParticleTypes.TOTEM_OF_UNDYING, 30);
						world.playSoundClient(entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ITEM_TOTEM_USE, entity.getSoundCategory(), 1.0F, 1.0F, false);
					}
					case 21, 63 -> {
						// Looping guardian / sniffer sounds: not worth leaving a sound loop behind.
					}
					default -> entity.handleStatus(e.status());
				}
			}
			case RecordedEvent.EntityAnimation e -> {
				Entity entity = puppetEntity(e.entityId());
				if (entity != null) {
					client.particleManager.addEmitter(entity, e.animationId() == EntityAnimationS2CPacket.CRIT ? ParticleTypes.CRIT : ParticleTypes.ENCHANTED_HIT);
				}
			}
			case RecordedEvent.EntityDamage e -> {
				Entity entity = puppetEntity(e.packet().entityId());
				if (entity != null) {
					entity.onDamaged(e.packet().createDamageSource(world));
				}
			}
			case RecordedEvent.BlockBreaking e -> {
				world.setBlockBreakingInfo(e.packet().getEntityId(), e.packet().getPos(), e.packet().getProgress());
				BLOCK_BREAKS.add(new BlockBreak(e.packet().getEntityId(), e.packet().getPos()));
			}
		}
	}

	/** Same as the vanilla particle packet handler. */
	private static void playParticles(ParticleS2CPacket packet) {
		if (packet.getCount() == 0) {
			world.addParticleClient(packet.getParameters(), packet.shouldForceSpawn(), packet.isImportant(), packet.getX(), packet.getY(), packet.getZ(),
				packet.getSpeed() * packet.getOffsetX(), packet.getSpeed() * packet.getOffsetY(), packet.getSpeed() * packet.getOffsetZ());
			return;
		}

		for (int i = 0; i < packet.getCount(); i++) {
			double x = world.random.nextGaussian() * packet.getOffsetX();
			double y = world.random.nextGaussian() * packet.getOffsetY();
			double z = world.random.nextGaussian() * packet.getOffsetZ();
			double velocityX = world.random.nextGaussian() * packet.getSpeed();
			double velocityY = world.random.nextGaussian() * packet.getSpeed();
			double velocityZ = world.random.nextGaussian() * packet.getSpeed();
			world.addParticleClient(packet.getParameters(), packet.shouldForceSpawn(), packet.isImportant(),
				packet.getX() + x, packet.getY() + y, packet.getZ() + z, velocityX, velocityY, velocityZ);
		}
	}

	// ---------------------------------------------------------------- per-frame camera

	/**
	 * Advances the replay camera by one rendered frame. Returns {@code null} as soon as the
	 * replay is no longer valid, so the vanilla camera takes over in that very frame.
	 */
	@Nullable
	public static DetachedCamera frame(float tickProgress) {
		if (!isActive()) {
			return null;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (stopRequested || !isStillValid(client)) {
			// The world must not be modified in the middle of a frame; clean up on the next tick.
			stopRequested = true;
			return null;
		}

		if (still) {
			return null;
		}

		long now = System.nanoTime();
		double seconds = lastFrameNanos == 0L ? 0.0 : MathHelper.clamp((now - lastFrameNanos) / 1.0e9, 0.0, 0.1);
		lastFrameNanos = now;

		CameraInput.State input = INPUT_STATE;
		if (controlling) {
			INPUT.poll(client, input);
		} else {
			input.forward = input.strafe = input.up = 0.0;
			input.sprint = false;
			input.yawDelta = input.pitchDelta = 0.0F;
		}

		float yawDelta = input.yawDelta + SYNTHETIC_INPUT.yawDelta * (float) seconds;
		float pitchDelta = input.pitchDelta + SYNTHETIC_INPUT.pitchDelta * (float) seconds;
		Entity player = getPlayerPuppet();
		if (player != null) {
			lastTarget = player.getLerpedPos(tickProgress).add(0.0, player.getStandingEyeHeight(), 0.0);
		}

		switch (view) {
			case FIRST_PERSON -> {
				CAMERA.setPos(lastTarget);
				if (player != null) {
					CAMERA.setRotation(player.getYaw(tickProgress), player.getPitch(tickProgress));
				}
			}
			case THIRD_PERSON -> {
				CAMERA.rotate(yawDelta, pitchDelta);
				CAMERA.orbit(world, player != null ? player : boundPlayer, lastTarget, distance);
			}
			case FREE -> {
				CAMERA.rotate(yawDelta, pitchDelta);
				double forward = MathHelper.clamp(input.forward + SYNTHETIC_INPUT.forward, -1.0, 1.0);
				double strafe = MathHelper.clamp(input.strafe + SYNTHETIC_INPUT.strafe, -1.0, 1.0);
				double up = MathHelper.clamp(input.up + SYNTHETIC_INPUT.up, -1.0, 1.0);
				double speed = flySpeed * (input.sprint || SYNTHETIC_INPUT.sprint ? SPRINT_MULTIPLIER : 1.0);
				CAMERA.fly(forward, strafe, up, speed * seconds);
			}
		}

		return CAMERA;
	}

	// ---------------------------------------------------------------- controls

	public static void setPlaying(boolean value) {
		if (!isPlayback()) {
			return;
		}

		if (value && isAtEnd()) {
			// "Play" at the end means "again".
			jumpTo(0);
		}

		playing = value;
	}

	public static void togglePlaying() {
		setPlaying(!playing);
	}

	public static void restart() {
		seek(0);
	}

	/** Jumps to a tick, keeping the play / pause state. */
	public static void seek(int target) {
		if (isPlayback()) {
			pendingSeek = NO_SEEK;
			jumpTo(target);
		}
	}

	/**
	 * Like {@link #seek}, but carried out on the next tick. For dragging the timeline, where
	 * many requests arrive per tick and only the last one matters.
	 */
	public static void requestSeek(int target) {
		if (isPlayback()) {
			pendingSeek = MathHelper.clamp(target, 0, recording.tickCount() - 1);
		}
	}

	public static void seekRelative(int direction) {
		seek(getTick() + direction * SEEK_TICKS);
	}

	public static void setView(ReplayView newView) {
		view = newView;
	}

	public static void cycleView() {
		setView(view.next());
	}

	/** Mouse wheel: leash length in third person, flight speed in free view. */
	public static void scroll(double amount) {
		if (view == ReplayView.FREE) {
			flySpeed = MathHelper.clamp(flySpeed * Math.pow(1.2, amount), MIN_FLY_SPEED, MAX_FLY_SPEED);
		} else if (view == ReplayView.THIRD_PERSON) {
			distance = MathHelper.clamp(distance * Math.pow(0.85, amount), MIN_DISTANCE, MAX_DISTANCE);
		}
	}

	/** Movement keys steer the camera while the replay screen is open. */
	static void setControlling(MinecraftClient client, boolean value) {
		controlling = value && isPlayback();
		if (!controlling) {
			INPUT.releaseMouse(client);
		}
	}

	/** The mouse turns the camera only while it is held for that (right button on the replay screen). */
	static void setLooking(MinecraftClient client, boolean looking) {
		if (looking && controlling) {
			INPUT.grabMouse(client);
		} else {
			INPUT.releaseMouse(client);
		}
	}

	public static boolean isLooking() {
		return INPUT.isMouseGrabbed();
	}
}
