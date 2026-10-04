package io.github.autyism.deathreplay.record;

import java.util.ArrayList;
import java.util.List;

import io.github.autyism.deathreplay.mixin.BiomeAccessAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;

/**
 * The terrain a recording needs, as the client had it at the end of the recording.
 *
 * <p>A replay starts where the player was at the start of the recording, which after thirty
 * seconds of sprinting or flying is a long way from where the player died. So the snapshot
 * covers the whole route: the chunks around every place the player passed through, plus a
 * wider area around the death itself.
 *
 * <p>Each chunk is kept in the form the server sends chunks in (blocks, biomes, block
 * entities, light), built here from the client's own copy. That form is compact, immutable,
 * can be written to a file with the vanilla codec, and can be loaded into a world through
 * the same path as a chunk from the server. Nothing is requested from the server for this.
 *
 * @param dimension     dimension the death happened in
 * @param dimensionType its dimension type (height, light, sky)
 * @param biomeSeed     the hashed seed the client uses to blend biome colours
 * @param seaLevel      sea level of the dimension
 * @param flat          whether the world is a superflat world (changes the horizon)
 * @param time          world age at the snapshot
 * @param timeOfDay     time of day at the snapshot
 * @param rainGradient  how much it was raining, 0..1
 * @param thunderGradient how much it was thundering, 0..1
 * @param centerChunkX  middle of the area the chunks span
 * @param centerChunkZ  middle of the area the chunks span
 * @param radius        how many chunks that area reaches from its middle, in the longer direction
 * @param chunks        the chunks themselves
 */
public record WorldSnapshot(
	RegistryKey<World> dimension,
	RegistryEntry<DimensionType> dimensionType,
	long biomeSeed,
	int seaLevel,
	boolean flat,
	long time,
	long timeOfDay,
	float rainGradient,
	float thunderGradient,
	int centerChunkX,
	int centerChunkZ,
	int radius,
	List<ChunkDataS2CPacket> chunks
) {
	/** Chunks kept around the place of death, whatever the render distance. */
	public static final int MAX_RADIUS = 8;
	/** Chunks kept around every other place on the player's route. */
	public static final int PATH_RADIUS = 6;
	/** If the route is so long that more chunks than this would be kept, the route's margin shrinks. */
	private static final int MAX_CHUNKS = 2500;

	/**
	 * @param death        chunk the player died in
	 * @param route        chunks the player was in during the recording
	 * @param unloaded     chunks of the route the client has already unloaded, captured when
	 *                     they were unloaded, by {@link ChunkPos#toLong()}
	 * @param viewDistance the client's render distance; nothing beyond it is kept
	 */
	public static WorldSnapshot capture(ClientWorld world, ChunkPos death, List<ChunkPos> route, Long2ObjectMap<ChunkDataS2CPacket> unloaded, int viewDistance) {
		int deathRadius = Math.min(MAX_RADIUS, viewDistance);
		int pathRadius = Math.min(PATH_RADIUS, viewDistance);
		LongSet wanted = wantedChunks(death, deathRadius, route, pathRadius);
		while (wanted.size() > MAX_CHUNKS && pathRadius > 1) {
			pathRadius--;
			wanted = wantedChunks(death, deathRadius, route, pathRadius);
		}

		List<ChunkDataS2CPacket> chunks = new ArrayList<>();
		int minX = death.x;
		int maxX = death.x;
		int minZ = death.z;
		int maxZ = death.z;
		for (long packed : wanted) {
			int x = ChunkPos.getPackedX(packed);
			int z = ChunkPos.getPackedZ(packed);
			WorldChunk chunk = world.getChunkManager().getWorldChunk(x, z, false);
			ChunkDataS2CPacket packet = chunk != null ? new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null) : unloaded.get(packed);
			if (packet == null) {
				continue;
			}

			chunks.add(packet);
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}

		return new WorldSnapshot(
			world.getRegistryKey(),
			world.getDimensionEntry(),
			((BiomeAccessAccessor) world.getBiomeAccess()).deathreplay$getSeed(),
			world.getSeaLevel(),
			world.getLevelProperties().getVoidDarknessRange() == 1.0F,
			world.getTime(),
			world.getTimeOfDay(),
			world.getRainGradient(1.0F),
			world.getThunderGradient(1.0F),
			(minX + maxX) / 2,
			(minZ + maxZ) / 2,
			Math.max(maxX - minX, maxZ - minZ) / 2 + 1,
			List.copyOf(chunks)
		);
	}

	private static LongSet wantedChunks(ChunkPos death, int deathRadius, List<ChunkPos> route, int pathRadius) {
		LongSet wanted = new LongLinkedOpenHashSet();
		addSquare(wanted, death, deathRadius);
		for (ChunkPos pos : route) {
			addSquare(wanted, pos, pathRadius);
		}

		return wanted;
	}

	private static void addSquare(LongSet set, ChunkPos center, int radius) {
		for (int x = center.x - radius; x <= center.x + radius; x++) {
			for (int z = center.z - radius; z <= center.z + radius; z++) {
				set.add(ChunkPos.toLong(x, z));
			}
		}
	}

	/** Whether {@code pos} is within {@link #PATH_RADIUS} of a chunk on {@code route}. */
	public static boolean isNearRoute(ChunkPos pos, List<ChunkPos> route) {
		for (ChunkPos onRoute : route) {
			if (Math.abs(onRoute.x - pos.x) <= PATH_RADIUS && Math.abs(onRoute.z - pos.z) <= PATH_RADIUS) {
				return true;
			}
		}

		return false;
	}
}
