package io.github.autyism.deathreplay.mixin;

//? if <1.21.6 {
/*import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/^*
 * 1.21.5 only: the field of view the world is drawn with, so marker labels can be placed on the
 * screen ({@code legacy.ScreenProjection}). Registered by {@link DeathReplayMixinPlugin}.
 ^/
@Mixin(GameRenderer.class)
public interface GameRendererFovInvoker {
	@Invoker("getFov")
	float deathreplay$getFov(Camera camera, float partialTicks, boolean useFovSetting);
}
*///?}
