package io.github.autyi6969.deathreplay.replay;

import io.github.autyi6969.deathreplay.record.Recording;
import io.github.autyi6969.deathreplay.record.ReplayFileWriter;
import io.github.autyi6969.deathreplay.waypoint.WaypointHud;
import io.github.autyi6969.deathreplay.waypoint.Waypoints;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The screen shown while a death replay plays.
 *
 * <p>The cursor stays free: the timeline at the top can be clicked and dragged, and a row of
 * buttons at the bottom does the rest. Holding the right mouse button turns the camera;
 * the movement keys fly it in free view. Closing the screen ends the replay.
 */
public class ReplayScreen extends Screen {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFC0C0C0;
	private static final int BAR_BACKGROUND = 0xA0000000;
	private static final int BAR_FILL = 0xFFFFFFFF;
	private static final int BAR_DEATH_MARK = 0xFFFF5555;
	private static final int BAR_TOP = 22;
	private static final int BAR_HEIGHT = 6;
	/** How far above and below the bar a click still counts as a click on it. */
	private static final int BAR_GRAB_MARGIN = 5;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final int MAX_BUTTON_WIDTH = 78;
	private static final int NOTICE_COLOR = 0xFFFFFF55;
	private static final int NOTICE_TICKS = 60;
	private static final long MARK_CLICK_NANOS = 300_000_000L;
	private static final float MARK_CLICK_DEGREES = 2.0F;

	/** Screen to go back to; {@code null} means "back to the game". */
	@Nullable
	private final Screen parent;
	private ButtonWidget playButton;
	private ButtonWidget viewButton;
	private ButtonWidget saveButton;
	/** True while the left mouse button is dragging the timeline. */
	private boolean scrubbing;
	private boolean wasPlayingBeforeScrub;
	// A right click that is short and does not turn the camera drops a marker instead.
	private long rightPressNanos;
	private float rightPressYaw;
	private float rightPressPitch;
	@Nullable
	private Text notice;
	private int noticeTicksLeft;

	public ReplayScreen(@Nullable Screen parent) {
		super(Text.translatable("deathreplay.replay.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Replay.setControlling(this.client, true);

		int count = 5;
		int buttonWidth = Math.min(MAX_BUTTON_WIDTH, (this.width - 16 - BUTTON_GAP * (count - 1)) / count);
		int x = (this.width - (buttonWidth * count + BUTTON_GAP * (count - 1))) / 2;
		int y = this.height - BUTTON_HEIGHT - 6;
		int step = buttonWidth + BUTTON_GAP;

		this.addDrawableChild(this.button(Text.translatable("deathreplay.replay.button.restart"), x, y, buttonWidth, Replay::restart));
		this.playButton = this.addDrawableChild(this.button(Text.empty(), x + step, y, buttonWidth, Replay::togglePlaying));
		this.viewButton = this.addDrawableChild(this.button(Text.empty(), x + step * 2, y, buttonWidth, Replay::cycleView));
		this.saveButton = this.addDrawableChild(this.button(Text.empty(), x + step * 3, y, buttonWidth, () -> {
			Recording recording = Replay.getRecording();
			if (recording != null) {
				ReplayFileWriter.saveAndAnnounce(this.client, recording);
			}
		}));
		this.addDrawableChild(this.button(Text.translatable("deathreplay.replay.button.close"), x + step * 4, y, buttonWidth, this::close));
		this.refreshButtons();
	}

	private ButtonWidget button(Text label, int x, int y, int width, Runnable action) {
		return ButtonWidget.builder(label, pressed -> {
			action.run();
			// A focused button would swallow Space and Enter, which fly the camera and pause.
			this.setFocused(null);
		}).dimensions(x, y, width, BUTTON_HEIGHT).build();
	}

	private void refreshButtons() {
		Recording recording = Replay.getRecording();
		this.playButton.setMessage(Text.translatable(Replay.isPlaying() ? "deathreplay.replay.button.pause" : "deathreplay.replay.button.play"));
		this.viewButton.setMessage(Replay.getView().getDisplayName());
		boolean saved = recording != null && ReplayFileWriter.isSaved(recording);
		this.saveButton.setMessage(Text.translatable(saved ? "deathreplay.replay.button.saved" : "deathreplay.replay.button.save"));
		this.saveButton.active = recording != null && !saved && !ReplayFileWriter.isSaving(recording);
	}

	@Override
	public void removed() {
		// Whatever replaces this screen, the real world goes back on screen first.
		Replay.endPlayback(this.client);
	}

	@Override
	public void tick() {
		if (!Replay.isPlayback()) {
			// The replay ended by itself (respawn, disconnect...): let vanilla pick the next screen.
			this.client.setScreen(null);
			return;
		}

		if (this.noticeTicksLeft > 0) {
			this.noticeTicksLeft--;
		}
	}

	@Override
	public void close() {
		// null lets vanilla decide: the death screen if the player is dead, the game otherwise.
		this.client.setScreen(this.client.player != null && this.client.player.isDead() ? this.deathScreenOrNull() : this.parent);
	}

	@Nullable
	private Screen deathScreenOrNull() {
		return this.parent instanceof DeathScreen ? this.parent : null;
	}

	// ---------------------------------------------------------------- timeline geometry

	private int barWidth() {
		return Math.min(300, this.width - 40);
	}

	private int barLeft() {
		return this.width / 2 - this.barWidth() / 2;
	}

	private boolean isOnBar(double mouseX, double mouseY) {
		return mouseX >= this.barLeft() - 2 && mouseX <= this.barLeft() + this.barWidth() + 2
			&& mouseY >= BAR_TOP - BAR_GRAB_MARGIN && mouseY <= BAR_TOP + BAR_HEIGHT + BAR_GRAB_MARGIN;
	}

	/** The tick a horizontal mouse position on the bar stands for. */
	private int tickAt(double mouseX) {
		Recording recording = Replay.getRecording();
		if (recording == null) {
			return 0;
		}

		double fraction = MathHelper.clamp((mouseX - this.barLeft()) / this.barWidth(), 0.0, 1.0);
		return (int) Math.round(fraction * (recording.tickCount() - 1));
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) {
			return true;
		}

		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.isOnBar(click.x(), click.y())) {
			this.scrubbing = true;
			this.wasPlayingBeforeScrub = Replay.isPlaying();
			Replay.setPlaying(false);
			Replay.requestSeek(this.tickAt(click.x()));
			return true;
		}

		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			this.rightPressNanos = System.nanoTime();
			this.rightPressYaw = Replay.getCamera().getYaw();
			this.rightPressPitch = Replay.getCamera().getPitch();
			Replay.setLooking(this.client, true);
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (this.scrubbing && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			Replay.requestSeek(this.tickAt(click.x()));
			return true;
		}

		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.scrubbing) {
			this.scrubbing = false;
			if (this.wasPlayingBeforeScrub) {
				Replay.setPlaying(true);
			}

			return true;
		}

		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			Replay.setLooking(this.client, false);
			boolean quick = System.nanoTime() - this.rightPressNanos < MARK_CLICK_NANOS;
			boolean still = Math.abs(Replay.getCamera().getYaw() - this.rightPressYaw) + Math.abs(Replay.getCamera().getPitch() - this.rightPressPitch) < MARK_CLICK_DEGREES;
			if (quick && still && Replay.getStage() != null) {
				// What the cursor is on, not what the centre of the screen is on: the cursor is free here.
				Waypoints.Marker marker = Waypoints.addFromCamera(this.client, Replay.getStage(), Replay.getCamera(),
					click.x() / this.width * 2.0 - 1.0, 1.0 - click.y() / this.height * 2.0);
				if (marker != null) {
					this.notice = Text.translatable("deathreplay.waypoint.added", marker.name, marker.x, marker.y, marker.z);
					this.noticeTicksLeft = NOTICE_TICKS;
				}
			}

