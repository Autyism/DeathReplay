package io.github.autyism.deathreplay.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the replay stage receive its light through vanilla's own method.
 *
 * <p>Renderer mods (Sodium) learn that a chunk is ready to be drawn by hooking
 * {@code applyLightData}, and they look at the handler's {@code level} field while doing so.
 * Copying vanilla's light code would bypass those hooks and leave the stage invisible to them.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPlayNetworkHandlerAccessor {
	@Accessor("level")
	ClientLevel deathreplay$getWorld();

	@Accessor("level")
	void deathreplay$setWorld(ClientLevel world);

	@Invoker("applyLightData")
	void deathreplay$readLightData(int x, int z, ClientboundLightUpdatePacketData data, boolean scheduleBlockRenders);
}
