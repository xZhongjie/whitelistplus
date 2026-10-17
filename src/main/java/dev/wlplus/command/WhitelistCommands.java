package dev.wlplus.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.wlplus.whitelist.WhitelistStore;
import dev.wlplus.whitelist.WhitelistStore.AddResult;
import dev.wlplus.whitelist.WhitelistStore.Entry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Registers the {@code /wl} command tree. Every subcommand requires admin (level 3)
 * permissions, so the whitelist cannot be modified by regular players.
 */
public final class WhitelistCommands {
	private static final Predicate<CommandSourceStack> ADMIN =
			source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);

	private static final int PAGE_SIZE = 10;
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private WhitelistCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, WhitelistStore store) {
		final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("wl")
				.requires(ADMIN)
				.executes(WhitelistCommands::help)
				.then(Commands.literal("help").executes(WhitelistCommands::help))
				.then(Commands.literal("status").executes(context -> status(context, store)))
				.then(Commands.literal("list")
						.executes(context -> list(context, store, 1))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
								.executes(context -> list(context, store,
										IntegerArgumentType.getInteger(context, "page")))))
				.then(Commands.literal("add")
						.then(Commands.argument("name", StringArgumentType.string())
								.executes(context -> add(context, store))))
				.then(Commands.literal("addall")
						.then(Commands.argument("names", StringArgumentType.greedyString())
								.executes(context -> addAll(context, store))))
				.then(Commands.literal("remove")
						.then(Commands.argument("name", StringArgumentType.string())
								.executes(context -> remove(context, store))))
				.then(Commands.literal("removeall")
						.then(Commands.argument("names", StringArgumentType.greedyString())
								.executes(context -> removeAll(context, store))))
				.then(Commands.literal("check")
						.then(Commands.argument("name", StringArgumentType.string())
								.executes(context -> check(context, store))))
				.then(Commands.literal("on").executes(context -> setEnabled(context, store, true)))
				.then(Commands.literal("off").executes(context -> setEnabled(context, store, false)))
				.then(Commands.literal("empty")
						.then(Commands.literal("on").executes(context -> setAllowWhenEmpty(context, store, true)))
						.then(Commands.literal("off").executes(context -> setAllowWhenEmpty(context, store, false))))
				.then(Commands.literal("reload").executes(context -> reload(context, store)))
				.then(Commands.literal("save").executes(context -> save(context, store)));

		dispatcher.register(root);
	}

	private static int help(CommandContext<CommandSourceStack> context) {
		final CommandSourceStack source = context.getSource();

		info(source, "Whitelist Plus 命令（需要管理员权限）：");
		info(source, "/wl status - 查看白名单状态");
		info(source, "/wl list [页码] - 列出白名单玩家");
		info(source, "/wl add <玩家名> - 添加玩家");
		info(source, "/wl addall <玩家名...> - 批量添加（空格或逗号分隔）");
		info(source, "/wl remove <玩家名> - 移除玩家");
		info(source, "/wl removeall <玩家名...> - 批量移除（空格或逗号分隔）");
		info(source, "/wl check <玩家名> - 查询玩家是否在白名单中");
		info(source, "/wl on|off - 开启或关闭白名单");
		info(source, "/wl empty on|off - 名单为空时是否放行所有人");
		info(source, "/wl reload - 从磁盘重新加载");
		info(source, "/wl save - 立即保存到磁盘");

		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();

		info(source, "Whitelist Plus 状态");
		info(source, " - 白名单：" + (store.isEnabled() ? "已开启" : "已关闭"));
		info(source, " - 名单人数：" + store.size());
		info(source, " - 空名单放行：" + (store.allowWhenEmpty() ? "是" : "否"));
		info(source, " - 文件：" + store.file());
		info(source, " - 拒绝提示：" + store.kickMessage().replace("\n", " / "));

		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context, WhitelistStore store, int page) {
		final CommandSourceStack source = context.getSource();
		final List<Entry> entries = store.entries();

		if (entries.isEmpty()) {
			info(source, "白名单为空。使用 /wl add <玩家名> 添加玩家。");
			return 1;
		}

		final int totalPages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;

		if (page > totalPages) {
			return fail(source, "页码超出范围：共 " + totalPages + " 页。");
		}

		info(source, "白名单（第 " + page + "/" + totalPages + " 页，共 " + entries.size() + " 人）：");

		final int from = (page - 1) * PAGE_SIZE;
		final int to = Math.min(entries.size(), from + PAGE_SIZE);

		for (int i = from; i < to; i++) {
			final Entry entry = entries.get(i);
			final String time = entry.addedAt() <= 0 ? "未知时间" : TIME_FORMAT.format(Instant.ofEpochMilli(entry.addedAt()));
			info(source, " " + (i + 1) + ". " + entry.name()
					+ "（添加者：" + entry.addedBy() + "，时间：" + time + "）");
		}

		if (page < totalPages) {
			info(source, "使用 /wl list " + (page + 1) + " 查看下一页。");
		}

		return 1;
	}

	private static int add(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();
		final String name = StringArgumentType.getString(context, "name");

		switch (store.add(name, source.getDisplayName().getString())) {
			case ADDED -> {
				info(source, "已将 " + name.trim() + " 加入白名单（当前共 " + store.size() + " 人）。");
				return 1;
			}
			case ALREADY_PRESENT -> {
				return fail(source, name.trim() + " 已在白名单中。");
			}
			default -> {
				return fail(source, "无效的玩家名（长度 1-32，不能包含换行或 § 等控制字符）。");
			}
		}
	}

	private static int remove(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();
		final String name = StringArgumentType.getString(context, "name");

		if (store.remove(name)) {
			info(source, "已将 " + name.trim() + " 移出白名单（当前共 " + store.size() + " 人）。");
			return 1;
		}

		return fail(source, name.trim() + " 不在白名单中。");
	}

	private static int addAll(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();
		final List<String> names = splitNames(StringArgumentType.getString(context, "names"));

		if (names.isEmpty()) {
			return fail(source, "没有可添加的玩家名。");
		}

		final String addedBy = source.getDisplayName().getString();
		int added = 0;
		int existing = 0;
		final List<String> invalid = new ArrayList<>();

		for (String name : names) {
			switch (store.add(name, addedBy)) {
				case ADDED -> added++;
				case ALREADY_PRESENT -> existing++;
				default -> invalid.add(name);
			}
		}

		info(source, "批量添加完成：新增 " + added + " 人，已存在 " + existing
				+ " 人，无效 " + invalid.size() + " 个；当前共 " + store.size() + " 人。");

		if (!invalid.isEmpty()) {
			info(source, "以下名称无效：" + String.join("、", invalid));
		}

		return added > 0 ? 1 : 0;
	}

	private static int removeAll(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();
		final List<String> names = splitNames(StringArgumentType.getString(context, "names"));

		if (names.isEmpty()) {
			return fail(source, "没有可移除的玩家名。");
		}

		int removed = 0;
		final List<String> missing = new ArrayList<>();

		for (String name : names) {
			if (store.remove(name)) {
				removed++;
			} else {
				missing.add(name);
			}
		}

		info(source, "批量移除完成：移除 " + removed + " 人，未找到 " + missing.size()
				+ " 人；当前共 " + store.size() + " 人。");

		if (!missing.isEmpty()) {
			info(source, "以下玩家不在白名单中：" + String.join("、", missing));
		}

		return removed > 0 ? 1 : 0;
	}

	/**
	 * Splits a raw argument on commas (half/full width) and whitespace, dropping
	 * case-insensitive duplicates while keeping the first spelling.
	 */
	private static List<String> splitNames(String raw) {
		final Map<String, String> unique = new LinkedHashMap<>();

		for (String part : raw.replace('，', ',').replace('；', ',').replace(';', ',').split("[,\\s]+")) {
			final String name = part.trim();

			if (!name.isEmpty()) {
				unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
			}
		}

		return new ArrayList<>(unique.values());
	}

	private static int check(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();
		final String name = StringArgumentType.getString(context, "name").trim();

		if (!store.isEnabled()) {
			info(source, "白名单当前已关闭，所有玩家都可以加入。");
			return 1;
		}

		if (store.isWhitelisted(name)) {
			info(source, name + " 在白名单中。");
		} else {
			info(source, name + " 不在白名单中（加入时会被拒绝）。");
		}

		return 1;
	}

	private static int setEnabled(CommandContext<CommandSourceStack> context, WhitelistStore store, boolean value) {
		final CommandSourceStack source = context.getSource();
		store.setEnabled(value);

		info(source, "白名单已" + (value ? "开启" : "关闭") + "。");

		if (value && store.size() == 0) {
			info(source, "注意：名单为空且“空名单放行”为"
					+ (store.allowWhenEmpty() ? "开启" : "关闭")
					+ "状态，请及时添加玩家。");
		}

		return 1;
	}

	private static int setAllowWhenEmpty(CommandContext<CommandSourceStack> context, WhitelistStore store, boolean value) {
		final CommandSourceStack source = context.getSource();
		store.setAllowWhenEmpty(value);
		info(source, "空名单放行已" + (value ? "开启" : "关闭") + "。");
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();
		store.load();
		info(source, "已重新加载白名单："
				+ (store.isEnabled() ? "已开启" : "已关闭")
				+ "，共 " + store.size() + " 人。");
		return 1;
	}

	private static int save(CommandContext<CommandSourceStack> context, WhitelistStore store) {
		final CommandSourceStack source = context.getSource();
		store.save();
		info(source, "已保存白名单到 " + store.file());
		return 1;
	}

	private static void info(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(message), false);
	}

	private static int fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		return 0;
	}
}
