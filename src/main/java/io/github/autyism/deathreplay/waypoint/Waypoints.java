package io.github.autyism.deathreplay.waypoint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.github.autyism.deathreplay.DeathReplayClient;
import io.github.autyism.deathreplay.camera.DetachedCamera;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import org.jetbrains.annotations.Nullable;

/**
 * Markers the player drops from the free camera ("that is where my items are").
 *
 * <p>They exist only on this client: a list in a file, shown as a label in the world and as a
 * dot on vanilla's locator bar (the direction bar that replaces the experience bar). The
 * locator bar normally shows waypoints the server sends; these are simply added to the
 * client's own copy of that list. Nothing about them is sent to the server.
 *
 * <p>Markers are kept per world: per server address, or per save in single player.
 */
public final class Waypoints {
	/** How far the free camera's line of sight is followed to find the block to mark. */
	private static final double REACH = 256.0;
	private static final int COLOR = 0xFF5555;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type FILE_TYPE = new TypeToken<Map<String, List<Marker>>>() {
	}.getType();

	/** One marker. A plain class with public fields so it maps directly onto the JSON file. */
	public static final class Marker {
		public String id;
		public String name;
		public String dimension;
		public int x;
		public int y;
		public int z;
		public long createdMillis;

		public Vec3 center() {
			return new Vec3(this.x + 0.5, this.y + 0.5, this.z + 0.5);
		}

		public BlockPos pos() {
			return new BlockPos(this.x, this.y, this.z);
		}

		UUID uuid() {
			return UUID.fromString(this.id);
		}
	}

	@Nullable
	private static Map<String, List<Marker>> all;
	/** The locator bar entries this class has added, and the connection they were added to. */
	private static final Set<UUID> ON_LOCATOR_BAR = new HashSet<>();
	@Nullable
	private static ClientPacketListener locatorBarOwner;

	private Waypoints() {
	}

	// ---------------------------------------------------------------- queries

	/** Markers of the world the client is in (all dimensions). Empty when not in a world. */
	public static List<Marker> current(Minecraft client) {
		String key = worldKey(client);
		return key == null ? List.of() : List.copyOf(load().getOrDefault(key, List.of()));
	}

	/** Markers of the current world that lie in {@code dimension}. */
	public static List<Marker> inDimension(Minecraft client, ResourceKey<Level> dimension) {
		String id = dimension.identifier().toString();
		return current(client).stream().filter(marker -> marker.dimension.equals(id)).toList();
	}

	/** Identifies "this world" across sessions: the server address, or the save name. */
	@Nullable
	private static String worldKey(Minecraft client) {
		if (client.level == null) {
			return null;
		}

		ServerData server = client.getCurrentServer();
		if (server != null) {
			return "server:" + server.ip;
		}

		if (client.getSingleplayerServer() != null) {
			return "save:" + client.getSingleplayerServer().getWorldData().getLevelName();
		}

		return "unknown";
	}

	// ---------------------------------------------------------------- changes

	/**
	 * Marks what the free camera is looking at: the spot in front of the first block in its
	 * line of sight, or the camera's own position if it looks at nothing within reach.
	 */
	@Nullable
	public static Marker addFromCamera(Minecraft client, Level world, DetachedCamera camera) {
		return addAlongRay(client, world, camera.getPos(), Vec3.directionFromRotation(camera.getPitch(), camera.getYaw()));
	}

	/**
	 * Marks what lies under a point of the screen. {@code screenX} / {@code screenY} run from -1
	 * (left / bottom) to 1 (right / top); 0, 0 is the centre, where the camera points.
	 */
	@Nullable
	public static Marker addFromCamera(Minecraft client, Level world, DetachedCamera camera, double screenX, double screenY) {
		Vec3 forward = Vec3.directionFromRotation(camera.getPitch(), camera.getYaw());
		Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
		Vec3 up = right.cross(forward);
		double halfHeight = Math.tan(Math.toRadians(client.options.fov().get()) / 2.0);
		double halfWidth = halfHeight * client.getWindow().getWidth() / client.getWindow().getHeight();
		Vec3 direction = forward.add(right.scale(screenX * halfWidth)).add(up.scale(screenY * halfHeight)).normalize();
		return addAlongRay(client, world, camera.getPos(), direction);
	}

