package io.github.autyi6969.deathreplay.record;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.autyi6969.deathreplay.DeathReplayClient;
import io.github.autyi6969.deathreplay.config.DeathReplayConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * The rolling recorder.
 *
 * <p>While the player is alive, every client tick appends one {@link Frame} to an in-memory
 * ring buffer that holds the last N seconds (N from the settings). Only data the client
 * already has is read: entities in the client world and packets the server sent. Nothing is
 * requested from, or sent to, the server.
 *
 * <p>When the player dies the buffer stops rolling: no older frame is dropped any more, a
 * short tail is appended (the server keeps sending the scene for one more second, which is
 * the death animation), a copy of the terrain along the player's route is taken, and the result is frozen
 * into a {@link Recording}. It stays available, also after the respawn, until the next death
 * replaces it or the player leaves the server.
 */
public final class Recorder {
	/** Entities further away than this from the player are not recorded. */
	public static final double CAPTURE_RADIUS = 64.0;
	/** Safety valve for entity-heavy places. */
	private static final int MAX_ENTITIES_PER_FRAME = 512;
	private static final int MAX_EVENTS_PER_TICK = 2048;
	/**
	 * Ticks recorded after the death is seen. The server removes the dead player, and with it
	 * every entity the client knows, 20 ticks after death; stop a little before that.
	 */
	public static final int DEATH_TAIL_TICKS = 15;

	private enum State {
		/** No world, or the player joined dead: nothing to record yet. */
		IDLE,
		RECORDING,
		/** Player is dead, appending the tail. */
		TAIL,
		/** Recording frozen, waiting for the respawn. */
		FROZEN
	}

	private static final ArrayDeque<Frame> BUFFER = new ArrayDeque<>();
	private static final List<RecordedEvent> PENDING_EVENTS = new ArrayList<>();
	/** Last captured look of each entity, so unchanged looks are shared between frames. */
	private static final Int2ObjectMap<EntityAppearance> APPEARANCES = new Int2ObjectOpenHashMap<>();
	/** Spawn packets of the entities currently known to the client, by entity id. */
	private static final Int2ObjectMap<EntitySpawnS2CPacket> SPAWN_PACKETS = new Int2ObjectOpenHashMap<>();
	/** The world {@link #SPAWN_PACKETS} belongs to; entity ids mean nothing across worlds. */
	@Nullable
	private static ClientWorld spawnPacketWorld;

	/**
	 * Chunks on the player's recent route that the client has since unloaded, captured at the
	 * moment of unloading, by {@link ChunkPos#toLong()}. Without them a replay that starts far
	 * from the death (elytra, teleport, a long sprint with a short render distance) would
	 * start in empty space.
	 */
	private static final Long2ObjectMap<UnloadedChunk> UNLOADED_CHUNKS = new Long2ObjectOpenHashMap<>();
	/** Every how many frames the player's position is looked at when working out the route. */
	private static final int ROUTE_STEP = 10;

	private record UnloadedChunk(ChunkDataS2CPacket packet, long tick) {
	}

	private static State state = State.IDLE;
	@Nullable
	private static ClientWorld world;
	@Nullable
	private static ClientPlayerEntity player;
	private static int deathFrame;
	private static int tailTicksLeft;
	private static long deathTimeMillis;
	@Nullable
	private static Text deathMessage;
	/** Recording of the death the player is still dead from; {@code null} once respawned. */
	@Nullable
	private static Recording frozen;
	/** The most recent recording of this session on this server, respawned or not. */
	@Nullable
	private static Recording last;
	/** True from a death until the hint about the replay key has been shown after the respawn. */
	private static boolean respawnHintPending;
	private static long tickCounter;
	// Cost of the per-tick capture, for the self-test and for anyone who wants to check.
	private static long captureNanosTotal;
	private static long captureNanosMax;
	private static long captureCount;

	private Recorder() {
	}

	// ---------------------------------------------------------------- queries

	/** The recording of the death the player has not respawned from yet, if any. */
	@Nullable
	public static Recording getFrozen() {
		return frozen;
	}

	/** The latest recording, whether or not the player has respawned since. */
	@Nullable
	public static Recording getLast() {
		return last;
	}

	/** Average time one tick of recording has cost so far, in microseconds. */
	public static double averageCaptureMicros() {
		return captureCount == 0L ? 0.0 : captureNanosTotal / 1000.0 / captureCount;
	}

	/** The most expensive single tick of recording so far, in microseconds. */
	public static double maxCaptureMicros() {
		return captureNanosMax / 1000.0;
	}

	public static void resetCaptureStats() {
		captureNanosTotal = 0L;
		captureNanosMax = 0L;
		captureCount = 0L;
	}

