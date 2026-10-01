package io.github.autyi6969.deathreplay.record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a file written by {@link ReplayFileWriter} back into a {@link Recording}.
 *
 * <p>The packet data inside a file is in the network format of the Minecraft version that
 * wrote it, and refers to that server's registries. A file from another Minecraft version is
 * refused; a file from a differently modded server may fail to decode, which is reported as
 * an {@link IOException} like any other unreadable file.
 */
public final class ReplayFileReader {
	private ReplayFileReader() {
	}

	public static Recording read(Path file, DynamicRegistryManager registries) throws IOException {
		try {
			NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
			int format = root.getInt("FormatVersion", 0);
			if (format != ReplayFileWriter.FORMAT_VERSION) {
				throw new IOException("replay file format " + format + " is not supported (expected " + ReplayFileWriter.FORMAT_VERSION + ")");
			}

			String version = root.getString("MinecraftVersion", "");
			if (!version.equals(SharedConstants.getGameVersion().name())) {
				throw new IOException("replay was saved by Minecraft " + version + ", this is " + SharedConstants.getGameVersion().name());
			}

			return parse(root, registries);
		} catch (RuntimeException e) {
			// Decoders throw all kinds of unchecked exceptions on data they do not understand.
			throw new IOException("replay file could not be decoded: " + e, e);
		}
	}

	private static Recording parse(NbtCompound root, DynamicRegistryManager registries) throws IOException {
		int tickCount = root.getInt("TickCount", 0);
		if (tickCount <= 0) {
			throw new IOException("replay file has no ticks");
		}

		List<List<EntitySample>> samples = new ArrayList<>(tickCount);
		List<List<RecordedEvent>> events = new ArrayList<>(tickCount);
		for (int i = 0; i < tickCount; i++) {
			samples.add(new ArrayList<>());
			events.add(new ArrayList<>());
		}

		NbtList entities = root.getListOrEmpty("Entities");
		for (int i = 0; i < entities.size(); i++) {
			readTrack(entities.getCompoundOrEmpty(i), registries, samples);
		}

		NbtList blockChanges = root.getListOrEmpty("BlockChanges");
		for (int i = 0; i < blockChanges.size(); i++) {
			NbtCompound nbt = blockChanges.getCompoundOrEmpty(i);
			int tick = nbt.getInt("Tick", -1);
			if (tick >= 0 && tick < tickCount) {
				BlockPos pos = new BlockPos(nbt.getInt("X", 0), nbt.getInt("Y", 0), nbt.getInt("Z", 0));
				BlockState oldState = NbtHelper.toBlockState(registries.getOrThrow(RegistryKeys.BLOCK), nbt.getCompoundOrEmpty("Old"));
				BlockState newState = NbtHelper.toBlockState(registries.getOrThrow(RegistryKeys.BLOCK), nbt.getCompoundOrEmpty("New"));
				events.get(tick).add(new RecordedEvent.BlockChange(pos, oldState, newState));
			}
		}

		NbtList otherEvents = root.getListOrEmpty("Events");
		for (int i = 0; i < otherEvents.size(); i++) {
			NbtCompound nbt = otherEvents.getCompoundOrEmpty(i);
			int tick = nbt.getInt("Tick", -1);
			RecordedEvent event = readEvent(nbt, registries);
			if (event != null && tick >= 0 && tick < tickCount) {
				events.get(tick).add(event);
			}
		}

		List<Frame> frames = new ArrayList<>(tickCount);
		for (int i = 0; i < tickCount; i++) {
			frames.add(new Frame(samples.get(i).toArray(EntitySample[]::new), events.get(i).toArray(RecordedEvent[]::new)));
		}

		NbtCompound player = root.getCompoundOrEmpty("Player");
		NbtList deathPos = root.getListOrEmpty("DeathPos");
		String deathMessage = root.getString("DeathMessage", "");
		return new Recording(
			List.copyOf(frames),
			Math.max(0, Math.min(tickCount - 1, root.getInt("DeathFrame", tickCount - 1))),
			player.getInt("EntityId", 0),
			player.getString("Name", ""),
			UUID.fromString(player.getString("UUID", new UUID(0L, 0L).toString())),
			readSnapshot(root, registries),
			new Vec3d(deathPos.getDouble(0, 0.0), deathPos.getDouble(1, 0.0), deathPos.getDouble(2, 0.0)),
			deathMessage.isEmpty() ? null : Text.literal(deathMessage),
			root.getLong("DeathTimeMillis", 0L)
		);
	}

