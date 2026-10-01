package io.github.autyi6969.deathreplay.camera;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Reads movement keys and mouse motion directly from the window.
 *
 * <p>While a screen is open vanilla releases all key bindings, so {@link KeyBinding#isPressed()}
 * is useless there; this asks GLFW for the physical state of whatever the player has bound.
 * Purely local input: nothing here is sent to the server.
 */
public final class CameraInput {
	/**
	 * Set by the self-test: a person using the computer while it runs would otherwise steer
	 * the camera with their real mouse and keys.
	 */
	public static boolean ignoreRealInput;

	private double lastMouseX;
	private double lastMouseY;
	private boolean hasLastMouse;
	private boolean grabbed;

	/** What the player (or the self-test) wants the camera to do this frame. */
	public static final class State {
		/** -1..1 each; strafe is positive to the left. */
		public double forward;
		public double strafe;
		public double up;
		public boolean sprint;
		/** Degrees to turn this frame. */
		public float yawDelta;
		public float pitchDelta;
	}

	/** Hides the cursor and lets it travel without limits, for mouse-look inside a screen. */
	public void grabMouse(MinecraftClient client) {
		if (!this.grabbed && client.isWindowFocused() && !ignoreRealInput) {
			GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
			this.grabbed = true;
			this.hasLastMouse = false;
		}
	}

	public void releaseMouse(MinecraftClient client) {
		if (this.grabbed) {
			GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
			this.grabbed = false;
		}

		this.hasLastMouse = false;
	}

	public boolean isMouseGrabbed() {
		return this.grabbed;
	}

	/** Fills {@code state} from the keyboard and mouse. Call once per rendered frame. */
	public void poll(MinecraftClient client, State state) {
		GameOptions options = client.options;
		state.forward = axis(client, options.forwardKey, options.backKey);
		state.strafe = axis(client, options.leftKey, options.rightKey);
		state.up = axis(client, options.jumpKey, options.sneakKey);
		state.sprint = isHeld(client, options.sprintKey);
		state.yawDelta = 0.0F;
		state.pitchDelta = 0.0F;

		if (ignoreRealInput) {
			state.forward = state.strafe = state.up = 0.0;
			state.sprint = false;
			this.hasLastMouse = false;
			return;
		}

		if (!this.grabbed || !client.isWindowFocused()) {
			this.hasLastMouse = false;
			return;
		}

		double x = client.mouse.getX();
		double y = client.mouse.getY();
		if (this.hasLastMouse) {
			// Same curve as vanilla mouse-look, so the camera feels like the player's own view.
			double sensitivity = options.getMouseSensitivity().getValue() * 0.6 + 0.2;
			double scale = sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
			double dx = (x - this.lastMouseX) * scale;
			double dy = (y - this.lastMouseY) * scale;
			state.yawDelta = (float) (options.getInvertMouseX().getValue() ? -dx : dx);
			state.pitchDelta = (float) (options.getInvertMouseY().getValue() ? -dy : dy);
		}

		this.lastMouseX = x;
		this.lastMouseY = y;
		this.hasLastMouse = true;
	}

	private static double axis(MinecraftClient client, KeyBinding positive, KeyBinding negative) {
		return (isHeld(client, positive) ? 1.0 : 0.0) - (isHeld(client, negative) ? 1.0 : 0.0);
	}

	private static boolean isHeld(MinecraftClient client, KeyBinding binding) {
		if (!client.isWindowFocused()) {
			return false;
		}

		InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(binding);
		long window = client.getWindow().getHandle();
		return switch (key.getCategory()) {
			case KEYSYM -> key.getCode() != InputUtil.UNKNOWN_KEY.getCode() && GLFW.glfwGetKey(window, key.getCode()) == GLFW.GLFW_PRESS;
			case MOUSE -> GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
			default -> false;
		};
	}
}
