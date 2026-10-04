package io.github.autyism.deathreplay.replay;

import io.github.autyism.deathreplay.mixin.ClientPlayNetworkHandlerAccessor;
import io.github.autyism.deathreplay.record.Recording;
import io.github.autyism.deathreplay.record.WorldSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Difficulty;
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

		// The same steps the client takes for a chunk packet from the server, through the same
		// methods. That matters: renderer mods such as Sodium keep their own list of chunks that
		// are ready to draw, and fill it from hooks inside exactly these methods. The light
		// method works on the handler's world field, so that field points at the stage for the
		// few milliseconds this takes. Everything here runs on the render thread, where packets
		// are handled too, so no packet can be applied to the wrong world in between.
		ClientPlayNetworkHandlerAccessor access = (ClientPlayNetworkHandlerAccessor) handler;
		ClientWorld realWorld = access.deathreplay$getWorld();
		access.deathreplay$setWorld(world);
		try {
			LightingProvider light = world.getChunkManager().getLightingProvider();
			for (ChunkDataS2CPacket packet : snapshot.chunks()) {
				int x = packet.getChunkX();
				int z = packet.getChunkZ();
				ChunkData data = packet.getChunkData();
				WorldChunk chunk = world.getChunkManager().loadChunkFromPacket(x, z, data.getSectionsDataBuf(), data.getHeightmap(), data.getBlockEntities(x, z));
				if (chunk == null) {
					continue;
				}

				access.deathreplay$readLightData(x, z, packet.getLightData(), false);
				ChunkSection[] sections = chunk.getSectionArray();
				for (int i = 0; i < sections.length; i++) {
					light.setSectionStatus(ChunkSectionPos.from(chunk.getPos(), world.sectionIndexToCoord(i)), sections[i].isEmpty());
				}
			}

			light.doLightUpdates();
		} finally {
			access.deathreplay$setWorld(realWorld);
		}

		return world;
	}
}