	private static WorldSnapshot readSnapshot(NbtCompound root, DynamicRegistryManager registries) throws IOException {
		NbtCompound world = root.getCompoundOrEmpty("World");
		RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(root.getString("Dimension", "minecraft:overworld")));
		Identifier typeId = Identifier.of(world.getString("DimensionType", "minecraft:overworld"));
		RegistryEntry<DimensionType> dimensionType = registries.getOrThrow(RegistryKeys.DIMENSION_TYPE)
			.getOptional(RegistryKey.of(RegistryKeys.DIMENSION_TYPE, typeId))
			.orElseThrow(() -> new IOException("this server has no dimension type " + typeId));

		List<ChunkDataS2CPacket> chunks = new ArrayList<>();
		NbtList chunkList = root.getListOrEmpty("Chunks");
		for (NbtElement element : chunkList) {
			byte[] bytes = element.asByteArray().orElseThrow(() -> new IOException("malformed chunk entry"));
			chunks.add(decode(ChunkDataS2CPacket.CODEC, bytes, registries));
		}

		return new WorldSnapshot(
			dimension,
			dimensionType,
			world.getLong("BiomeSeed", 0L),
			world.getInt("SeaLevel", 63),
			world.getBoolean("Flat", false),
			world.getLong("Time", 0L),
			world.getLong("TimeOfDay", 6000L),
			world.getFloat("Rain", 0.0F),
			world.getFloat("Thunder", 0.0F),
			world.getInt("CenterChunkX", 0),
			world.getInt("CenterChunkZ", 0),
			world.getInt("Radius", WorldSnapshot.MAX_RADIUS),
			List.copyOf(chunks)
		);
	}

	private static void readTrack(NbtCompound track, DynamicRegistryManager registries, List<List<EntitySample>> samples) throws IOException {
		int entityId = track.getInt("EntityId", 0);
		EntityType<?> type = Registries.ENTITY_TYPE.get(Identifier.of(track.getString("Type", "minecraft:pig")));
		UUID uuid = UUID.fromString(track.getString("UUID", new UUID(0L, 0L).toString()));
		String name = track.getString("Name", "");
		GameProfile profile = name.isEmpty() ? null : new GameProfile(uuid, name);
		boolean local = track.getBoolean("Local", false);

		int[] ticks = track.getIntArray("Ticks").orElse(new int[0]);
		long[] pos = track.getLongArray("Pos").orElse(new long[0]);
		int[] rot = track.getIntArray("Rot").orElse(new int[0]);
		int[] vel = track.getIntArray("Vel").orElse(new int[0]);
		int[] vehicle = track.getIntArray("Vehicle").orElse(new int[0]);
		int[] state = track.getIntArray("State").orElse(new int[0]);
		int[] eye = track.getIntArray("Eye").orElse(new int[0]);
		int count = ticks.length;
		if (pos.length != count * 3 || rot.length != count * 4 || vel.length != count * 3 || vehicle.length != count || state.length != count || eye.length != count) {
			throw new IOException("entity track " + entityId + " has inconsistent array lengths");
		}

		// Looks are stored in the order they came into effect.
		NbtList looks = track.getListOrEmpty("Looks");
		int nextLook = 0;
		EntityAppearance appearance = null;
		for (int i = 0; i < count; i++) {
			int tick = ticks[i];
			while (nextLook < looks.size() && looks.getCompoundOrEmpty(nextLook).getInt("Tick", 0) <= tick) {
				appearance = readLook(looks.getCompoundOrEmpty(nextLook), type, uuid, profile, local, registries);
				nextLook++;
			}

			if (appearance == null || tick < 0 || tick >= samples.size()) {
				continue;
			}

			int packed = state[i];
			int flags = packed >>> 24;
			samples.get(tick).add(new EntitySample(
				entityId,
				Double.longBitsToDouble(pos[i * 3]), Double.longBitsToDouble(pos[i * 3 + 1]), Double.longBitsToDouble(pos[i * 3 + 2]),
				Float.intBitsToFloat(rot[i * 4]), Float.intBitsToFloat(rot[i * 4 + 1]), Float.intBitsToFloat(rot[i * 4 + 2]), Float.intBitsToFloat(rot[i * 4 + 3]),
				Float.intBitsToFloat(vel[i * 3]), Float.intBitsToFloat(vel[i * 3 + 1]), Float.intBitsToFloat(vel[i * 3 + 2]),
				vehicle[i],
				(flags & ReplayFileWriter.FLAG_ON_GROUND) != 0,
				Float.intBitsToFloat(eye[i]),
				packed & 0xFF,
				packed >> 8 & 0xFF,
				(flags & ReplayFileWriter.FLAG_HAND_SWINGING) != 0,
				(byte) (packed >> 16 & 0xFF),
				(flags & ReplayFileWriter.FLAG_OFF_HAND) != 0,
				appearance
			));
		}
	}

	private static EntityAppearance readLook(NbtCompound look, EntityType<?> type, UUID uuid, @Nullable GameProfile profile, boolean local, DynamicRegistryManager registries) {
		List<DataTracker.SerializedEntry<?>> tracked = look.getByteArray("Tracked")
			.map(bytes -> decode(EntityTrackerUpdateS2CPacket.CODEC, bytes, registries).trackedValues())
			.orElse(List.of());
		EntitySpawnS2CPacket spawn = look.getByteArray("Spawn").map(bytes -> decode(EntitySpawnS2CPacket.CODEC, bytes, registries)).orElse(null);

		ItemStack[] equipment = null;
		if (look.contains("Equipment")) {
			EquipmentSlot[] slots = EquipmentSlot.values();
			equipment = new ItemStack[slots.length];
			for (int i = 0; i < slots.length; i++) {
				equipment[i] = ItemStack.EMPTY;
			}

			NbtList list = look.getListOrEmpty("Equipment");
			for (int i = 0; i < list.size(); i++) {
				NbtCompound entry = list.getCompoundOrEmpty(i);
				EquipmentSlot slot = EquipmentSlot.CODEC.byId(entry.getString("Slot", ""));
				ItemStack stack = ItemStack.CODEC.parse(registries.getOps(NbtOps.INSTANCE), entry.getCompoundOrEmpty("Item")).result().orElse(ItemStack.EMPTY);
				if (slot != null) {
					equipment[slot.ordinal()] = stack;
				}
			}
		}

		return new EntityAppearance(type, uuid, profile, local, spawn, List.copyOf(tracked), equipment);
	}

	@Nullable
	private static RecordedEvent readEvent(NbtCompound nbt, DynamicRegistryManager registries) {
		String type = nbt.getString("Type", "");
		byte[] packet = nbt.getByteArray("Packet").orElse(null);
		return switch (type) {
			case "particle" -> packet == null ? null : new RecordedEvent.Particle(decode(ParticleS2CPacket.CODEC, packet, registries));
			case "sound" -> packet == null ? null : new RecordedEvent.Sound(decode(PlaySoundS2CPacket.CODEC, packet, registries));
			case "entity_sound" -> packet == null ? null : new RecordedEvent.EntitySound(decode(PlaySoundFromEntityS2CPacket.CODEC, packet, registries));
			case "world_event" -> packet == null ? null : new RecordedEvent.WorldEvent(decode(WorldEventS2CPacket.CODEC, packet, registries));
			case "explosion" -> packet == null ? null : new RecordedEvent.Explosion(decode(ExplosionS2CPacket.CODEC, packet, registries));
			case "entity_damage" -> packet == null ? null : new RecordedEvent.EntityDamage(decode(EntityDamageS2CPacket.CODEC, packet, registries));
			case "block_breaking" -> packet == null ? null : new RecordedEvent.BlockBreaking(decode(BlockBreakingProgressS2CPacket.CODEC, packet, registries));
			case "entity_status" -> new RecordedEvent.EntityStatus(nbt.getInt("EntityId", 0), nbt.getByte("Status", (byte) 0));
			case "entity_animation" -> new RecordedEvent.EntityAnimation(nbt.getInt("EntityId", 0), nbt.getInt("Animation", 0));
			default -> null;
		};
	}

	private static <T> T decode(PacketCodec<? super RegistryByteBuf, T> codec, byte[] bytes, DynamicRegistryManager registries) {
		return codec.decode(new RegistryByteBuf(Unpooled.wrappedBuffer(bytes), registries));
	}
}
