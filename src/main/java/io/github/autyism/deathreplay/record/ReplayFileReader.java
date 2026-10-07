package io.github.autyism.deathreplay.record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
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

	public static Recording read(Path file, RegistryAccess registries) throws IOException {
		try {
			CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			int format = root.getIntOr("FormatVersion", 0);
			if (format != ReplayFileWriter.FORMAT_VERSION) {
				throw new IOException("replay file format " + format + " is not supported (expected " + ReplayFileWriter.FORMAT_VERSION + ")");
			}

			String version = root.getStringOr("MinecraftVersion", "");
			if (!version.equals(SharedConstants.getCurrentVersion().name())) {
				throw new IOException("replay was saved by Minecraft " + version + ", this is " + SharedConstants.getCurrentVersion().name());
			}

			return parse(root, registries);
		} catch (RuntimeException e) {
			// Decoders throw all kinds of unchecked exceptions on data they do not understand.
			throw new IOException("replay file could not be decoded: " + e, e);
		}
	}

	private static Recording parse(CompoundTag root, RegistryAccess registries) throws IOException {
		int tickCount = root.getIntOr("TickCount", 0);
		if (tickCount <= 0) {
			throw new IOException("replay file has no ticks");
		}

		List<List<EntitySample>> samples = new ArrayList<>(tickCount);
		List<List<RecordedEvent>> events = new ArrayList<>(tickCount);
		for (int i = 0; i < tickCount; i++) {
			samples.add(new ArrayList<>());
			events.add(new ArrayList<>());
		}

		ListTag entities = root.getListOrEmpty("Entities");
		for (int i = 0; i < entities.size(); i++) {
			readTrack(entities.getCompoundOrEmpty(i), registries, samples);
		}

		ListTag blockChanges = root.getListOrEmpty("BlockChanges");
		for (int i = 0; i < blockChanges.size(); i++) {
			CompoundTag nbt = blockChanges.getCompoundOrEmpty(i);
			int tick = nbt.getIntOr("Tick", -1);
			if (tick >= 0 && tick < tickCount) {
				BlockPos pos = new BlockPos(nbt.getIntOr("X", 0), nbt.getIntOr("Y", 0), nbt.getIntOr("Z", 0));
				BlockState oldState = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), nbt.getCompoundOrEmpty("Old"));
				BlockState newState = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), nbt.getCompoundOrEmpty("New"));
				events.get(tick).add(new RecordedEvent.BlockChange(pos, oldState, newState));
			}
		}

		ListTag otherEvents = root.getListOrEmpty("Events");
		for (int i = 0; i < otherEvents.size(); i++) {
			CompoundTag nbt = otherEvents.getCompoundOrEmpty(i);
			int tick = nbt.getIntOr("Tick", -1);
			RecordedEvent event = readEvent(nbt, registries);
			if (event != null && tick >= 0 && tick < tickCount) {
				events.get(tick).add(event);
			}
		}

		List<Frame> frames = new ArrayList<>(tickCount);
		for (int i = 0; i < tickCount; i++) {
			frames.add(new Frame(samples.get(i).toArray(EntitySample[]::new), events.get(i).toArray(RecordedEvent[]::new)));
		}

		CompoundTag player = root.getCompoundOrEmpty("Player");
		ListTag deathPos = root.getListOrEmpty("DeathPos");
		String deathMessage = root.getStringOr("DeathMessage", "");
		return new Recording(
			List.copyOf(frames),
			Math.max(0, Math.min(tickCount - 1, root.getIntOr("DeathFrame", tickCount - 1))),
			player.getIntOr("EntityId", 0),
			player.getStringOr("Name", ""),
			UUID.fromString(player.getStringOr("UUID", new UUID(0L, 0L).toString())),
			readSnapshot(root, registries),
			new Vec3(deathPos.getDoubleOr(0, 0.0), deathPos.getDoubleOr(1, 0.0), deathPos.getDoubleOr(2, 0.0)),
			deathMessage.isEmpty() ? null : Component.literal(deathMessage),
			root.getLongOr("DeathTimeMillis", 0L)
		);
	}

	private static WorldSnapshot readSnapshot(CompoundTag root, RegistryAccess registries) throws IOException {
		CompoundTag world = root.getCompoundOrEmpty("World");
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(root.getStringOr("Dimension", "minecraft:overworld")));
		Identifier typeId = Identifier.parse(world.getStringOr("DimensionType", "minecraft:overworld"));
		Holder<DimensionType> dimensionType = registries.lookupOrThrow(Registries.DIMENSION_TYPE)
			.get(ResourceKey.create(Registries.DIMENSION_TYPE, typeId))
			.orElseThrow(() -> new IOException("this server has no dimension type " + typeId));

		List<ClientboundLevelChunkWithLightPacket> chunks = new ArrayList<>();
		ListTag chunkList = root.getListOrEmpty("Chunks");
		for (Tag element : chunkList) {
			byte[] bytes = element.asByteArray().orElseThrow(() -> new IOException("malformed chunk entry"));
			chunks.add(decode(ClientboundLevelChunkWithLightPacket.STREAM_CODEC, bytes, registries));
		}

		return new WorldSnapshot(
			dimension,
			dimensionType,
			world.getLongOr("BiomeSeed", 0L),
			world.getIntOr("SeaLevel", 63),
			world.getBooleanOr("Flat", false),
			world.getLongOr("Time", 0L),
			world.getLongOr("TimeOfDay", 6000L),
			//? if >=26.1
			/*readClocks(world.getCompoundOrEmpty("Clocks")),*/
			world.getFloatOr("Rain", 0.0F),
			world.getFloatOr("Thunder", 0.0F),
			world.getIntOr("CenterChunkX", 0),
			world.getIntOr("CenterChunkZ", 0),
			world.getIntOr("Radius", WorldSnapshot.MAX_RADIUS),
			List.copyOf(chunks)
		);
	}

	//? if >=26.1 {
	/*private static java.util.Map<Identifier, Long> readClocks(CompoundTag clocks) {
		java.util.Map<Identifier, Long> read = new java.util.HashMap<>();
		for (String id : clocks.keySet()) {
			read.put(Identifier.parse(id), clocks.getLongOr(id, 0L));
		}

		return read;
	}
	*///?}

	private static void readTrack(CompoundTag track, RegistryAccess registries, List<List<EntitySample>> samples) throws IOException {
		int entityId = track.getIntOr("EntityId", 0);
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(track.getStringOr("Type", "minecraft:pig")));
		UUID uuid = UUID.fromString(track.getStringOr("UUID", new UUID(0L, 0L).toString()));
		String name = track.getStringOr("Name", "");
		GameProfile profile = name.isEmpty() ? null : new GameProfile(uuid, name);
		boolean local = track.getBooleanOr("Local", false);

		int[] ticks = track.getIntArray("Ticks").orElse(new int[0]);
		long[] pos = track.getLongArray("Pos").orElse(new long[0]);
		int[] rot = track.getIntArray("Rot").orElse(new int[0]);
		int[] vel = track.getIntArray("Vel").orElse(new int[0]);
		int[] vehicle = track.getIntArray("Vehicle").orElse(new int[0]);
		int[] state = track.getIntArray("State").orElse(new int[0]);
		int[] eye = track.getIntArray("Eye").orElse(new int[0]);
		//? if >=26.3
		/*int[] swing = track.getIntArray("Swing").orElse(new int[0]);*/
		int count = ticks.length;
		if (pos.length != count * 3 || rot.length != count * 4 || vel.length != count * 3 || vehicle.length != count || state.length != count || eye.length != count) {
			throw new IOException("entity track " + entityId + " has inconsistent array lengths");
		}

		// Looks are stored in the order they came into effect.
		ListTag looks = track.getListOrEmpty("Looks");
		int nextLook = 0;
		EntityAppearance appearance = null;
		for (int i = 0; i < count; i++) {
			int tick = ticks[i];
			while (nextLook < looks.size() && looks.getCompoundOrEmpty(nextLook).getIntOr("Tick", 0) <= tick) {
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
				//? if >=26.3
				/*swingAnimation(swing, i),*/
				appearance
			));
		}
	}

	//? if >=26.3 {
	/*@Nullable
	private static net.minecraft.world.item.component.SwingAnimation swingAnimation(int[] swing, int index) {
		net.minecraft.world.item.SwingAnimationType[] types = net.minecraft.world.item.SwingAnimationType.values();
		if (index >= swing.length || swing[index] < 0 || swing[index] >>> 16 >= types.length) {
			return null;
		}

		return new net.minecraft.world.item.component.SwingAnimation(types[swing[index] >>> 16], swing[index] & 0xFFFF);
	}

	*///?}
	private static EntityAppearance readLook(CompoundTag look, EntityType<?> type, UUID uuid, @Nullable GameProfile profile, boolean local, RegistryAccess registries) {
		List<SynchedEntityData.DataValue<?>> tracked = look.getByteArray("Tracked")
			.map(bytes -> decode(ClientboundSetEntityDataPacket.STREAM_CODEC, bytes, registries).packedItems())
			.orElse(List.of());
		ClientboundAddEntityPacket spawn = look.getByteArray("Spawn").map(bytes -> decode(ClientboundAddEntityPacket.STREAM_CODEC, bytes, registries)).orElse(null);

		ItemStack[] equipment = null;
		if (look.contains("Equipment")) {
			EquipmentSlot[] slots = EquipmentSlot.values();
			equipment = new ItemStack[slots.length];
			for (int i = 0; i < slots.length; i++) {
				equipment[i] = ItemStack.EMPTY;
			}

			ListTag list = look.getListOrEmpty("Equipment");
			for (int i = 0; i < list.size(); i++) {
				CompoundTag entry = list.getCompoundOrEmpty(i);
				EquipmentSlot slot = EquipmentSlot.CODEC.byName(entry.getStringOr("Slot", ""));
				ItemStack stack = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), entry.getCompoundOrEmpty("Item")).result().orElse(ItemStack.EMPTY);
				if (slot != null) {
					equipment[slot.ordinal()] = stack;
				}
			}
		}

		return new EntityAppearance(type, uuid, profile, local, spawn, List.copyOf(tracked), equipment);
	}

	@Nullable
	private static RecordedEvent readEvent(CompoundTag nbt, RegistryAccess registries) {
		String type = nbt.getStringOr("Type", "");
		byte[] packet = nbt.getByteArray("Packet").orElse(null);
		return switch (type) {
			case "particle" -> packet == null ? null : new RecordedEvent.Particle(decode(ClientboundLevelParticlesPacket.STREAM_CODEC, packet, registries));
			case "sound" -> packet == null ? null : new RecordedEvent.Sound(decode(ClientboundSoundPacket.STREAM_CODEC, packet, registries));
			case "entity_sound" -> packet == null ? null : new RecordedEvent.EntitySound(decode(ClientboundSoundEntityPacket.STREAM_CODEC, packet, registries));
			case "world_event" -> packet == null ? null : new RecordedEvent.WorldEvent(decode(ClientboundLevelEventPacket.STREAM_CODEC, packet, registries));
			case "explosion" -> packet == null ? null : new RecordedEvent.Explosion(decode(ClientboundExplodePacket.STREAM_CODEC, packet, registries));
			case "entity_damage" -> packet == null ? null : new RecordedEvent.EntityDamage(decode(ClientboundDamageEventPacket.STREAM_CODEC, packet, registries));
			case "block_breaking" -> packet == null ? null : new RecordedEvent.BlockBreaking(decode(ClientboundBlockDestructionPacket.STREAM_CODEC, packet, registries));
			case "entity_status" -> new RecordedEvent.EntityStatus(nbt.getIntOr("EntityId", 0), nbt.getByteOr("Status", (byte) 0));
			case "entity_animation" -> new RecordedEvent.EntityAnimation(nbt.getIntOr("EntityId", 0), nbt.getIntOr("Animation", 0));
			default -> null;
		};
	}

	private static <T> T decode(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, byte[] bytes, RegistryAccess registries) {
		return codec.decode(new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries));
	}
}
