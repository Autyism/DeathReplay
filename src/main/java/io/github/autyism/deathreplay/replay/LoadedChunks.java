package io.github.autyism.deathreplay.replay;

//? if >=26.2 {
/*import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/^*
 * 26.2+: the level renderer only learns which chunks are loaded (and which sections are empty)
 * from the chunk cache's lists of newly added ones, and forgets everything when it is pointed at
 * another level. After a replay it is pointed back at the real world, whose chunks are all
 * loaded already, so they are listed again here, exactly the way the chunk cache lists a chunk
 * that arrives. Without this the real world stays invisible after a replay.
 ^/
final class LoadedChunks {
	/^* Further than any server sends chunks (view distance 32 plus the cache's margin). ^/
	private static final int SEARCH_RADIUS = 40;

	private LoadedChunks() {
	}

	static void announce(ClientLevel level, ChunkPos around) {
		ClientChunkCache cache = level.getChunkSource();
		LongOpenHashSet loaded = cache.addedLoadedChunks();
		LongOpenHashSet empty = cache.addedEmptySections();
		for (int x = around.x() - SEARCH_RADIUS; x <= around.x() + SEARCH_RADIUS; x++) {
			for (int z = around.z() - SEARCH_RADIUS; z <= around.z() + SEARCH_RADIUS; z++) {
				LevelChunk chunk = cache.getChunk(x, z, false);
				if (chunk == null) {
					continue;
				}

				loaded.add(chunk.getPos().pack());
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					if (sections[i].hasOnlyAir()) {
						empty.add(SectionPos.asLong(x, chunk.getSectionYFromSectionIndex(i), z));
					}
				}
			}
		}
	}
}
*///?}