	@Nullable
	private static Marker addAlongRay(Minecraft client, Level world, Vec3 from, Vec3 direction) {
		Vec3 to = from.add(direction.scale(REACH));
		BlockHitResult hit = world.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		BlockPos pos = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection()) : BlockPos.containing(from);
		return add(client, world.dimension(), pos);
	}

	@Nullable
	public static Marker add(Minecraft client, ResourceKey<Level> dimension, BlockPos pos) {
		String key = worldKey(client);
		if (key == null) {
			return null;
		}

		List<Marker> list = load().computeIfAbsent(key, k -> new ArrayList<>());
		Marker marker = new Marker();
		marker.id = UUID.randomUUID().toString();
		marker.name = Component.translatable("deathreplay.waypoint.name", nextNumber(list)).getString();
		marker.dimension = dimension.identifier().toString();
		marker.x = pos.getX();
		marker.y = pos.getY();
		marker.z = pos.getZ();
		marker.createdMillis = System.currentTimeMillis();
		list.add(marker);
		save();
		return marker;
	}

	/** The smallest positive number no existing marker of this world ends with. */
	private static int nextNumber(List<Marker> list) {
		for (int number = 1; ; number++) {
			String suffix = " " + number;
			if (list.stream().noneMatch(marker -> marker.name.endsWith(suffix))) {
				return number;
			}
		}
	}

	public static void remove(Minecraft client, Marker marker) {
		String key = worldKey(client);
		if (key != null && load().getOrDefault(key, new ArrayList<>()).removeIf(other -> other.id.equals(marker.id))) {
			save();
		}
	}

	public static void clear(Minecraft client) {
		String key = worldKey(client);
		if (key != null && load().remove(key) != null) {
			save();
		}
	}

	// ---------------------------------------------------------------- locator bar

	/**
	 * Called every client tick: keeps the locator bar showing exactly the markers of the
	 * dimension the player is in.
	 */
	public static void tick(Minecraft client) {
		ClientPacketListener handler = client.getConnection();
		if (handler != locatorBarOwner) {
			// New connection: its locator bar starts empty.
			ON_LOCATOR_BAR.clear();
			locatorBarOwner = handler;
		}

		if (handler == null || client.level == null) {
			return;
		}

		Set<UUID> wanted = new HashSet<>();
		for (Marker marker : inDimension(client, client.level.dimension())) {
			UUID uuid = marker.uuid();
			wanted.add(uuid);
			if (ON_LOCATOR_BAR.add(uuid)) {
				Waypoint.Icon config = new Waypoint.Icon();
				config.color = Optional.of(COLOR);
				handler.getWaypointManager().trackWaypoint(TrackedWaypoint.setPosition(uuid, config, new Vec3i(marker.x, marker.y, marker.z)));
			}
		}

		ON_LOCATOR_BAR.removeIf(uuid -> {
			if (wanted.contains(uuid)) {
				return false;
			}

			handler.getWaypointManager().untrackWaypoint(TrackedWaypoint.empty(uuid));
			return true;
		});
	}

	// ---------------------------------------------------------------- file

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(DeathReplayClient.NAMESPACE + "-waypoints.json");
	}

	private static Map<String, List<Marker>> load() {
		if (all != null) {
			return all;
		}

		all = new HashMap<>();
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				Map<String, List<Marker>> read = GSON.fromJson(reader, FILE_TYPE);
				if (read != null) {
					read.forEach((key, list) -> {
						List<Marker> valid = new ArrayList<>();
						for (Marker marker : list) {
							if (marker != null && marker.id != null && marker.name != null && marker.dimension != null) {
								valid.add(marker);
							}
						}

						all.put(key, valid);
					});
				}
			} catch (IOException | JsonParseException e) {
				DeathReplayClient.LOGGER.warn("Could not read {}, starting without markers", path, e);
			}
		}

		return all;
	}

	private static void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(all, FILE_TYPE, writer);
			}
		} catch (IOException e) {
			DeathReplayClient.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
