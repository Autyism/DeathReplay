package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.selftest.PacketAudit;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Self-test only (see {@link DeathReplayMixinPlugin}): lets the self-test count what the
 * client sends. It only looks; no packet is changed, added or held back.
 */
@Mixin(Connection.class)
public abstract class ClientConnectionAuditMixin {
	@Shadow
	@Final
	private PacketFlow receiving;

	//? if >=1.21.6 {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
	private void deathreplay$audit(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
	//?} else {
	/*@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"))
	private void deathreplay$audit(Packet<?> packet, net.minecraft.network.PacketSendListener listener, boolean flush, CallbackInfo ci) {
	*///?}
		// The client's end of a connection is the one that receives clientbound packets. In single
		// player the integrated server's end lives in the same process and must not be counted.
		if (this.receiving == PacketFlow.CLIENTBOUND) {
			PacketAudit.onClientSend(packet);
		}
	}
}
