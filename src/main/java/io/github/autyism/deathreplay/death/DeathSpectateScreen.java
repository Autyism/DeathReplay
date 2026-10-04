package io.github.autyism.deathreplay.death;

import io.github.autyism.deathreplay.camera.DeathCameraMode;
import io.github.autyism.deathreplay.waypoint.WaypointHud;
import io.github.autyism.deathreplay.waypoint.Waypoints;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The interactive part of the death screen free camera: hides the death screen's red tint and
 * buttons, grabs the mouse for looking around and lets WASD fly the camera in free mode.
 * Esc goes back to the vanilla death screen it was opened from.
 */
public class DeathSpectateScreen extends Screen {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFC0C0C0;

	private static final int NOTICE_COLOR = 0xFFFFFF55;
	private static final int CROSSHAIR_COLOR = 0xC0FFFFFF;
	private static final int NOTICE_TICKS = 60;

	private final DeathScreen parent;
	/** "Marked: ..." line shown for a moment after a right click. */
	@Nullable
	private Text notice;
	private int noticeTicksLeft;

	public DeathSpectateScreen(DeathScreen parent) {
		super(Text.translatable("deathreplay.spectate.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		DeathView.setControlling(this.client, true);
	}

	@Override
	public void removed() {
		DeathView.setControlling(this.client, false);
	}

	@Override
	public void tick() {
		if (!DeathView.isActive()) {
			// Respawned (or the feature was switched off): hand control back to vanilla,
			// which shows the death screen if the player is still dead and the game otherwise.
			this.client.setScreen(null);
			return;
		}

		// The mouse can only be grabbed while the window has focus; retry after alt-tabbing back.
		DeathView.regrabMouse(this.client);
		if (this.noticeTicksLeft > 0) {
			this.noticeTicksLeft--;
		}
	}

	/** Right click: drop a marker on whatever the crosshair points at. */
	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && this.client.world != null) {
			Waypoints.Marker marker = Waypoints.addFromCamera(this.client, this.client.world, DeathView.getCamera());
			if (marker != null) {
				this.notice = Text.translatable("deathreplay.waypoint.added", marker.name, marker.x, marker.y, marker.z);
				this.noticeTicksLeft = NOTICE_TICKS;
			}

			return true;
		}

		return super.mouseClicked(click, doubled);
	}

	@Override
	public void close() {
		this.client.setScreen(DeathView.isActive() ? this.parent : null);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (this.client.options.togglePerspectiveKey.matchesKey(input)) {
			DeathView.cycleMode();
			return true;
		}

		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		DeathView.scroll(verticalAmount);
		return true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		DeathCameraMode mode = DeathView.getMode();
		Text perspectiveKey = this.client.options.togglePerspectiveKey.getBoundKeyLocalizedText();
		int centerX = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.mode", mode.getDisplayName()), centerX, 8, TEXT_COLOR);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint", perspectiveKey), centerX, 20, HINT_COLOR);
		if (mode == DeathCameraMode.FREE) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint.free"), centerX, 32, HINT_COLOR);
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint.free2"), centerX, 44, HINT_COLOR);
		} else {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint.orbit"), centerX, 32, HINT_COLOR);
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint.mark"), centerX, 44, HINT_COLOR);
		}

		// A crosshair, because the right click marks what the centre of the screen points at.
		int centerY = this.height / 2;
		context.fill(centerX - 5, centerY, centerX + 6, centerY + 1, CROSSHAIR_COLOR);
		context.fill(centerX, centerY - 5, centerX + 1, centerY + 6, CROSSHAIR_COLOR);

		WaypointHud.render(context, this.client, this.client.world.getRegistryKey());
		if (this.notice != null && this.noticeTicksLeft > 0) {
			context.drawCenteredTextWithShadow(this.textRenderer, this.notice, centerX, 60, NOTICE_COLOR);
		}
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		// No blur, no dimming, no red tint: the point of this screen is an unobstructed view.
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
