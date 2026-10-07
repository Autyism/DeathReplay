package io.github.autyism.deathreplay.waypoint;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The markers of the current world, with a button to remove each one.
 */
public class WaypointListScreen extends Screen {
	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int TEXT_COLOR = 0xFFE0E0E0;
	private static final int NOTE_COLOR = 0xFFC0C0C0;
	private static final int ROW_WIDTH = 300;
	private static final int ROW_HEIGHT = 22;
	private static final int ROWS_PER_PAGE = 6;
	private static final int FIRST_ROW_Y = 36;
	private static final int DELETE_WIDTH = 50;

	@Nullable
	private final Screen parent;
	private List<Waypoints.Marker> markers = List.of();
	private int page;

	public WaypointListScreen(@Nullable Screen parent) {
		super(Component.translatable("deathreplay.waypoints.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.markers = Waypoints.current(this.minecraft);
		int pages = Math.max(1, (this.markers.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
		this.page = Math.min(this.page, pages - 1);
		int x = this.width / 2 - ROW_WIDTH / 2;

		int y = FIRST_ROW_Y;
		for (int i = this.page * ROWS_PER_PAGE; i < Math.min(this.markers.size(), (this.page + 1) * ROWS_PER_PAGE); i++) {
			Waypoints.Marker marker = this.markers.get(i);
			this.addRenderableWidget(Button.builder(Component.translatable("deathreplay.waypoints.delete"), button -> {
				Waypoints.remove(this.minecraft, marker);
				this.rebuildWidgets();
			}).bounds(x + ROW_WIDTH - DELETE_WIDTH, y, DELETE_WIDTH, 20).build());
			y += ROW_HEIGHT;
		}

		int bottom = this.height - 28;
		int quarter = (ROW_WIDTH - 12) / 4;
		Button previous = Button.builder(Component.translatable("deathreplay.browser.previous"), button -> this.turnPage(-1)).bounds(x, bottom, quarter, 20).build();
		Button next = Button.builder(Component.translatable("deathreplay.browser.next"), button -> this.turnPage(1)).bounds(x + quarter + 4, bottom, quarter, 20).build();
		previous.active = this.page > 0;
		next.active = this.page < pages - 1;
		this.addRenderableWidget(previous);
		this.addRenderableWidget(next);
		Button clear = Button.builder(Component.translatable("deathreplay.waypoints.clear"), button -> {
			Waypoints.clear(this.minecraft);
			this.rebuildWidgets();
		}).bounds(x + (quarter + 4) * 2, bottom, quarter, 20).build();
		clear.active = !this.markers.isEmpty();
		this.addRenderableWidget(clear);
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose()).bounds(x + (quarter + 4) * 3, bottom, quarter, 20).build());
	}

	private void turnPage(int direction) {
		this.page = Math.max(0, this.page + direction);
		this.rebuildWidgets();
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		int centerX = this.width / 2;
		context.drawCenteredString(this.font, this.title, centerX, 14, TITLE_COLOR);

		if (this.minecraft.level == null) {
			context.drawCenteredString(this.font, Component.translatable("deathreplay.waypoints.need_world"), centerX, this.height / 2 - 10, NOTE_COLOR);
			return;
		}

		if (this.markers.isEmpty()) {
			context.drawCenteredString(this.font, Component.translatable("deathreplay.waypoints.empty"), centerX, this.height / 2 - 10, NOTE_COLOR);
			return;
		}

		int x = this.width / 2 - ROW_WIDTH / 2;
		int y = FIRST_ROW_Y + 6;
		for (int i = this.page * ROWS_PER_PAGE; i < Math.min(this.markers.size(), (this.page + 1) * ROWS_PER_PAGE); i++) {
			Waypoints.Marker marker = this.markers.get(i);
			String dimension = marker.dimension.startsWith("minecraft:") ? marker.dimension.substring("minecraft:".length()) : marker.dimension;
			context.drawString(this.font, Component.translatable("deathreplay.waypoints.row", marker.name, marker.x, marker.y, marker.z, dimension), x, y, TEXT_COLOR);
			y += ROW_HEIGHT;
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
