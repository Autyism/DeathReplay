package io.github.autyism.deathreplay.waypoint;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
	public static void render(GuiGraphics context, Minecraft client, ResourceKey<Level> dimension) {
		Camera camera = client.gameRenderer.getMainCamera();
		if (!camera.isInitialized()) {
			return;
		}

		int width = context.guiWidth();
		int height = context.guiHeight();
		Font textRenderer = client.font;
		for (Waypoints.Marker marker : Waypoints.inDimension(client, dimension)) {
			Vec3 screen = screenPos(client, marker, width, height);
			if (screen == null) {
				continue;
			}

			int x = (int) screen.x;
			int y = (int) screen.y;
			int distance = (int) Math.round(marker.center().distanceTo(camera.position()));
			String label = marker.name + "  " + distance + "m";
			int half = textRenderer.width(label) / 2;
			context.fill(x - 2, y - 2, x + 2, y + 2, DOT_COLOR);
			context.fill(x - half - 3, y + 5, x + half + 3, y + 17, BACKGROUND);
			context.drawCenteredString(textRenderer, label, x, y + 7, TEXT_COLOR);
		}
	}

	/**
	 * Where on a {@code width} x {@code height} screen the marker appears (x, y in pixels of the
	 * scaled GUI), or {@code null} if it is behind the camera or outside the view.
	 */
	@Nullable
	public static Vec3 screenPos(Minecraft client, Waypoints.Marker marker, int width, int height) {
		Camera camera = client.gameRenderer.getMainCamera();
		Vec3 toMarker = marker.center().subtract(camera.position());
		if (toMarker.dot(new Vec3(camera.forwardVector())) <= 0.05) {
			return null;
		}

		// Normalised device coordinates: x and y in -1..1 across the screen, y pointing up.
		Vec3 projected = client.gameRenderer.projectPointToScreen(marker.center());
		if (Math.abs(projected.x) > 1.0 || Math.abs(projected.y) > 1.0) {
			return null;
		}

		return new Vec3((projected.x * 0.5 + 0.5) * width, (0.5 - projected.y * 0.5) * height, 0.0);
	}
}
