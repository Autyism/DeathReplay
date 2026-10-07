package io.github.autyism.deathreplay.record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.autyism.deathreplay.DeathReplayClient;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
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
import net.minecraft.util.Util;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Writes a {@link Recording} to {@code <game dir>/deathreplay/death_<time>.nbt}
 * (gzip-compressed NBT, readable with any NBT editor).
 *
 * <p>Layout, format version 2 (version 1 had no terrain and cannot be played):
 * <pre>
 * FormatVersion, ModVersion, MinecraftVersion, DataVersion
 * DeathTimeMillis, Dimension, DeathPos[3], DeathMessage, DeathFrame, TickCount
 * Player { Name, UUID, EntityId }
 * World { DimensionType, BiomeSeed, SeaLevel, Flat, Time, TimeOfDay, Rain, Thunder,
 *         CenterChunkX, CenterChunkZ, Radius }
 * Chunks [ byte[] ]                   the terrain around the death, one chunk packet each
 * Entities [ { EntityId, Type, UUID, Name?, Local,
 *              Ticks int[]            frame index of each sample
 *              Pos   long[3n]         x, y, z as raw double bits
 *              Rot   int[4n]          yaw, pitch, head yaw, body yaw as raw float bits
 *              Vel   int[3n]          velocity as raw float bits
 *              Vehicle int[n]         vehicle entity id or Integer.MIN_VALUE
 *              Eye   int[n]           eye height as raw float bits
 *              State int[n]           hurtTime | deathTime&lt;&lt;8 | swingTicks&lt;&lt;16 | flags&lt;&lt;24
 *                                     (flags: 1 on ground, 2 hand swinging, 4 off hand)
 *              Looks [ { Tick, Tracked byte[], Spawn byte[]?, Equipment [ { Slot, Item } ] } ] } ]
 * BlockChanges [ { Tick, X, Y, Z, Old, New } ]
 * Events [ { Tick, Type, Packet byte[] | EntityId + Status | EntityId + Animation } ]
 * </pre>
 * Packet byte arrays are the vanilla network encoding of the packet the client received
 * (for chunks: of the chunk packet built from the client's own copy of the chunk).
 * {@link ReplayFileReader} reads this back.
 */
public final class ReplayFileWriter {
	public static final int FORMAT_VERSION = 2;
	public static final String DIRECTORY = "deathreplay";
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
	static final int FLAG_ON_GROUND = 1;
	static final int FLAG_HAND_SWINGING = 2;
	static final int FLAG_OFF_HAND = 4;

	@Nullable
	private static volatile Path lastSavedFile;
	@Nullable
	private static volatile Recording lastSavedRecording;
	@Nullable
	private static volatile Recording savingRecording;

	private ReplayFileWriter() {
	}

	/** The file written by the most recent successful save in this session. */
	@Nullable
	public static Path getLastSavedFile() {
		return lastSavedFile;
	}

	public static boolean isSaved(Recording recording) {
		return lastSavedRecording == recording;
	}

	public static boolean isSaving(Recording recording) {
		return savingRecording == recording;
	}

	public static Path directory(Minecraft client) {
		return client.gameDirectory.toPath().resolve(DIRECTORY);
	}

	/**
	 * Builds the file contents on the calling (render) thread, because that needs the game's
	 * registries, then compresses and writes on the IO pool. Completes with the file path, or
	 * with {@code null} if anything went wrong (the reason is logged).
	 */
	public static CompletableFuture<@Nullable Path> saveAsync(Minecraft client, Recording recording, RegistryAccess registries) {
		CompoundTag root;
		try {
			root = toNbt(recording, registries);
		} catch (RuntimeException e) {
			DeathReplayClient.LOGGER.error("Could not serialize the death recording", e);
			return CompletableFuture.completedFuture(null);
		}

		savingRecording = recording;
		Path directory = directory(client);
		String baseName = "death_" + FILE_TIME.format(Instant.ofEpochMilli(recording.deathTimeMillis()));
		return CompletableFuture.supplyAsync(() -> {
			try {
				Files.createDirectories(directory);
				Path file = directory.resolve(baseName + ".nbt");
				for (int i = 2; Files.exists(file); i++) {
					file = directory.resolve(baseName + "_" + i + ".nbt");
				}

				NbtIo.writeCompressed(root, file);
				lastSavedFile = file;
				lastSavedRecording = recording;
				DeathReplayClient.LOGGER.info("Saved death recording to {} ({} bytes)", file, Files.size(file));
				return file;
			} catch (IOException e) {
				DeathReplayClient.LOGGER.error("Could not save the death recording to {}", directory, e);
				return null;
			} finally {
				savingRecording = null;
			}
		}, Util.ioPool());
	}

	/** Saves {@code recording} and tells the player in chat how it went. For the Save buttons. */
	public static void saveAndAnnounce(Minecraft client, Recording recording) {
		if (client.level == null) {
			return;
		}

		saveAsync(client, recording, client.level.registryAccess()).thenAcceptAsync(file -> {
			if (client.player != null) {
				client.player.displayClientMessage(file != null
					? Component.translatable("deathreplay.message.saved", file.getFileName().toString())
					: Component.translatable("deathreplay.message.save_failed"), false);
			}
		}, client);
	}

	// ---------------------------------------------------------------- serialization

	private static CompoundTag toNbt(Recording recording, RegistryAccess registries) {
		CompoundTag root = new CompoundTag();
		root.putInt("FormatVersion", FORMAT_VERSION);
		root.putString("ModVersion", FabricLoader.getInstance().getModContainer(DeathReplayClient.MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
		root.putString("MinecraftVersion", SharedConstants.getCurrentVersion().name());
		root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		root.putLong("DeathTimeMillis", recording.deathTimeMillis());
		root.putString("Dimension", recording.dimension().identifier().toString());
		ListTag deathPos = new ListTag();
		deathPos.add(DoubleTag.valueOf(recording.deathPos().x));
		deathPos.add(DoubleTag.valueOf(recording.deathPos().y));
		deathPos.add(DoubleTag.valueOf(recording.deathPos().z));
		root.put("DeathPos", deathPos);
		root.putString("DeathMessage", recording.deathMessage() == null ? "" : recording.deathMessage().getString());
		root.putInt("DeathFrame", recording.deathFrame());
		root.putInt("TickCount", recording.tickCount());

		CompoundTag player = new CompoundTag();
		player.putString("Name", recording.playerName());
		player.putString("UUID", recording.playerUuid().toString());
		player.putInt("EntityId", recording.playerEntityId());
		root.put("Player", player);

		WorldSnapshot snapshot = recording.snapshot();
		CompoundTag world = new CompoundTag();
		world.putString("DimensionType", snapshot.dimensionType().unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:overworld"));
		world.putLong("BiomeSeed", snapshot.biomeSeed());
		world.putInt("SeaLevel", snapshot.seaLevel());
		world.putBoolean("Flat", snapshot.flat());
		world.putLong("Time", snapshot.time());
		world.putLong("TimeOfDay", snapshot.timeOfDay());
		world.putFloat("Rain", snapshot.rainGradient());
		world.putFloat("Thunder", snapshot.thunderGradient());
		world.putInt("CenterChunkX", snapshot.centerChunkX());
		world.putInt("CenterChunkZ", snapshot.centerChunkZ());
		world.putInt("Radius", snapshot.radius());
		root.put("World", world);

		ListTag chunks = new ListTag();
		for (ClientboundLevelChunkWithLightPacket chunk : snapshot.chunks()) {
			byte[] bytes = encode(ClientboundLevelChunkWithLightPacket.STREAM_CODEC, chunk, registries);
			if (bytes != null) {
				chunks.add(new ByteArrayTag(bytes));
			}
		}

		root.put("Chunks", chunks);

		Map<Integer, Track> tracks = new LinkedHashMap<>();
		ListTag blockChanges = new ListTag();
		ListTag events = new ListTag();
		List<Frame> frames = recording.frames();
		for (int tick = 0; tick < frames.size(); tick++) {
			Frame frame = frames.get(tick);
			for (EntitySample sample : frame.entities()) {
				tracks.computeIfAbsent(sample.entityId, id -> new Track()).add(tick, sample, registries);
			}

			for (RecordedEvent event : frame.events()) {
				if (event instanceof RecordedEvent.BlockChange change) {
					CompoundTag nbt = new CompoundTag();
					nbt.putInt("Tick", tick);
					nbt.putInt("X", change.pos().getX());
					nbt.putInt("Y", change.pos().getY());
					nbt.putInt("Z", change.pos().getZ());
					nbt.put("Old", NbtUtils.writeBlockState(change.oldState()));
					nbt.put("New", NbtUtils.writeBlockState(change.newState()));
					blockChanges.add(nbt);
				} else {
					CompoundTag nbt = eventToNbt(event, registries);
					nbt.putInt("Tick", tick);
					events.add(nbt);
				}
			}
		}

		ListTag entities = new ListTag();
		for (Track track : tracks.values()) {
			entities.add(track.toNbt());
		}

		root.put("Entities", entities);
		root.put("BlockChanges", blockChanges);
		root.put("Events", events);
		return root;
	}

	private static CompoundTag eventToNbt(RecordedEvent event, RegistryAccess registries) {
		CompoundTag nbt = new CompoundTag();
		switch (event) {
			case RecordedEvent.Particle e -> putPacket(nbt, "particle", ClientboundLevelParticlesPacket.STREAM_CODEC, e.packet(), registries);
			case RecordedEvent.Sound e -> putPacket(nbt, "sound", ClientboundSoundPacket.STREAM_CODEC, e.packet(), registries);
			case RecordedEvent.EntitySound e -> putPacket(nbt, "entity_sound", ClientboundSoundEntityPacket.STREAM_CODEC, e.packet(), registries);
			case RecordedEvent.WorldEvent e -> putPacket(nbt, "world_event", ClientboundLevelEventPacket.STREAM_CODEC, e.packet(), registries);
			case RecordedEvent.Explosion e -> putPacket(nbt, "explosion", ClientboundExplodePacket.STREAM_CODEC, e.packet(), registries);
			case RecordedEvent.EntityDamage e -> putPacket(nbt, "entity_damage", ClientboundDamageEventPacket.STREAM_CODEC, e.packet(), registries);
			case RecordedEvent.BlockBreaking e -> putPacket(nbt, "block_breaking", ClientboundBlockDestructionPacket.STREAM_CODEC, e.packet(), registries);
			case RecordedEvent.EntityStatus e -> {
				nbt.putString("Type", "entity_status");
				nbt.putInt("EntityId", e.entityId());
				nbt.putByte("Status", e.status());
			}
			case RecordedEvent.EntityAnimation e -> {
				nbt.putString("Type", "entity_animation");
				nbt.putInt("EntityId", e.entityId());
				nbt.putInt("Animation", e.animationId());
			}
			case RecordedEvent.BlockChange e -> throw new IllegalStateException("block changes are written separately");
		}

		return nbt;
	}

	private static <T> void putPacket(CompoundTag nbt, String type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T packet, RegistryAccess registries) {
		nbt.putString("Type", type);
		byte[] bytes = encode(codec, packet, registries);
		if (bytes != null) {
			nbt.putByteArray("Packet", bytes);
		}
	}

	/** Vanilla network encoding of {@code value}; {@code null} if the codec refuses it. */
	private static <T> byte @Nullable [] encode(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value, RegistryAccess registries) {
		ByteBuf raw = Unpooled.buffer();
		try {
			codec.encode(new RegistryFriendlyByteBuf(raw, registries), value);
			byte[] bytes = new byte[raw.readableBytes()];
			raw.readBytes(bytes);
			return bytes;
		} catch (RuntimeException e) {
			DeathReplayClient.LOGGER.debug("Could not encode {} for the replay file", value.getClass().getSimpleName(), e);
			return null;
		} finally {
			raw.release();
		}
	}

	/** All samples of one entity, turned into parallel arrays. */
	private static final class Track {
		private final IntArrayList ticks = new IntArrayList();
		private final LongArrayList pos = new LongArrayList();
		private final IntArrayList rot = new IntArrayList();
		private final IntArrayList vel = new IntArrayList();
		private final IntArrayList vehicle = new IntArrayList();
		private final IntArrayList eye = new IntArrayList();
		private final IntArrayList state = new IntArrayList();
		private final ListTag looks = new ListTag();
		@Nullable
		private EntitySample first;
		@Nullable
		private EntityAppearance lastLook;

		void add(int tick, EntitySample sample, RegistryAccess registries) {
			if (this.first == null) {
				this.first = sample;
			}

			this.ticks.add(tick);
			this.pos.add(Double.doubleToRawLongBits(sample.x));
			this.pos.add(Double.doubleToRawLongBits(sample.y));
			this.pos.add(Double.doubleToRawLongBits(sample.z));
			this.rot.add(Float.floatToRawIntBits(sample.yaw));
			this.rot.add(Float.floatToRawIntBits(sample.pitch));
			this.rot.add(Float.floatToRawIntBits(sample.headYaw));
			this.rot.add(Float.floatToRawIntBits(sample.bodyYaw));
			this.vel.add(Float.floatToRawIntBits(sample.velocityX));
			this.vel.add(Float.floatToRawIntBits(sample.velocityY));
			this.vel.add(Float.floatToRawIntBits(sample.velocityZ));
			this.vehicle.add(sample.vehicleId);
			this.eye.add(Float.floatToRawIntBits(sample.eyeHeight));
			int flags = (sample.onGround ? FLAG_ON_GROUND : 0) | (sample.handSwinging ? FLAG_HAND_SWINGING : 0) | (sample.offHandSwing ? FLAG_OFF_HAND : 0);
			this.state.add(sample.hurtTime & 0xFF | (sample.deathTime & 0xFF) << 8 | (sample.handSwingTicks & 0xFF) << 16 | flags << 24);

			if (sample.appearance != this.lastLook) {
				this.lastLook = sample.appearance;
				this.looks.add(lookToNbt(tick, sample.appearance, registries));
			}
		}

		CompoundTag toNbt() {
			EntityAppearance look = this.first.appearance;
			CompoundTag nbt = new CompoundTag();
			nbt.putInt("EntityId", this.first.entityId);
			nbt.putString("Type", BuiltInRegistries.ENTITY_TYPE.getKey(look.type()).toString());
			nbt.putString("UUID", look.uuid().toString());
			if (look.profile() != null) {
				nbt.putString("Name", look.profile().name());
			}

			nbt.putIntArray("Ticks", this.ticks.toIntArray());
			nbt.putLongArray("Pos", this.pos.toLongArray());
			nbt.putIntArray("Rot", this.rot.toIntArray());
			nbt.putIntArray("Vel", this.vel.toIntArray());
			nbt.putIntArray("Vehicle", this.vehicle.toIntArray());
			nbt.putIntArray("Eye", this.eye.toIntArray());
			nbt.putBoolean("Local", look.localPlayer());
			nbt.putIntArray("State", this.state.toIntArray());
			nbt.put("Looks", this.looks);
			return nbt;
		}

		private static CompoundTag lookToNbt(int tick, EntityAppearance look, RegistryAccess registries) {
			CompoundTag nbt = new CompoundTag();
			nbt.putInt("Tick", tick);
			byte[] tracked = encode(ClientboundSetEntityDataPacket.STREAM_CODEC, new ClientboundSetEntityDataPacket(0, look.trackedData()), registries);
			if (tracked != null) {
				nbt.putByteArray("Tracked", tracked);
			}

			if (look.spawnPacket() != null) {
				byte[] spawn = encode(ClientboundAddEntityPacket.STREAM_CODEC, look.spawnPacket(), registries);
				if (spawn != null) {
					nbt.putByteArray("Spawn", spawn);
				}
			}

			if (look.equipment() != null) {
				ListTag equipment = new ListTag();
				for (EquipmentSlot slot : EquipmentSlot.values()) {
					ItemStack stack = look.equipment()[slot.ordinal()];
					if (stack.isEmpty()) {
						continue;
					}

					ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).result().ifPresent(item -> {
						CompoundTag entry = new CompoundTag();
						entry.putString("Slot", slot.getSerializedName());
						entry.put("Item", item);
						equipment.add(entry);
					});
				}

				nbt.put("Equipment", equipment);
			}

			return nbt;
		}
	}
}