	/** True while events should be collected. */
	public static boolean isCapturing() {
		return state == State.RECORDING || state == State.TAIL;
	}

	public static int bufferedTicks() {
		return BUFFER.size();
	}

	// ---------------------------------------------------------------- tick

	/** Called at the end of every client tick. */
	public static void tick(MinecraftClient client) {
		ClientPlayerEntity currentPlayer = client.player;
		ClientWorld currentWorld = client.world;
		if (currentPlayer == null || currentWorld == null) {
			if (world != null || last != null) {
				// Left the server: a recording is only meaningful with that server's registries.
				reset();
				last = null;
				respawnHintPending = false;
			}

			return;
		}

		if (currentWorld != world || currentPlayer != player) {
			// New world (join, dimension change) or new player object (respawn): entity ids and
			// block history start over. The last frozen recording is kept for watching later.
			reset();
			world = currentWorld;
			player = currentPlayer;
			if (respawnHintPending && !currentPlayer.isDead()) {
				respawnHintPending = false;
				DeathReplayClient.showRespawnHint(client);
			}
		}

		if (client.isPaused()) {
			return;
		}

		tickCounter++;
		if (tickCounter % 200L == 0L) {
			pruneSpawnPackets(currentWorld);
			// An unloaded chunk matters only while the frames recorded near it are still in the buffer.
			long oldest = tickCounter - DeathReplayConfig.get().bufferSeconds * Recording.TICKS_PER_SECOND - 200L;
			UNLOADED_CHUNKS.values().removeIf(chunk -> chunk.tick() < oldest);
		}

		switch (state) {
			case IDLE -> {
				PENDING_EVENTS.clear();
				if (!currentPlayer.isDead()) {
					state = State.RECORDING;
				}
			}
			case RECORDING -> {
				captureFrame(currentWorld, currentPlayer);
				int capacity = DeathReplayConfig.get().bufferSeconds * Recording.TICKS_PER_SECOND;
				while (BUFFER.size() > capacity) {
					BUFFER.removeFirst();
				}

				if (currentPlayer.isDead()) {
					deathFrame = BUFFER.size() - 1;
					deathTimeMillis = System.currentTimeMillis();
					tailTicksLeft = DEATH_TAIL_TICKS;
					state = State.TAIL;
				}
			}
			case TAIL -> {
				captureFrame(currentWorld, currentPlayer);
				if (--tailTicksLeft <= 0) {
					freeze(client, currentWorld, currentPlayer);
				}
			}
			case FROZEN -> PENDING_EVENTS.clear();
		}
	}

	private static void reset() {
		BUFFER.clear();
		PENDING_EVENTS.clear();
		APPEARANCES.clear();
		UNLOADED_CHUNKS.clear();
		state = State.IDLE;
		world = null;
		player = null;
		frozen = null;
		deathMessage = null;
	}

	private static void freeze(MinecraftClient client, ClientWorld currentWorld, ClientPlayerEntity currentPlayer) {
		List<Frame> frames = List.copyOf(BUFFER);
		BUFFER.clear();
		APPEARANCES.clear();
		PENDING_EVENTS.clear();
		state = State.FROZEN;

		EntitySample atDeath = frames.get(deathFrame).find(currentPlayer.getId());
		long snapshotStart = System.nanoTime();
		Long2ObjectMap<ChunkDataS2CPacket> unloaded = new Long2ObjectOpenHashMap<>();
		UNLOADED_CHUNKS.forEach((pos, chunk) -> unloaded.put((long) pos, chunk.packet()));
		WorldSnapshot snapshot = WorldSnapshot.capture(currentWorld, currentPlayer.getChunkPos(), route(frames, currentPlayer.getId()), unloaded,
			client.options.getClampedViewDistance());
		UNLOADED_CHUNKS.clear();
		double snapshotMillis = (System.nanoTime() - snapshotStart) / 1.0e6;
		frozen = new Recording(
			frames,
			deathFrame,
			currentPlayer.getId(),
			currentPlayer.getGameProfile().name(),
			currentPlayer.getUuid(),
			snapshot,
			atDeath != null ? atDeath.pos() : currentPlayer.getEntityPos(),
			deathMessage,
			deathTimeMillis
		);
		last = frozen;
		respawnHintPending = true;
		DeathReplayClient.LOGGER.info("Froze death recording: {} ticks ({} s), death at tick {}, {} chunks of terrain copied in {} ms",
			frames.size(), String.format("%.1f", frozen.seconds()), deathFrame, snapshot.chunks().size(), String.format("%.1f", snapshotMillis));

		if (DeathReplayConfig.get().autoSave) {
			ReplayFileWriter.saveAsync(client, frozen, currentWorld.getRegistryManager());
		}
	}

