package io.github.autyi6969.deathreplay.replay;

import java.util.BitSet;
import java.util.Iterator;

import io.github.autyi6969.deathreplay.record.Recording;
import io.github.autyi6969.deathreplay.record.WorldSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * Builds the stage a replay is played on: a client-side world that holds nothing but the
 * recorded terrain. It is never the game's current world. It is only handed to the renderers
 * while a replay is shown, so the real world, the real player and everything the server sends
 * carry on untouched underneath.
 */
final class ReplayWorld {
	/**
	 * Vanilla only plays death animations for entities within the simulation distance of the
	 * real player, who may be far from the replayed place. Large enough to always pass.
	 */
	private static final int SIMULATION_DISTANCE = 1_000_000;

	private ReplayWorld() {
	}

	static ClientWorld create(MinecraftClient client, Recording recording) {
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler == null) {
			throw new IllegalStateException("not connected");
		}

		WorldSnapshot snapshot = recording.snapshot();
		ClientWorld.Properties properties = new ClientWorld.Properties(Difficulty.NORMAL, false, snapshot.flat());
		ClientWorld world = new ClientWorld(
			handler,
			properties,
			snapshot.dimension(),
			snapshot.dimensionType(),
			snapshot.radius() + 2,
			SIMULATION_DISTANCE,
			client.worldRenderer,
			false,
			snapshot.biomeSeed(),
			snapshot.seaLevel()
		);
		world.getChunkManager().setChunkMapCenter(snapshot.centerChunkX(), snapshot.centerChunkZ());
		world.setTime(snapshot.time(), snapshot.timeOfDay(), false);
		world.setRainGradient(snapshot.rainGradient());
		world.setThunderGradient(snapshot.thunderGradient());

		// The same steps the client takes for a chunk packet from the server.
		for (ChunkDataS2CPacket packet : snapshot.chunks()) {
			int x = packet.getChunkX();
			int z = packet.getChunkZ();
			ChunkData data = packet.getChunkData();
			WorldChunk chunk = world.getChunkManager().loadChunkFromPacket(x, z, data.getSectionsDataBuf(), data.getHeightmap(), data.getBlockEntities(x, z));
			if (chunk == null) {
				continue;
			}

			LightingProvider light = world.getChunkManager().getLightingProvider();
			LightData lightData = packet.getLightData();
			readLight(light, x, z, LightType.SKY, lightData.getInitedSky(), lightData.getUninitedSky(), lightData.getSkyNibbles().iterator());
			readLight(light, x, z, LightType.BLOCK, lightData.getInitedBlock(), lightData.getUninitedBlock(), lightData.getBlockNibbles().iterator());
			light.setColumnEnabled(new ChunkPos(x, z), true);

			ChunkSection[] sections = chunk.getSectionArray();
			for (int i = 0; i < sections.length; i++) {
				light.setSectionStatus(ChunkSectionPos.from(chunk.getPos(), world.sectionIndexToCoord(i)), sections[i].isEmpty());
			}
		}

		world.getChunkManager().getLightingProvider().doLightUpdates();
		return world;
	}

	private static void readLight(LightingProvider light, int chunkX, int chunkZ, LightType type, BitSet inited, BitSet uninited, Iterator<byte[]> nibbles) {
		for (int i = 0; i < light.getHeight(); i++) {
			boolean hasData = inited.get(i);
			if (hasData || uninited.get(i)) {
				ChunkSectionPos pos = ChunkSectionPos.from(chunkX, light.getBottomY() + i, chunkZ);
				light.enqueueSectionData(type, pos, hasData ? new ChunkNibbleArray(nibbles.next().clone()) : new ChunkNibbleArray());
			}
		}
	}
}
