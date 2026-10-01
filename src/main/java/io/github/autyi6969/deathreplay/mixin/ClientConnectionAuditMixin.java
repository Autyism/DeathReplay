package io.github.autyi6969.deathreplay.mixin;

import io.github.autyi6969.deathreplay.selftest.PacketAudit;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
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
@Mixin(ClientConnection.class)
public abstract class ClientConnectionAuditMixin {
	@Shadow
	@Final
	private NetworkSide side;

	@Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
	private void deathreplay$audit(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		// The client's end of a connection is the one that receives clientbound packets. In single
		// player the integrated server's end lives in the same process and must not be counted.
		if (this.side == NetworkSide.CLIENTBOUND) {
			PacketAudit.onClientSend(packet);
		}
	}
}
