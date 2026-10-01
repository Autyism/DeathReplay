package io.github.autyi6969.deathreplay.waypoint;

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
import io.github.autyi6969.deathreplay.DeathReplayClient;
import io.github.autyi6969.deathreplay.camera.DetachedCamera;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.waypoint.TrackedWaypoint;
import net.minecraft.world.waypoint.Waypoint;
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

		public Vec3d center() {
			return new Vec3d(this.x + 0.5, this.y + 0.5, this.z + 0.5);
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
	private static ClientPlayNetworkHandler locatorBarOwner;

	private Waypoints() {
	}

	// ---------------------------------------------------------------- queries

	/** Markers of the world the client is in (all dimensions). Empty when not in a world. */
	public static List<Marker> current(MinecraftClient client) {
		String key = worldKey(client);
		return key == null ? List.of() : List.copyOf(load().getOrDefault(key, List.of()));
	}

	/** Markers of the current world that lie in {@code dimension}. */
	public static List<Marker> inDimension(MinecraftClient client, RegistryKey<World> dimension) {
		String id = dimension.getValue().toString();
		return current(client).stream().filter(marker -> marker.dimension.equals(id)).toList();
	}

	/** Identifies "this world" across sessions: the server address, or the save name. */
	@Nullable
	private static String worldKey(MinecraftClient client) {
		if (client.world == null) {
			return null;
		}

		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) {
			return "server:" + server.address;
		}

		if (client.getServer() != null) {
			return "save:" + client.getServer().getSaveProperties().getLevelName();
		}

		return "unknown";
	}

	// ---------------------------------------------------------------- changes

	/**
	 * Marks what the free camera is looking at: the spot in front of the first block in its
	 * line of sight, or the camera's own position if it looks at nothing within reach.
	 */
	@Nullable
	public static Marker addFromCamera(MinecraftClient client, World world, DetachedCamera camera) {
		return addAlongRay(client, world, camera.getPos(), Vec3d.fromPolar(camera.getPitch(), camera.getYaw()));
	}

	/**
	 * Marks what lies under a point of the screen. {@code screenX} / {@code screenY} run from -1
	 * (left / bottom) to 1 (right / top); 0, 0 is the centre, where the camera points.
	 */
	@Nullable
	public static Marker addFromCamera(MinecraftClient client, World world, DetachedCamera camera, double screenX, double screenY) {
		Vec3d forward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
		Vec3d right = forward.crossProduct(new Vec3d(0.0, 1.0, 0.0)).normalize();
		Vec3d up = right.crossProduct(forward);
		double halfHeight = Math.tan(Math.toRadians(client.options.getFov().getValue()) / 2.0);
		double halfWidth = halfHeight * client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight();
		Vec3d direction = forward.add(right.multiply(screenX * halfWidth)).add(up.multiply(screenY * halfHeight)).normalize();
		return addAlongRay(client, world, camera.getPos(), direction);
	}

	@Nullable
	private static Marker addAlongRay(MinecraftClient client, World world, Vec3d from, Vec3d direction) {
		Vec3d to = from.add(direction.multiply(REACH));
		BlockHitResult hit = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
		BlockPos pos = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().offset(hit.getSide()) : BlockPos.ofFloored(from);
		return add(client, world.getRegistryKey(), pos);
	}

	@Nullable
	public static Marker add(MinecraftClient client, RegistryKey<World> dimension, BlockPos pos) {
		String key = worldKey(client);
		if (key == null) {
			return null;
		}

		List<Marker> list = load().computeIfAbsent(key, k -> new ArrayList<>());
		Marker marker = new Marker();
		marker.id = UUID.randomUUID().toString();
		marker.name = Text.translatable("deathreplay.waypoint.name", nextNumber(list)).getString();
		marker.dimension = dimension.getValue().toString();
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

	public static void remove(MinecraftClient client, Marker marker) {
		String key = worldKey(client);
		if (key != null && load().getOrDefault(key, new ArrayList<>()).removeIf(other -> other.id.equals(marker.id))) {
			save();
		}
	}

	public static void clear(MinecraftClient client) {
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
	public static void tick(MinecraftClient client) {
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler != locatorBarOwner) {
			// New connection: its locator bar starts empty.
			ON_LOCATOR_BAR.clear();
			locatorBarOwner = handler;
		}

		if (handler == null || client.world == null) {
			return;
		}

		Set<UUID> wanted = new HashSet<>();
		for (Marker marker : inDimension(client, client.world.getRegistryKey())) {
			UUID uuid = marker.uuid();
			wanted.add(uuid);
			if (ON_LOCATOR_BAR.add(uuid)) {
				Waypoint.Config config = new Waypoint.Config();
				config.color = Optional.of(COLOR);
				handler.getWaypointHandler().onTrack(TrackedWaypoint.ofPos(uuid, config, new Vec3i(marker.x, marker.y, marker.z)));
			}
		}

		ON_LOCATOR_BAR.removeIf(uuid -> {
			if (wanted.contains(uuid)) {
				return false;
			}

			handler.getWaypointHandler().onUntrack(TrackedWaypoint.empty(uuid));
			return true;
		});
	}

	// ---------------------------------------------------------------- file

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(DeathReplayClient.MOD_ID + "-waypoints.json");
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
