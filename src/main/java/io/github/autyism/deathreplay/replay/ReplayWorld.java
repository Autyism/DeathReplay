package io.github.autyism.deathreplay.replay;

import io.github.autyism.deathreplay.mixin.ClientPlayNetworkHandlerAccessor;
import io.github.autyism.deathreplay.record.Recording;
import io.github.autyism.deathreplay.record.WorldSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

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

	static ClientLevel create(Minecraft client, Recording recording) {
		ClientPacketListener handler = client.getConnection();
		if (handler == null) {
			throw new IllegalStateException("not connected");
		}

		WorldSnapshot snapshot = recording.snapshot();
		ClientLevel.ClientLevelData properties = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, snapshot.flat());
		ClientLevel world = new ClientLevel(
			handler,
			properties,
			snapshot.dimension(),
			snapshot.dimensionType(),
			snapshot.radius() + 2,
			SIMULATION_DISTANCE,
			client.levelRenderer,
			false,
			snapshot.biomeSeed(),
			snapshot.seaLevel()
		);
		world.getChunkSource().updateViewCenter(snapshot.centerChunkX(), snapshot.centerChunkZ());
		world.setTimeFromServer(snapshot.time(), snapshot.timeOfDay(), false);
		world.setRainLevel(snapshot.rainGradient());
		world.setThunderLevel(snapshot.thunderGradient());

		// The same steps the client takes for a chunk packet from the server, through the same
		// methods. That matters: renderer mods such as Sodium keep their own list of chunks that
		// are ready to draw, and fill it from hooks inside exactly these methods. The light
		// method works on the handler's world field, so that field points at the stage for the
		// few milliseconds this takes. Everything here runs on the render thread, where packets
		// are handled too, so no packet can be applied to the wrong world in between.
		ClientPlayNetworkHandlerAccessor access = (ClientPlayNetworkHandlerAccessor) handler;
		ClientLevel realWorld = access.deathreplay$getWorld();
		access.deathreplay$setWorld(world);
		try {
			LevelLightEngine light = world.getChunkSource().getLightEngine();
			for (ClientboundLevelChunkWithLightPacket packet : snapshot.chunks()) {
				int x = packet.getX();
				int z = packet.getZ();
				ClientboundLevelChunkPacketData data = packet.getChunkData();
				LevelChunk chunk = world.getChunkSource().replaceWithPacketData(x, z, data.getReadBuffer(), data.getHeightmaps(), data.getBlockEntitiesTagsConsumer(x, z));
				if (chunk == null) {
					continue;
				}

				access.deathreplay$readLightData(x, z, packet.getLightData(), false);
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					light.updateSectionStatus(SectionPos.of(chunk.getPos(), world.getSectionYFromSectionIndex(i)), sections[i].hasOnlyAir());
				}
			}

			light.runLightUpdates();
		} finally {
			access.deathreplay$setWorld(realWorld);
		}

		return world;
	}
}
