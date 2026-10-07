package io.github.autyism.deathreplay.config;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import io.github.autyism.deathreplay.camera.DeathCameraMode;
import io.github.autyism.deathreplay.replay.ReplayBrowserScreen;
import io.github.autyism.deathreplay.replay.ReplayView;
import io.github.autyism.deathreplay.waypoint.WaypointListScreen;
import org.jetbrains.annotations.Nullable;

/**
 * The mod's settings: a plain column of vanilla widgets, no config library.
 * Changes apply immediately and are written to disk when the screen closes.
 */
public class SettingsScreen extends Screen {
	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int WIDGET_WIDTH = 260;
	private static final int WIDGET_HEIGHT = 20;
	private static final int ROW_HEIGHT = 24;
	private static final int BUFFER_STEP_SECONDS = 5;

	@Nullable
	private final Screen parent;
	private final DeathReplayConfig config = DeathReplayConfig.get();

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("deathreplay.options.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDGET_WIDTH / 2;
		int y = Math.max(28, this.height / 2 - 4 * ROW_HEIGHT - 8);

		this.addRenderableWidget(new BufferSlider(x, y, this.config));
		y += ROW_HEIGHT;

		this.addRenderableWidget(CycleButton.onOffBuilder(this.config.autoSave)
			.withTooltip(value -> Tooltip.create(Component.translatable("deathreplay.options.auto_save.tooltip")))
			.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("deathreplay.options.auto_save"), (button, value) -> this.config.autoSave = value));
		y += ROW_HEIGHT;

		this.addRenderableWidget(CycleButton.builder(ReplayView::getDisplayName, this.config.replayView)
			.withValues(List.of(ReplayView.values()))
			.withTooltip(value -> Tooltip.create(Component.translatable("deathreplay.options.replay_view.tooltip")))
			.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("deathreplay.options.replay_view"), (button, value) -> this.config.replayView = value));
		y += ROW_HEIGHT;

		this.addRenderableWidget(CycleButton.onOffBuilder(this.config.respawnHint)
			.withTooltip(value -> Tooltip.create(Component.translatable("deathreplay.options.respawn_hint.tooltip")))
			.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("deathreplay.options.respawn_hint"), (button, value) -> this.config.respawnHint = value));
		y += ROW_HEIGHT;

		this.addRenderableWidget(CycleButton.onOffBuilder(this.config.deathFreeCamera)
			.withTooltip(value -> Tooltip.create(Component.translatable("deathreplay.options.death_free_camera.tooltip")))
			.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("deathreplay.options.death_free_camera"), (button, value) -> this.config.deathFreeCamera = value));
		y += ROW_HEIGHT;

		this.addRenderableWidget(CycleButton.builder(DeathCameraMode::getDisplayName, this.config.deathCameraMode)
			.withValues(List.of(DeathCameraMode.values()))
			.withTooltip(value -> Tooltip.create(Component.translatable("deathreplay.options.death_camera_mode.tooltip")))
			.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("deathreplay.options.death_camera_mode"), (button, value) -> this.config.deathCameraMode = value));
		y += ROW_HEIGHT + 4;

		int half = (WIDGET_WIDTH - 4) / 2;
		this.addRenderableWidget(Button.builder(Component.translatable("deathreplay.options.browse"), button -> this.minecraft.setScreen(new ReplayBrowserScreen(this)))
			.bounds(x, y, half, WIDGET_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.translatable("deathreplay.options.waypoints"), button -> this.minecraft.setScreen(new WaypointListScreen(this)))
			.bounds(x + half + 4, y, half, WIDGET_HEIGHT)
			.build());
		y += ROW_HEIGHT;

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(this.width / 2 - 100, y, 200, WIDGET_HEIGHT)
			.build());
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredString(this.font, this.title, this.width / 2, 14, TITLE_COLOR);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	@Override
	public void removed() {
		// Also covers Esc and being replaced by another screen.
		this.config.save();
	}

	/** Buffer length in whole steps of five seconds. */
	private static final class BufferSlider extends AbstractSliderButton {
		private static final int STEPS = (DeathReplayConfig.MAX_BUFFER_SECONDS - DeathReplayConfig.MIN_BUFFER_SECONDS) / BUFFER_STEP_SECONDS;

		private final DeathReplayConfig config;

		BufferSlider(int x, int y, DeathReplayConfig config) {
			super(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.empty(), (config.bufferSeconds - DeathReplayConfig.MIN_BUFFER_SECONDS) / (double) (STEPS * BUFFER_STEP_SECONDS));
			this.config = config;
			this.setTooltip(Tooltip.create(Component.translatable("deathreplay.options.buffer_seconds.tooltip")));
			this.updateMessage();
		}

		private int seconds() {
			return DeathReplayConfig.MIN_BUFFER_SECONDS + (int) Math.round(Mth.clamp(this.value, 0.0, 1.0) * STEPS) * BUFFER_STEP_SECONDS;
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable("deathreplay.options.buffer_seconds", this.seconds()));
		}

		@Override
		protected void applyValue() {
			this.config.bufferSeconds = this.seconds();
		}
	}
}
