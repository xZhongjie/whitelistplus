package dev.wlplus.whitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.wlplus.WhitelistPlus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The whitelist data and its on-disk JSON representation.
 *
 * <p>Entries are keyed by the lower-cased player name. All mutating methods save the file
 * immediately, so a crash never loses an edit that was already acknowledged by a command.
 */
public final class WhitelistStore {
	public record Entry(String name, String addedBy, long addedAt) {
	}

	public enum AddResult {
		ADDED,
		ALREADY_PRESENT,
		INVALID
	}

	public static final String DEFAULT_KICK_MESSAGE =
			"你不在本服务器的白名单中。\n请联系管理员申请加入。";

	private static final int MAX_NAME_LENGTH = 32;

	private final Path file;
	private final Gson gson = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	/** Lower-cased name -> entry. */
	private final TreeMap<String, Entry> entries = new TreeMap<>();

	private boolean enabled;
	private boolean allowWhenEmpty = true;
	private boolean allowOps = true;
	private String kickMessage = DEFAULT_KICK_MESSAGE;

	public WhitelistStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	public synchronized boolean isEnabled() {
		return enabled;
	}

	public synchronized boolean allowWhenEmpty() {
		return allowWhenEmpty;
	}

	public synchronized boolean allowOps() {
		return allowOps;
	}

	public synchronized String kickMessage() {
		return kickMessage;
	}

	public synchronized int size() {
		return entries.size();
	}

	/** Entries sorted by display name (case-insensitive). */
	public synchronized List<Entry> entries() {
		final List<Entry> list = new ArrayList<>(entries.values());
		list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	public synchronized boolean isWhitelisted(String name) {
		return name != null && entries.containsKey(name.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Returns {@code true} when the player must be rejected.
	 *
	 * @param name     player name
	 * @param operator whether the player currently has OP permissions
	 */
	public synchronized boolean shouldDeny(String name, boolean operator) {
		if (!enabled) {
			return false;
		}
		if (operator && allowOps) {
			return false;
		}
		if (entries.isEmpty() && allowWhenEmpty) {
			return false;
		}
		return !isWhitelisted(name);
	}

	public synchronized AddResult add(String name, String addedBy) {
		final String displayName = name == null ? "" : name.trim();

		if (!isValidName(displayName)) {
			return AddResult.INVALID;
		}

		final String key = displayName.toLowerCase(Locale.ROOT);

		if (entries.containsKey(key)) {
			return AddResult.ALREADY_PRESENT;
		}

		entries.put(key, new Entry(displayName, addedBy, System.currentTimeMillis()));
		save();
		return AddResult.ADDED;
	}

	public synchronized boolean remove(String name) {
		if (name == null) {
			return false;
		}

		final boolean removed = entries.remove(name.trim().toLowerCase(Locale.ROOT)) != null;

		if (removed) {
			save();
		}

		return removed;
	}

	public synchronized void setEnabled(boolean value) {
		if (this.enabled != value) {
			this.enabled = value;
			save();
		}
	}

	public synchronized void setAllowWhenEmpty(boolean value) {
		if (this.allowWhenEmpty != value) {
			this.allowWhenEmpty = value;
			save();
		}
	}

	public synchronized void setAllowOps(boolean value) {
		if (this.allowOps != value) {
			this.allowOps = value;
			save();
		}
	}

	public static boolean isValidName(String name) {
		if (name == null) {
			return false;
		}

		final String trimmed = name.trim();

		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
			return false;
		}

		for (int i = 0; i < trimmed.length(); i++) {
			final char c = trimmed.charAt(i);

			if (c < 0x20 || c == 0x7F || c == '\u00a7') {
				return false;
			}
		}

		return true;
	}

	/**
	 * Loads the JSON file. A missing file is created; a corrupt file is backed up and
	 * replaced by safe defaults so the server does not get permanently locked out.
	 */
	public synchronized void load() {
		entries.clear();
		enabled = false;
		allowWhenEmpty = true;
		allowOps = true;
		kickMessage = DEFAULT_KICK_MESSAGE;

		if (Files.notExists(file)) {
			save();
			return;
		}

		try {
			final String json = Files.readString(file, StandardCharsets.UTF_8);
			final JsonObject root = JsonParser.parseString(json).getAsJsonObject();

			enabled = readBoolean(root, "enabled", false);
			allowWhenEmpty = readBoolean(root, "allowWhenEmpty", true);
			allowOps = readBoolean(root, "allowOps", true);

			if (root.has("kickMessage") && root.get("kickMessage").isJsonPrimitive()) {
				final String message = root.get("kickMessage").getAsString();
				kickMessage = message.isEmpty() ? DEFAULT_KICK_MESSAGE : message;
			}

			if (root.has("entries") && root.get("entries").isJsonArray()) {
				final JsonArray array = root.getAsJsonArray("entries");

				for (JsonElement element : array) {
					if (!element.isJsonObject()) {
						continue;
					}

					final JsonObject object = element.getAsJsonObject();

					if (!object.has("name") || !object.get("name").isJsonPrimitive()) {
						continue;
					}

					final String name = object.get("name").getAsString().trim();

					if (!isValidName(name)) {
						continue;
					}

					final String addedBy = object.has("addedBy") && object.get("addedBy").isJsonPrimitive()
							? object.get("addedBy").getAsString()
							: "unknown";
					final long addedAt = object.has("addedAt") && object.get("addedAt").isJsonPrimitive()
							? object.get("addedAt").getAsLong()
							: 0L;

					entries.put(name.toLowerCase(Locale.ROOT), new Entry(name, addedBy, addedAt));
				}
			}
		} catch (Exception e) {
			WhitelistPlus.LOGGER.error("无法读取白名单文件 {}，将备份后重建", file, e);
			backupBrokenFile();
			entries.clear();
			enabled = false;
			allowWhenEmpty = true;
			allowOps = true;
			kickMessage = DEFAULT_KICK_MESSAGE;
			save();
		}
	}

	public synchronized void save() {
		final JsonObject root = new JsonObject();
		root.addProperty("enabled", enabled);
		root.addProperty("allowWhenEmpty", allowWhenEmpty);
		root.addProperty("allowOps", allowOps);
		root.addProperty("kickMessage", kickMessage);

		final JsonArray array = new JsonArray();

		for (Entry entry : entries()) {
			final JsonObject object = new JsonObject();
			object.addProperty("name", entry.name());
			object.addProperty("addedBy", entry.addedBy());
			object.addProperty("addedAt", entry.addedAt());
			array.add(object);
		}

		root.add("entries", array);

		try {
			Files.createDirectories(file.getParent());

			final Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
			Files.writeString(temp, gson.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);

			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			WhitelistPlus.LOGGER.error("无法保存白名单文件 {}", file, e);
		}
	}

	private void backupBrokenFile() {
		try {
			final Path backup = file.resolveSibling(
					file.getFileName().toString() + ".broken-" + System.currentTimeMillis());
			Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
			WhitelistPlus.LOGGER.warn("已将损坏的白名单文件备份为 {}", backup);
		} catch (IOException ignored) {
			WhitelistPlus.LOGGER.warn("无法备份损坏的白名单文件 {}", file);
		}
	}

	private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
		if (root.has(key) && root.get(key).isJsonPrimitive()) {
			try {
				return root.get(key).getAsBoolean();
			} catch (Exception ignored) {
				return fallback;
			}
		}

		return fallback;
	}
}
