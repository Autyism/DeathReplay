package io.github.autyism.deathreplay.death;

import io.github.autyism.deathreplay.camera.DeathCameraMode;
import io.github.autyism.deathreplay.waypoint.WaypointHud;
import io.github.autyism.deathreplay.waypoint.Waypoints;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
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
	private Component notice;
	private int noticeTicksLeft;

	public DeathSpectateScreen(DeathScreen parent) {
		super(Component.translatable("deathreplay.spectate.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		DeathView.setControlling(this.minecraft, true);
	}

	@Override
	public void removed() {
		DeathView.setControlling(this.minecraft, false);
	}

	@Override
	public void tick() {
		if (!DeathView.isActive()) {
			// Respawned (or the feature was switched off): hand control back to vanilla,
			// which shows the death screen if the player is still dead and the game otherwise.
			this.minecraft.setScreen(null);
			return;
		}

		// The mouse can only be grabbed while the window has focus; retry after alt-tabbing back.
		DeathView.regrabMouse(this.minecraft);
		if (this.noticeTicksLeft > 0) {
			this.noticeTicksLeft--;
		}
	}

	/** Right click: drop a marker on whatever the crosshair points at. */
	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && this.minecraft.level != null) {
			Waypoints.Marker marker = Waypoints.addFromCamera(this.minecraft, this.minecraft.level, DeathView.getCamera());
			if (marker != null) {
				this.notice = Component.translatable("deathreplay.waypoint.added", marker.name, marker.x, marker.y, marker.z);
				this.noticeTicksLeft = NOTICE_TICKS;
			}

			return true;
		}

		return super.mouseClicked(click, doubled);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(DeathView.isActive() ? this.parent : null);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (this.minecraft.options.keyTogglePerspective.matches(input)) {
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
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		DeathCameraMode mode = DeathView.getMode();
		Component perspectiveKey = this.minecraft.options.keyTogglePerspective.getTranslatedKeyMessage();
		int centerX = this.width / 2;
		context.drawCenteredString(this.font, Component.translatable("deathreplay.spectate.mode", mode.getDisplayName()), centerX, 8, TEXT_COLOR);
		context.drawCenteredString(this.font, Component.translatable("deathreplay.spectate.hint", perspectiveKey), centerX, 20, HINT_COLOR);
		if (mode == DeathCameraMode.FREE) {
			context.drawCenteredString(this.font, Component.translatable("deathreplay.spectate.hint.free"), centerX, 32, HINT_COLOR);
			context.drawCenteredString(this.font, Component.translatable("deathreplay.spectate.hint.free2"), centerX, 44, HINT_COLOR);
		} else {
			context.drawCenteredString(this.font, Component.translatable("deathreplay.spectate.hint.orbit"), centerX, 32, HINT_COLOR);
			context.drawCenteredString(this.font, Component.translatable("deathreplay.spectate.hint.mark"), centerX, 44, HINT_COLOR);
		}

		// A crosshair, because the right click marks what the centre of the screen points at.
		int centerY = this.height / 2;
		context.fill(centerX - 5, centerY, centerX + 6, centerY + 1, CROSSHAIR_COLOR);
		context.fill(centerX, centerY - 5, centerX + 1, centerY + 6, CROSSHAIR_COLOR);

		WaypointHud.render(context, this.minecraft, this.minecraft.level.dimension());
		if (this.notice != null && this.noticeTicksLeft > 0) {
			context.drawCenteredString(this.font, this.notice, centerX, 60, NOTICE_COLOR);
		}
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		// No blur, no dimming, no red tint: the point of this screen is an unobstructed view.
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