			return true;
		}

		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		Replay.scroll(verticalAmount);
		return true;
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

	// ---------------------------------------------------------------- drawing

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		Recording recording = Replay.getRecording();
		if (recording == null) {
			return;
		}

		this.refreshButtons();
		super.render(context, mouseX, mouseY, deltaTicks);

		int centerX = this.width / 2;
		int tick = Replay.getTick();
		int lastTick = Math.max(1, recording.tickCount() - 1);

		Text state = Text.translatable(Replay.isPlaying() ? "deathreplay.replay.playing" : Replay.isAtEnd() ? "deathreplay.replay.ended" : "deathreplay.replay.paused");
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.replay.header", state, Replay.getView().getDisplayName()), centerX, 8, TEXT_COLOR);

		// Timeline: white = played so far, red mark = the moment of death, knob = where we are.
		int barWidth = this.barWidth();
		int barLeft = this.barLeft();
		int knobX = barLeft + barWidth * tick / lastTick;
		context.fill(barLeft - 1, BAR_TOP - 1, barLeft + barWidth + 1, BAR_TOP + BAR_HEIGHT + 1, BAR_BACKGROUND);
		context.fill(barLeft, BAR_TOP, knobX, BAR_TOP + BAR_HEIGHT, BAR_FILL);
		int deathX = barLeft + barWidth * recording.deathFrame() / lastTick;
		context.fill(deathX, BAR_TOP - 2, deathX + 1, BAR_TOP + BAR_HEIGHT + 2, BAR_DEATH_MARK);
		context.fill(knobX - 1, BAR_TOP - 3, knobX + 2, BAR_TOP + BAR_HEIGHT + 3, BAR_FILL);

		float toDeath = (tick - recording.deathFrame()) / (float) Recording.TICKS_PER_SECOND;
		Text time = toDeath <= 0.0F
			? Text.translatable("deathreplay.replay.time.before", String.format("%.1f", -toDeath))
			: Text.translatable("deathreplay.replay.time.after", String.format("%.1f", toDeath));
		context.drawCenteredTextWithShadow(this.textRenderer, time, centerX, BAR_TOP + BAR_HEIGHT + 6, TEXT_COLOR);

		Text perspectiveKey = this.client.options.togglePerspectiveKey.getBoundKeyLocalizedText();
		Text viewHint = switch (Replay.getView()) {
			case FIRST_PERSON -> Text.translatable("deathreplay.replay.hint.first_person", perspectiveKey);
			case THIRD_PERSON -> Text.translatable("deathreplay.replay.hint.third_person", perspectiveKey);
			case FREE -> Text.translatable("deathreplay.replay.hint.free", perspectiveKey);
		};
		context.drawCenteredTextWithShadow(this.textRenderer, viewHint, centerX, this.height - BUTTON_HEIGHT - 20, HINT_COLOR);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.replay.hint.mark"), centerX, this.height - BUTTON_HEIGHT - 32, HINT_COLOR);

		WaypointHud.render(context, this.client, recording.dimension());
		if (this.notice != null && this.noticeTicksLeft > 0) {
			context.drawCenteredTextWithShadow(this.textRenderer, this.notice, centerX, BAR_TOP + BAR_HEIGHT + 20, NOTICE_COLOR);
		}
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
