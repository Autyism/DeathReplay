package io.github.autyi6969.deathreplay.config;

import java.util.List;

import io.github.autyi6969.deathreplay.camera.DeathCameraMode;
import io.github.autyi6969.deathreplay.replay.ReplayView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
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
		super(Text.translatable("deathreplay.options.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDGET_WIDTH / 2;
		int y = Math.max(30, this.height / 2 - 4 * ROW_HEIGHT + 6);

		this.addDrawableChild(new BufferSlider(x, y, this.config));
		y += ROW_HEIGHT;

		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.autoSave)
			.tooltip(value -> Tooltip.of(Text.translatable("deathreplay.options.auto_save.tooltip")))
			.build(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("deathreplay.options.auto_save"), (button, value) -> this.config.autoSave = value));
		y += ROW_HEIGHT;

		this.addDrawableChild(CyclingButtonWidget.builder(ReplayView::getDisplayName, this.config.replayView)
			.values(List.of(ReplayView.values()))
			.tooltip(value -> Tooltip.of(Text.translatable("deathreplay.options.replay_view.tooltip")))
			.build(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("deathreplay.options.replay_view"), (button, value) -> this.config.replayView = value));
		y += ROW_HEIGHT;

		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.respawnHint)
			.tooltip(value -> Tooltip.of(Text.translatable("deathreplay.options.respawn_hint.tooltip")))
			.build(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("deathreplay.options.respawn_hint"), (button, value) -> this.config.respawnHint = value));
		y += ROW_HEIGHT;

		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.deathFreeCamera)
			.tooltip(value -> Tooltip.of(Text.translatable("deathreplay.options.death_free_camera.tooltip")))
			.build(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("deathreplay.options.death_free_camera"), (button, value) -> this.config.deathFreeCamera = value));
		y += ROW_HEIGHT;

		this.addDrawableChild(CyclingButtonWidget.builder(DeathCameraMode::getDisplayName, this.config.deathCameraMode)
			.values(List.of(DeathCameraMode.values()))
			.tooltip(value -> Tooltip.of(Text.translatable("deathreplay.options.death_camera_mode.tooltip")))
			.build(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("deathreplay.options.death_camera_mode"), (button, value) -> this.config.deathCameraMode = value));
		y += ROW_HEIGHT + 8;

		this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> this.close())
			.dimensions(this.width / 2 - 100, y, 200, WIDGET_HEIGHT)
			.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, TITLE_COLOR);
	}

	@Override
	public void close() {
		this.client.setScreen(this.parent);
	}

	@Override
	public void removed() {
		// Also covers Esc and being replaced by another screen.
		this.config.save();
	}

	/** Buffer length in whole steps of five seconds. */
	private static final class BufferSlider extends SliderWidget {
		private static final int STEPS = (DeathReplayConfig.MAX_BUFFER_SECONDS - DeathReplayConfig.MIN_BUFFER_SECONDS) / BUFFER_STEP_SECONDS;

		private final DeathReplayConfig config;

		BufferSlider(int x, int y, DeathReplayConfig config) {
			super(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.empty(), (config.bufferSeconds - DeathReplayConfig.MIN_BUFFER_SECONDS) / (double) (STEPS * BUFFER_STEP_SECONDS));
			this.config = config;
			this.setTooltip(Tooltip.of(Text.translatable("deathreplay.options.buffer_seconds.tooltip")));
			this.updateMessage();
		}

		private int seconds() {
			return DeathReplayConfig.MIN_BUFFER_SECONDS + (int) Math.round(MathHelper.clamp(this.value, 0.0, 1.0) * STEPS) * BUFFER_STEP_SECONDS;
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Text.translatable("deathreplay.options.buffer_seconds", this.seconds()));
		}

		@Override
		protected void applyValue() {
			this.config.bufferSeconds = this.seconds();
		}
	}
}
