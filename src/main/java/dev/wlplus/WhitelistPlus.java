package dev.wlplus;

import dev.wlplus.command.WhitelistCommands;
import dev.wlplus.whitelist.WhitelistStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Whitelist Plus - a server-authoritative whitelist for Fabric.
 *
 * <p>The whitelist is matched by player name (case-insensitive), which works both on
 * online-mode and offline-mode servers, and does not require any client-side code.
 */
public final class WhitelistPlus implements ModInitializer {
	public static final String MOD_ID = "whitelistplus";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static WhitelistPlus instance;

	private WhitelistStore store;

	@Override
	public void onInitialize() {
		instance = this;

		final Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		this.store = new WhitelistStore(dir.resolve("whitelist.json"));
		this.store.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				WhitelistCommands.register(dispatcher, this.store));

		LOGGER.info("Whitelist Plus initialized (enabled={}, entries={}, file={})",
				this.store.isEnabled(), this.store.size(), this.store.file());
	}

	/** May be {@code null} while the game is still starting up. */
	public static WhitelistPlus instance() {
		return instance;
	}

	public WhitelistStore store() {
		return store;
	}
}
