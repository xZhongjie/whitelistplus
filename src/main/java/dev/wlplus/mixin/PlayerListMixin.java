package dev.wlplus.mixin;

import dev.wlplus.WhitelistPlus;
import dev.wlplus.whitelist.WhitelistStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * Rejects non-whitelisted players at login time by returning a disconnect reason from
 * {@code PlayerList#canPlayerLogin}. Vanilla then disconnects the player with that message,
 * before any play packets are sent.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
	@Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
	private void whitelistplus$enforceWhitelist(SocketAddress address, NameAndId nameAndId,
			CallbackInfoReturnable<Component> callback) {
		final WhitelistPlus mod = WhitelistPlus.instance();

		if (mod == null) {
			return;
		}

		final WhitelistStore store = mod.store();

		if (store == null) {
			return;
		}

		final boolean operator = ((PlayerList) (Object) this).isOp(nameAndId);

		if (store.shouldDeny(nameAndId.name(), operator)) {
			WhitelistPlus.LOGGER.info("拒绝未在白名单中的玩家 {} 登录", nameAndId.name());
			callback.setReturnValue(Component.literal(store.kickMessage()));
		}
	}
}
