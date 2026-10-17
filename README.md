# Whitelist Plus

适用于 **Minecraft 26.1.2 / Fabric** 的白名单模组：服务端强制校验，OP 通过 `/wl` 指令管理名单。

同一个 jar 既可以装在专用服务器，也可以装在客户端（客户端安装不会产生副作用，白名单完全由服务端判定）。

## 功能特点

- **登录阶段拦截**：通过 Mixin 注入 `PlayerList#canPlayerLogin`，未在白名单的玩家在进入游戏前就会被断开，并看到自定义提示，而不是先进入世界再被踢出。
- **按玩家名匹配**（不区分大小写）：正版 / 离线模式服务器都能直接使用，不依赖 Mojang 皮肤 / UUID 查询。
- **OP 指令管理**：所有 `/wl` 子指令都要求 3 级管理员权限（`COMMANDS_ADMIN`），普通玩家不可见、不可用；控制台与 RCON 同样可以执行。
- **自动持久化**：每次增删改都会立即原子写入 `config/whitelistplus/whitelist.json`，文件损坏时自动备份并重建。
- **空名单保护**：默认「名单为空时放行所有人」（`allowWhenEmpty`），避免误开白名单把包括自己在内的所有玩家挡在门外；可随时关闭。
- **与 vanilla 白名单互不干扰**：模组使用自己的名单文件，和原版 `/whitelist`、`whitelist.json` 相互独立。

## 兼容性

| 组件 | 版本 |
| --- | --- |
| Minecraft | **26.1.2** |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | **0.155.3+26.1.2**（必需） |
| Java | 25 |

## 安装

1. 服务端（以及需要安装的客户端）安装 Fabric Loader 0.19.5 或更高版本。
2. 将 [Fabric API](https://modrinth.com/mod/fabric-api) 和 `whitelistplus-1.0.0.jar` 放入 `mods/` 目录。
3. 启动服务器，首次启动会生成 `config/whitelistplus/whitelist.json`。
4. 在服务器控制台执行 `/wl add <你的名字>`，再执行 `/wl on` 开启白名单。

> 建议先把自己加入名单，再开启白名单。

## 指令

所有指令都需要 **3 级管理员（OP）权限**。

| 指令 | 说明 |
| --- | --- |
| `/wl` 或 `/wl help` | 显示帮助 |
| `/wl status` | 查看开关、人数、存储路径、拒绝提示 |
| `/wl list [页码]` | 分页列出白名单玩家 |
| `/wl add <玩家名>` | 添加玩家（支持离线玩家，可提前添加） |
| `/wl remove <玩家名>` | 移除玩家 |
| `/wl check <玩家名>` | 查询某玩家是否在白名单中 |
| `/wl on` / `/wl off` | 开启 / 关闭白名单（只影响之后的登录） |
| `/wl empty on` / `/wl empty off` | 空名单时是否放行所有人 |
| `/wl reload` | 从磁盘重新加载配置 |
| `/wl save` | 立即保存到磁盘（通常无需手动执行） |

基岩版（Geyser）玩家名可能含有空格，请使用引号，例如：

```
/wl add "Bedrock Player"
```

## 配置文件

路径：`config/whitelistplus/whitelist.json`

```json
{
  "enabled": true,
  "allowWhenEmpty": true,
  "kickMessage": "你不在本服务器的白名单中。\n请联系管理员申请加入。",
  "entries": [
    {
      "name": "Steve",
      "addedBy": "Server",
      "addedAt": 1780000000000
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `enabled` | 是否开启白名单（也可用 `/wl on/off`） |
| `allowWhenEmpty` | 名单为空时是否放行所有人，默认 `true` |
| `kickMessage` | 被拒绝时显示的文字，`\n` 表示换行 |
| `entries[].name` | 玩家名（匹配时不区分大小写） |
| `entries[].addedBy` | 添加者（执行指令的人或控制台） |
| `entries[].addedAt` | 添加时间（毫秒时间戳） |

## 行为说明

- 白名单**只影响新登录**。执行 `/wl on` 或 `/wl remove` 时，已经在线的玩家不会被踢出；他们下次登录时才会被判断。
- 玩家名匹配不区分大小写；离线模式与正版模式使用同一套逻辑。
- 文件写入采用「临时文件 + 原子替换」，服务器异常关闭也不会写出半个 JSON。
- 如果文件损坏，模组会把它备份成 `whitelist.json.broken-<时间戳>`，然后以安全的默认值（白名单关闭）重建，避免把服务器锁死。
- 忘记把自己加进名单而被挡住时：
  1. 在服务器控制台执行 `/wl add <你的名字>`（控制台总是有权限），或
  2. 直接编辑 `whitelist.json` 把 `enabled` 改为 `false`，再执行 `/wl reload`。

## 构建

项目已附带 Gradle Wrapper（Gradle 9.7.1），需要 **JDK 25**：

```bash
./gradlew build
```

产物：`build/libs/whitelistplus-1.0.0.jar`（Windows 使用 `gradlew.bat`）。

也可以直接把仓库推到 GitHub，`.github/workflows/build.yml` 会在每次 push / PR 时自动构建，并在 Actions 页面的 **Artifacts** 中提供 `whitelistplus-jar`。

## 安全提示

- 本模组按玩家名识别，**离线模式服务器无法防止冒名**（这是离线模式本身的限制，与模组无关）；正版服务器中玩家名唯一，按名匹配是安全的。
- 白名单文件位于服务器配置目录，请做好备份并限制文件权限。

## 许可证

MIT
