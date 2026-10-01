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
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
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
 * Plays a frozen {@link Recording} back inside the client's own copy of the world.
 *
 * <p>The replay is a local re-enactment of data the client already received before the death:
 * <ul>
 * <li>blocks that changed during the recording are set back, then changed again in step;</li>
 * <li>recorded entities are acted out by client-only {@link Puppet}s;</li>
 * <li>recorded particle / sound / effect packets are shown again at their tick.</li>
 * </ul>
 * Nothing is sent to the server and nothing from the server is delayed or dropped: block
 * updates that arrive while a replay runs are queued and applied the moment it ends. A replay
 * is only possible while the player is still dead on the death screen, and it stops the
 * instant the player respawns or the world changes.
 *
 * <p>Besides playback there is the <em>still</em>: the last recorded frame shown as a frozen
 * scene on the death screen. One second after a death the server stops sending the dead
 * player any entities, so without it the death screen camera would look at an empty world.
 * The still is a photo of the past, not a live view; it ends with the respawn like playback.
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

	private static final DetachedCamera CAMERA = new DetachedCamera();
	private static final CameraInput INPUT = new CameraInput();
	private static final CameraInput.State INPUT_STATE = new CameraInput.State();
	/** Extra input injected by the self-test, which cannot press real keys. */
	private static final CameraInput.State SYNTHETIC_INPUT = new CameraInput.State();
	private static final Int2ObjectMap<Puppet> PUPPETS = new Int2ObjectOpenHashMap<>();
	private static final Set<Entity> PUPPET_ENTITIES = new ReferenceOpenHashSet<>();
	private static final List<DeferredBlockUpdate> DEFERRED_BLOCK_UPDATES = new ArrayList<>();
	private static final List<BlockBreak> BLOCK_BREAKS = new ArrayList<>();

	@Nullable
	private static Recording recording;
	@Nullable
	private static ClientWorld world;
	@Nullable
	private static ClientPlayerEntity deadPlayer;
	/** Index of the frame the puppets were last given. */
	private static int tick;
	private static boolean playing;
	/** True while only the frozen last frame is shown and the death screen camera is in charge. */
	private static boolean still;
	/** Index of the last frame whose block changes are currently applied to the world. */
	private static int blocksAppliedThrough;
	private static int nextPuppetId;
	private static ReplayView view = ReplayView.THIRD_PERSON;
	private static double distance = DEFAULT_DISTANCE;
	private static double flySpeed = DEFAULT_FLY_SPEED;
	private static Vec3d lastTarget = Vec3d.ZERO;
	private static boolean controlling;
	private static boolean stopRequested;
	private static long lastFrameNanos;

	private record DeferredBlockUpdate(BlockPos pos, BlockState state, int flags) {
	}

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

	/** Whether a replay could be started right now. */
	public static boolean isAvailable(MinecraftClient client) {
		Recording frozen = Recorder.getFrozen();
		return frozen != null
			&& client.player != null
			&& client.world != null
			&& client.player.isDead()
			&& client.world.getRegistryKey() == frozen.dimension();
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
		return tick;
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

	/** Entities the replay hides: everything real, and the player's own puppet in first person. */
	public static boolean hidesEntity(Entity entity) {
		if (!isActive()) {
			return false;
		}

		return !isPuppet(entity) || !still && view == ReplayView.FIRST_PERSON && entity == getPlayerPuppet();
	}

	// ---------------------------------------------------------------- start / stop

	/** Opens the replay screen from the death screen. Returns false if there is nothing to replay. */
	public static boolean open(MinecraftClient client) {
		if (isPlayback() || !isAvailable(client) || !(client.currentScreen instanceof DeathScreen deathScreen)) {
			return false;
		}

		if (!begin(client)) {
			return false;
		}

		startPlayback();
		client.setScreen(new ReplayScreen(deathScreen));
		return true;
	}

	/**
	 * Shows the frozen last frame on the death screen. Called automatically once the recording
	 * of the current death is ready, the death screen camera is in use, and the server has
	 * taken the live entities away.
	 */
	public static boolean showStill(MinecraftClient client) {
		if (isActive() || !begin(client)) {
			return false;
		}

		still = true;
		playing = false;
		tick = recording.tickCount() - 1;
		syncPuppets(recording.frames().get(tick));
		DeathReplayClient.LOGGER.info("Showing the last recorded frame on the death screen: {} puppets", PUPPETS.size());
		return true;
	}

	/** Binds the replay to the current death. The world is left as it is (the end of the recording). */
	private static boolean begin(MinecraftClient client) {
		if (isActive()) {
			return true;
		}

		Recording frozen = Recorder.getFrozen();
		if (frozen == null || frozen.frames().isEmpty() || !isAvailable(client)) {
			return false;
		}

		recording = frozen;
		world = client.world;
		deadPlayer = client.player;
		still = false;
		playing = false;
		stopRequested = false;
		nextPuppetId = 0;
		tick = frozen.tickCount() - 1;
		blocksAppliedThrough = frozen.tickCount() - 1;
		DEFERRED_BLOCK_UPDATES.clear();
		BLOCK_BREAKS.clear();
		return true;
	}

	private static void startPlayback() {
		Recording frozen = recording;
		still = false;
		view = DeathReplayConfig.get().replayView;
		distance = DEFAULT_DISTANCE;
		flySpeed = DEFAULT_FLY_SPEED;
		lastFrameNanos = 0L;

		// The world currently shows the moment of death; take it back to the start of the recording.
		jumpTo(0);
		playing = true;

		EntitySample player = frozen.playerAt(0);
		lastTarget = player != null ? player.pos().add(0.0, player.eyeHeight, 0.0) : frozen.deathPos();
		CAMERA.setRotation(player != null ? player.yaw : 0.0F, 20.0F);
		CAMERA.orbit(world, deadPlayer, lastTarget, distance);
		DeathReplayClient.LOGGER.info("Replay started: {} ticks, {} puppets", frozen.tickCount(), PUPPETS.size());
	}

	/**
	 * Called when the replay screen goes away: playback ends, the world returns to the present,
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
		return DeathView.isActive() && isAvailable(client) && !hasLiveEntitiesNearby(client);
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

	/** Ends the replay and puts the world back exactly as the server last described it. */
	public static void stop(MinecraftClient client) {
		if (!isActive()) {
			return;
		}

		ClientWorld replayWorld = world;
		if (client.world == replayWorld && replayWorld != null) {
			removeAllPuppets(replayWorld);
			for (BlockBreak blockBreak : BLOCK_BREAKS) {
				replayWorld.setBlockBreakingInfo(blockBreak.entityId(), blockBreak.pos(), -1);
			}

			// Back to the state at the end of the recording...
			setBlocksThrough(replayWorld, recording.tickCount() - 1);
		}

		recording = null;
		world = null;
		deadPlayer = null;
		playing = false;
		still = false;
		controlling = false;
		stopRequested = false;
		PUPPETS.clear();
		PUPPET_ENTITIES.clear();
		BLOCK_BREAKS.clear();
		INPUT.releaseMouse(client);

		// ...then everything the server sent while the replay was running, in order.
		// (The replay is no longer active here, so these go straight into the world.)
		if (client.world == replayWorld && replayWorld != null) {
			for (DeferredBlockUpdate update : DEFERRED_BLOCK_UPDATES) {
				replayWorld.handleBlockUpdate(update.pos(), update.state(), update.flags());
			}
		}

		DEFERRED_BLOCK_UPDATES.clear();
		DeathReplayClient.LOGGER.info("Replay stopped");
	}

	private static boolean isStillValid(MinecraftClient client) {
		return client.world == world && client.player == deadPlayer && deadPlayer != null && deadPlayer.isDead();
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

		if (playing && !client.isPaused()) {
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

	/**
	 * Called for every block update the server sends. While a replay runs the update is queued
	 * (returns true) instead of being applied to a world that is showing the past; it is
	 * applied when the replay ends.
	 */
	public static boolean deferBlockUpdate(ClientWorld updatedWorld, BlockPos pos, BlockState state, int flags) {
		// The still does not rewind the world, so there is nothing to protect from updates.
		if (!isPlayback() || updatedWorld != world) {
			return false;
		}

		DEFERRED_BLOCK_UPDATES.add(new DeferredBlockUpdate(pos.toImmutable(), state, flags));
		return true;
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
				CAMERA.orbit(world, player != null ? player : deadPlayer, lastTarget, distance);
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
			jumpTo(target);
		}
	}

	public static void seekRelative(int direction) {
		seek(tick + direction * SEEK_TICKS);
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

	static void setControlling(MinecraftClient client, boolean value) {
		controlling = value && isPlayback();
		if (controlling) {
			INPUT.grabMouse(client);
		} else {
			INPUT.releaseMouse(client);
		}
	}

	static void regrabMouse(MinecraftClient client) {
		if (controlling) {
			INPUT.grabMouse(client);
		}
	}
}