	// ---------------------------------------------------------------- route and terrain

	/** The chunks the player was in over {@code frames}, without repeats. */
	private static List<ChunkPos> route(Iterable<Frame> frames, int playerId) {
		List<ChunkPos> route = new ArrayList<>();
		int index = 0;
		for (Frame frame : frames) {
			if (index++ % ROUTE_STEP != 0) {
				continue;
			}

			EntitySample sample = frame.find(playerId);
			if (sample != null) {
				ChunkPos pos = new ChunkPos(BlockPos.ofFloored(sample.x, sample.y, sample.z));
				if (route.isEmpty() || !route.getLast().equals(pos)) {
					route.add(pos);
				}
			}
		}

		return route;
	}

	/**
	 * Called just before the client unloads a chunk. If the player passed near it within the
	 * buffered time, its terrain is kept for the replay.
	 */
	public static void onChunkUnload(ClientWorld unloadingWorld, ChunkPos pos) {
		if (state != State.RECORDING || unloadingWorld != world || player == null || BUFFER.isEmpty()) {
			return;
		}

		if (!WorldSnapshot.isNearRoute(pos, route(BUFFER, player.getId()))) {
			return;
		}

		WorldChunk chunk = unloadingWorld.getChunkManager().getWorldChunk(pos.x, pos.z, false);
		if (chunk != null) {
			UNLOADED_CHUNKS.put(pos.toLong(), new UnloadedChunk(new ChunkDataS2CPacket(chunk, unloadingWorld.getLightingProvider(), null, null), tickCounter));
		}
	}

	/**
	 * Called just before the client moves the centre of its chunk storage (the player crossed
	 * a chunk border, or was teleported). Returns the loaded chunks near the recorded route.
	 *
	 * <p>After a long jump the storage no longer reaches the old chunks at all, and the unload
	 * packets that follow find nothing to unload. {@link #afterChunkCenterChange} keeps the ones
	 * that dropped out of reach.
	 */
	public static List<WorldChunk> beforeChunkCenterChange(ClientWorld movingWorld) {
		if (state != State.RECORDING || movingWorld != world || player == null || BUFFER.isEmpty()) {
			return List.of();
		}

		List<WorldChunk> nearRoute = new ArrayList<>();
		LongSet seen = new LongOpenHashSet();
		for (ChunkPos onRoute : route(BUFFER, player.getId())) {
			for (int x = onRoute.x - WorldSnapshot.PATH_RADIUS; x <= onRoute.x + WorldSnapshot.PATH_RADIUS; x++) {
				for (int z = onRoute.z - WorldSnapshot.PATH_RADIUS; z <= onRoute.z + WorldSnapshot.PATH_RADIUS; z++) {
					if (seen.add(ChunkPos.toLong(x, z))) {
						WorldChunk chunk = movingWorld.getChunkManager().getWorldChunk(x, z, false);
						if (chunk != null) {
							nearRoute.add(chunk);
						}
					}
				}
			}
		}

		return nearRoute;
	}

	/** Keeps the chunks of {@code nearRoute} that the storage can no longer reach. */
	public static void afterChunkCenterChange(ClientWorld movedWorld, List<WorldChunk> nearRoute) {
		for (WorldChunk chunk : nearRoute) {
			ChunkPos pos = chunk.getPos();
			if (movedWorld.getChunkManager().getWorldChunk(pos.x, pos.z, false) != chunk) {
				UNLOADED_CHUNKS.put(pos.toLong(), new UnloadedChunk(new ChunkDataS2CPacket(chunk, movedWorld.getLightingProvider(), null, null), tickCounter));
			}
		}
	}

	// ---------------------------------------------------------------- frame capture

	private static void captureFrame(ClientWorld currentWorld, ClientPlayerEntity currentPlayer) {
		long start = System.nanoTime();
		List<EntitySample> samples = new ArrayList<>();
		IntSet seen = new IntOpenHashSet();
		double maxDistanceSquared = CAPTURE_RADIUS * CAPTURE_RADIUS;

		// The player first, so it is never cut off by the entity cap.
		samples.add(sample(currentPlayer, true));
		seen.add(currentPlayer.getId());

		for (Entity entity : currentWorld.getEntities()) {
			if (entity == currentPlayer || entity.isRemoved() || entity.squaredDistanceTo(currentPlayer) > maxDistanceSquared) {
				continue;
			}

			if (samples.size() >= MAX_ENTITIES_PER_FRAME) {
				break;
			}

			samples.add(sample(entity, false));
			seen.add(entity.getId());
		}

		APPEARANCES.keySet().retainAll(seen);
		BUFFER.addLast(new Frame(samples.toArray(EntitySample[]::new), PENDING_EVENTS.toArray(RecordedEvent[]::new)));
		PENDING_EVENTS.clear();

		long nanos = System.nanoTime() - start;
		captureNanosTotal += nanos;
		captureNanosMax = Math.max(captureNanosMax, nanos);
		captureCount++;
	}

