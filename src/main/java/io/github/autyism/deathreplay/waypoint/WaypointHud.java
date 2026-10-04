package io.github.autyism.deathreplay.waypoint;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Draws each marker as a small label ("name  distance") at the place on screen where the
 * marked spot is, whenever that spot is in view. Direction to markers that are not in view is
 * what the locator bar is for.
 */
public final class WaypointHud {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int BACKGROUND = 0x90000000;
	private static final int DOT_COLOR = 0xFFFF5555;

	private WaypointHud() {
	}

	/** Draws the labels of the markers that lie in {@code dimension}. */
	public static void render(DrawContext context, MinecraftClient client, RegistryKey<World> dimension) {
		Camera camera = client.gameRenderer.getCamera();
		if (!camera.isReady()) {
			return;
		}

		int width = context.getScaledWindowWidth();
		int height = context.getScaledWindowHeight();
		TextRenderer textRenderer = client.textRenderer;
		for (Waypoints.Marker marker : Waypoints.inDimension(client, dimension)) {
			Vec3d screen = screenPos(client, marker, width, height);
			if (screen == null) {
				continue;
			}

			int x = (int) screen.x;
			int y = (int) screen.y;
			int distance = (int) Math.round(marker.center().distanceTo(camera.getCameraPos()));
			String label = marker.name + "  " + distance + "m";
			int half = textRenderer.getWidth(label) / 2;
			context.fill(x - 2, y - 2, x + 2, y + 2, DOT_COLOR);
			context.fill(x - half - 3, y + 5, x + half + 3, y + 17, BACKGROUND);
			context.drawCenteredTextWithShadow(textRenderer, label, x, y + 7, TEXT_COLOR);
		}
	}

	/**
	 * Where on a {@code width} x {@code height} screen the marker appears (x, y in pixels of the
	 * scaled GUI), or {@code null} if it is behind the camera or outside the view.
	 */
	@Nullable
	public static Vec3d screenPos(MinecraftClient client, Waypoints.Marker marker, int width, int height) {
		Camera camera = client.gameRenderer.getCamera();
		Vec3d toMarker = marker.center().subtract(camera.getCameraPos());
		if (toMarker.dotProduct(new Vec3d(camera.getHorizontalPlane())) <= 0.05) {
			return null;
		}

		// Normalised device coordinates: x and y in -1..1 across the screen, y pointing up.
		Vec3d projected = client.gameRenderer.project(marker.center());
		if (Math.abs(projected.x) > 1.0 || Math.abs(projected.y) > 1.0) {
			return null;
		}

		return new Vec3d((projected.x * 0.5 + 0.5) * width, (0.5 - projected.y * 0.5) * height, 0.0);
	}
}
