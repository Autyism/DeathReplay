package io.github.autyism.deathreplay.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.LightData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the replay stage receive its light through vanilla's own method.
 *
 * <p>Renderer mods (Sodium) learn that a chunk is ready to be drawn by hooking
 * {@code readLightData}, and they look at the handler's {@code world} field while doing so.
 * Copying vanilla's light code would bypass those hooks and leave the stage invisible to them.
 */
@Mixin(ClientPlayNetworkHandler.class)
public interface ClientPlayNetworkHandlerAccessor {
	@Accessor("world")
	ClientWorld deathreplay$getWorld();

	@Accessor("world")
	void deathreplay$setWorld(ClientWorld world);

	@Invoker("readLightData")
	void deathreplay$readLightData(int x, int z, LightData data, boolean scheduleBlockRenders);
}
