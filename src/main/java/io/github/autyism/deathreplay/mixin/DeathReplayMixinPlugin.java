package io.github.autyism.deathreplay.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Keeps the self-test's packet counter out of normal play: the mixin that watches outgoing
 * packets is only applied when the game is started with {@code -Ddr.selftest=true}.
 */
public class DeathReplayMixinPlugin implements IMixinConfigPlugin {
	private static final String SELF_TEST_ONLY = "ClientConnectionAuditMixin";

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !mixinClassName.endsWith(SELF_TEST_ONLY) || Boolean.getBoolean("dr.selftest");
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		//? if >=26.1 {
		/*// Only exists from 26.1 on, so it is not in the mixin config that all versions share.
		return List.of("ClientLevelMixin");
		*///?} else
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
