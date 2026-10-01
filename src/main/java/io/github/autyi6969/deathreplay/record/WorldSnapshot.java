package io.github.autyi6969.deathreplay.record;

import java.util.ArrayList;
import java.util.List;

import io.github.autyi6969.deathreplay.mixin.BiomeAccessAccessor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;

/**
 * The terrain around a death, as the client had it at the end of the recording.
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
 * @param centerChunkX  chunk the death happened in
 * @param centerChunkZ  chunk the death happened in
 * @param radius        how many chunks around the centre were looked at
 * @param chunks        the chunks that were loaded within that radius
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
	/** Chunks further than this from the death are not kept, whatever the render distance. */
	public static final int MAX_RADIUS = 8;

	public static WorldSnapshot capture(ClientWorld world, ChunkPos center, int radius) {
		List<ChunkDataS2CPacket> chunks = new ArrayList<>();
		for (int x = center.x - radius; x <= center.x + radius; x++) {
			for (int z = center.z - radius; z <= center.z + radius; z++) {
				WorldChunk chunk = world.getChunkManager().getWorldChunk(x, z, false);
				if (chunk != null) {
					chunks.add(new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null));
				}
			}
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
			center.x,
			center.z,
			radius,
			List.copyOf(chunks)
		);
	}
}