	private static EntitySample sample(Entity entity, boolean localPlayer) {
		EntityAppearance previous = APPEARANCES.get(entity.getId());
		EntityAppearance appearance = captureAppearance(entity, localPlayer, previous);
		if (appearance != previous) {
			APPEARANCES.put(entity.getId(), appearance);
		}

		return new EntitySample(entity, appearance);
	}

	/** Returns {@code previous} itself when nothing about the entity's look changed. */
	private static EntityAppearance captureAppearance(Entity entity, boolean localPlayer, @Nullable EntityAppearance previous) {
		List<DataTracker.SerializedEntry<?>> tracked = entity.getDataTracker().getChangedEntries();
		if (tracked == null) {
			tracked = List.of();
		}

		ItemStack[] equipment = null;
		if (entity instanceof LivingEntity living) {
			EquipmentSlot[] slots = EquipmentSlot.values();
			equipment = new ItemStack[slots.length];
			for (EquipmentSlot slot : slots) {
				equipment[slot.ordinal()] = living.getEquippedStack(slot);
			}
		}

		if (previous != null
			&& previous.type() == entity.getType()
			&& sameTrackedData(previous.trackedData(), tracked)
			&& sameEquipment(previous.equipment(), equipment)) {
			return previous;
		}

		if (equipment != null) {
			for (int i = 0; i < equipment.length; i++) {
				equipment[i] = equipment[i].copy();
			}
		}

		return new EntityAppearance(
			entity.getType(),
			entity.getUuid(),
			entity instanceof PlayerEntity playerEntity ? playerEntity.getGameProfile() : null,
			localPlayer,
			localPlayer ? null : SPAWN_PACKETS.get(entity.getId()),
			List.copyOf(tracked),
			equipment
		);
	}

	private static boolean sameTrackedData(List<DataTracker.SerializedEntry<?>> a, List<DataTracker.SerializedEntry<?>> b) {
		if (a.size() != b.size()) {
			return false;
		}

		for (int i = 0; i < a.size(); i++) {
			DataTracker.SerializedEntry<?> left = a.get(i);
			DataTracker.SerializedEntry<?> right = b.get(i);
			if (left.id() != right.id()) {
				return false;
			}

			Object leftValue = left.value();
			Object rightValue = right.value();
			// ItemStack has no equals(); without this every dropped item would look "changed" each tick.
			boolean same = leftValue instanceof ItemStack leftStack && rightValue instanceof ItemStack rightStack
				? ItemStack.areEqual(leftStack, rightStack)
				: Objects.equals(leftValue, rightValue);
			if (!same) {
				return false;
			}
		}

		return true;
	}

	private static boolean sameEquipment(ItemStack @Nullable [] a, ItemStack @Nullable [] b) {
		if (a == null || b == null) {
			return a == b;
		}

		for (int i = 0; i < a.length; i++) {
			if (!ItemStack.areEqual(a[i], b[i])) {
				return false;
			}
		}

		return true;
	}

	// ---------------------------------------------------------------- hooks (called from mixins, render thread only)

	public static void onEvent(RecordedEvent event) {
		if (isCapturing() && PENDING_EVENTS.size() < MAX_EVENTS_PER_TICK) {
			PENDING_EVENTS.add(event);
		}
	}

	public static void onBlockChange(ClientWorld changedWorld, BlockPos pos, BlockState oldState, BlockState newState) {
		if (isCapturing() && changedWorld == world && oldState != newState) {
			onEvent(new RecordedEvent.BlockChange(pos.toImmutable(), oldState, newState));
		}
	}

	/** Kept at all times, not only while capturing: entities usually spawn long before the death. */
	public static void onEntitySpawn(ClientWorld spawnWorld, EntitySpawnS2CPacket packet) {
		if (spawnWorld != spawnPacketWorld) {
			SPAWN_PACKETS.clear();
			spawnPacketWorld = spawnWorld;
		}

		SPAWN_PACKETS.put(packet.getEntityId(), packet);
	}

	public static void onDeathMessage(Text message) {
		deathMessage = message;
	}

	private static void pruneSpawnPackets(ClientWorld currentWorld) {
		if (currentWorld == spawnPacketWorld) {
			SPAWN_PACKETS.keySet().removeIf(id -> currentWorld.getEntityById(id) == null);
		}
	}
}
