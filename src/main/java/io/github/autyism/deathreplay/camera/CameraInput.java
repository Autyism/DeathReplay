package io.github.autyism.deathreplay.camera;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
//? if >=26.3 {
/*import java.nio.FloatBuffer;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.system.MemoryStack;
*///?} else
import org.lwjgl.glfw.GLFW;

/**
 * Reads movement keys and mouse motion directly from the window.
 *
 * <p>While a screen is open vanilla releases all key bindings, so {@link KeyMapping#isDown()}
 * is useless there; this asks GLFW for the physical state of whatever the player has bound.
 * Purely local input: nothing here is sent to the server.
 * (26.3+: the window is SDL, not GLFW; the same is asked of SDL.)
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
	public void grabMouse(Minecraft client) {
		if (!this.grabbed && client.isWindowActive() && !ignoreRealInput) {
			//? if >=26.3 {
			/*// SDL's relative mode. The cursor goes back where it was on release, as it did with GLFW.
			this.lastMouseX = client.mouseHandler.xpos();
			this.lastMouseY = client.mouseHandler.ypos();
			SDLMouse.SDL_SetWindowRelativeMouseMode(client.getWindow().handle(), true);
			relativeMotion();
			*///?} else
			GLFW.glfwSetInputMode(client.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
			this.grabbed = true;
			this.hasLastMouse = false;
		}
	}

	public void releaseMouse(Minecraft client) {
		if (this.grabbed) {
			//? if >=26.3 {
			/*SDLMouse.SDL_SetWindowRelativeMouseMode(client.getWindow().handle(), false);
			SDLMouse.SDL_WarpMouseInWindow(client.getWindow().handle(), (float) this.lastMouseX, (float) this.lastMouseY);
			*///?} else
			GLFW.glfwSetInputMode(client.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
			this.grabbed = false;
		}

		this.hasLastMouse = false;
	}

	public boolean isMouseGrabbed() {
		return this.grabbed;
	}

	/** Fills {@code state} from the keyboard and mouse. Call once per rendered frame. */
	public void poll(Minecraft client, State state) {
		Options options = client.options;
		state.forward = axis(client, options.keyUp, options.keyDown);
		state.strafe = axis(client, options.keyLeft, options.keyRight);
		state.up = axis(client, options.keyJump, options.keyShift);
		state.sprint = isHeld(client, options.keySprint);
		state.yawDelta = 0.0F;
		state.pitchDelta = 0.0F;

		if (ignoreRealInput) {
			state.forward = state.strafe = state.up = 0.0;
			state.sprint = false;
			this.hasLastMouse = false;
			return;
		}

		if (!this.grabbed || !client.isWindowActive()) {
			this.hasLastMouse = false;
			return;
		}

		//? if >=26.3 {
		/*// In relative mode the cursor stops at the window's edge; SDL counts the motion itself.
		double[] motion = relativeMotion();
		double sensitivity = options.sensitivity().get() * 0.6 + 0.2;
		double scale = sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
		double dx = motion[0] * scale;
		double dy = motion[1] * scale;
		state.yawDelta = (float) (options.invertMouseX().get() ? -dx : dx);
		state.pitchDelta = (float) (options.invertMouseY().get() ? -dy : dy);
		*///?} else {
		double x = client.mouseHandler.xpos();
		double y = client.mouseHandler.ypos();
		if (this.hasLastMouse) {
			// Same curve as vanilla mouse-look, so the camera feels like the player's own view.
			double sensitivity = options.sensitivity().get() * 0.6 + 0.2;
			double scale = sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
			double dx = (x - this.lastMouseX) * scale;
			double dy = (y - this.lastMouseY) * scale;
			state.yawDelta = (float) (options.invertMouseX().get() ? -dx : dx);
			state.pitchDelta = (float) (options.invertMouseY().get() ? -dy : dy);
		}

		this.lastMouseX = x;
		this.lastMouseY = y;
		//?}
		this.hasLastMouse = true;
	}

	//? if >=26.3 {
	/*/^* Mouse motion since the last call. ^/
	private static double[] relativeMotion() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer x = stack.mallocFloat(1);
			FloatBuffer y = stack.mallocFloat(1);
			SDLMouse.SDL_GetRelativeMouseState(x, y);
			return new double[] {x.get(0), y.get(0)};
		}
	}

	/^* Whether a mouse button (numbered from 1, as SDL does) is held. ^/
	private static boolean isMouseButtonDown(int button) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int buttons = SDLMouse.SDL_GetMouseState(stack.mallocFloat(1), stack.mallocFloat(1));
			return button > 0 && button <= 32 && (buttons & 1 << button - 1) != 0;
		}
	}
	*///?}

	private static double axis(Minecraft client, KeyMapping positive, KeyMapping negative) {
		return (isHeld(client, positive) ? 1.0 : 0.0) - (isHeld(client, negative) ? 1.0 : 0.0);
	}

	private static boolean isHeld(Minecraft client, KeyMapping binding) {
		if (!client.isWindowActive()) {
			return false;
		}

		InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(binding);
		//? if >=26.3 {
		/*// Keyboard keys are SDL scancodes.
		return switch (key.getType()) {
			case KEYBOARD -> key.getValue() != InputConstants.UNKNOWN.getValue() && InputConstants.isKeyDown(key.getValue());
			case MOUSE -> isMouseButtonDown(key.getValue());
			default -> false;
		};
		*///?} else {
		long window = client.getWindow().handle();
		return switch (key.getType()) {
			case KEYSYM -> key.getValue() != InputConstants.UNKNOWN.getValue() && GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
			case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
			default -> false;
		};
		//?}
	}
}
