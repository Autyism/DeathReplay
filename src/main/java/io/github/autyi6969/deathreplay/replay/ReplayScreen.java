package io.github.autyi6969.deathreplay.replay;

import io.github.autyi6969.deathreplay.record.Recording;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The screen shown while a death replay plays: a timeline, the current view, and the keys.
 * The mouse is grabbed for looking around, so everything is driven by keys and mouse buttons.
 * Esc returns to the death screen the replay was opened from.
 */
public class ReplayScreen extends Screen {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFC0C0C0;
	private static final int BAR_BACKGROUND = 0xA0000000;
	private static final int BAR_FILL = 0xFFFFFFFF;
	private static final int BAR_DEATH_MARK = 0xFFFF5555;
	private static final int BAR_HEIGHT = 4;

	private final DeathScreen parent;

	public ReplayScreen(DeathScreen parent) {
		super(Text.translatable("deathreplay.replay.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Replay.setControlling(this.client, true);
	}

	@Override
	public void removed() {
		// Whatever replaces this screen, the world goes back to the present first.
		Replay.stop(this.client);
	}

	@Override
	public void tick() {
		if (!Replay.isActive()) {
			// The replay ended by itself (respawn, disconnect...): let vanilla pick the next screen.
			this.client.setScreen(null);
			return;
		}

		// The mouse can only be grabbed while the window has focus; retry after alt-tabbing back.
		Replay.regrabMouse(this.client);
	}

	@Override
	public void close() {
		this.client.setScreen(this.client.player != null && this.client.player.isDead() ? this.parent : null);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (this.client.options.togglePerspectiveKey.matchesKey(input)) {
			Replay.cycleView();
			return true;
		}

		switch (input.key()) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> Replay.togglePlaying();
			case GLFW.GLFW_KEY_LEFT -> Replay.seekRelative(-1);
			case GLFW.GLFW_KEY_RIGHT -> Replay.seekRelative(1);
			case GLFW.GLFW_KEY_R -> Replay.restart();
			default -> {
				return super.keyPressed(input);
			}
		}

		return true;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			Replay.togglePlaying();
			return true;
		}

		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		Replay.scroll(verticalAmount);
		return true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		Recording recording = Replay.getRecording();
		if (recording == null) {
			return;
		}

		int centerX = this.width / 2;
		int tick = Replay.getTick();
		int lastTick = Math.max(1, recording.tickCount() - 1);

		Text state = Text.translatable(Replay.isPlaying() ? "deathreplay.replay.playing" : Replay.isAtEnd() ? "deathreplay.replay.ended" : "deathreplay.replay.paused");
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.replay.header", state, Replay.getView().getDisplayName()), centerX, 8, TEXT_COLOR);

		// Timeline: white = played so far, red mark = the moment of death.
		int barWidth = Math.min(300, this.width - 40);
		int barLeft = centerX - barWidth / 2;
		int barTop = 22;
		context.fill(barLeft - 1, barTop - 1, barLeft + barWidth + 1, barTop + BAR_HEIGHT + 1, BAR_BACKGROUND);
		context.fill(barLeft, barTop, barLeft + barWidth * tick / lastTick, barTop + BAR_HEIGHT, BAR_FILL);
		int deathX = barLeft + barWidth * recording.deathFrame() / lastTick;
		context.fill(deathX, barTop - 2, deathX + 1, barTop + BAR_HEIGHT + 2, BAR_DEATH_MARK);

		float toDeath = (tick - recording.deathFrame()) / (float) Recording.TICKS_PER_SECOND;
		Text time = toDeath <= 0.0F
			? Text.translatable("deathreplay.replay.time.before", String.format("%.1f", -toDeath))
			: Text.translatable("deathreplay.replay.time.after", String.format("%.1f", toDeath));
		context.drawCenteredTextWithShadow(this.textRenderer, time, centerX, barTop + BAR_HEIGHT + 5, TEXT_COLOR);

		Text perspectiveKey = this.client.options.togglePerspectiveKey.getBoundKeyLocalizedText();
		int hintY = barTop + BAR_HEIGHT + 18;
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.replay.hint.transport"), centerX, hintY, HINT_COLOR);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.replay.hint.view", perspectiveKey), centerX, hintY + 12, HINT_COLOR);
		Text viewHint = switch (Replay.getView()) {
			case FIRST_PERSON -> Text.translatable("deathreplay.replay.hint.first_person");
			case THIRD_PERSON -> Text.translatable("deathreplay.spectate.hint.orbit");
			case FREE -> Text.translatable("deathreplay.spectate.hint.free");
		};
		context.drawCenteredTextWithShadow(this.textRenderer, viewHint, centerX, hintY + 24, HINT_COLOR);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		// No blur, no dimming: the replay is the content.
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
