# TAuth — Minecraft Forge 1.20.1 Offline Player Authentication

[English](#english) | [中文](#chinese)

## English

TAuth is a **server-side** (client-optional) Minecraft Forge 1.20.1 mod that authenticates offline (cracked) players via password verification.

### Features

- 🔐 PBKDF2WithHmacSHA256 password hashing (cryptographically secure)
- 🖥️ Server-required, client-optional — works with or without client installation
- 📋 `/login`, `/register`, `/resetpasswd` (OP-only) commands
- ⏰ Configurable login timeout and max fail attempts
- 🔄 Seamless migration from `offlineauth` and `TrueUUID`
- 🎨 LDLib-based login/register GUI when client is installed
- 🔑 Auto-login via machine ID + IP (client-side)
- ⚡ Minimal performance overhead — entire mod skips when `auth.enabled = false`

### Requirements

- Minecraft Forge 1.20.1 (47.2.0+)
- KotlinForForge 4.11.0+
- Optional: [LDLib](https://github.com/Low-Drag-MC/LDLib) (for client GUI)
- Optional: [TrueUUID](https://github.com/alini/TrueUUID) (for premium detection)

### Configuration

Dedicated/server-world config is loaded from the world `serverconfig/tauth-server.toml` copy after Forge syncs SERVER configs. The default template is generated as `config/tauth-server.toml`.

```toml
[auth]
enabled = false           # Enable authentication
loginTimeoutSeconds = 90  # Time before kicking unauthenticated players
maxFailAttempts = 5       # Max wrong password attempts
```

### Commands

| Command | Description | Permission |
|---|---|---|
| `/register <password> <confirm>` | Register a new account | Player |
| `/login <password>` | Login to an existing account | Player |
| `/resetpasswd <player>` | Reset a player's password | OP (level 2) |

### Development

```bash
./gradlew build
```

### Migration

When first enabling `auth.enabled`, TAuth automatically migrates data from:
- `offlineauth` (`config/offlineauth/auth_hash.json`)
- `TrueUUID` (premium player list)

## Chinese

TAuth 是一个 Minecraft Forge 1.20.1 的**服务器端**（客户端选装）认证模组，为离线（盗版）玩家提供密码验证保护。

### 功能

- 🔐 PBKDF2WithHmacSHA256 密码安全哈希
- 🖥️ 服务器必装，客户端选装
- 📋 `/login`、`/register`、`/resetpasswd`（OP 专用）命令
- ⏰ 可配置的登录超时和最大错误尝试次数
- 🔄 从 `offlineauth` 和 `TrueUUID` 无缝迁移数据
- 🎨 客户端安装后提供 LDLib 登录/注册界面
- 🔑 基于机器 ID + IP 的自动登录
- ⚡ 极低性能开销 — `auth.enabled = false` 时模组完全跳过

### 配置

专用服务端/世界配置以 Forge SERVER config 形式使用：实际生效文件为世界目录下的 `serverconfig/tauth-server.toml`，默认模板会生成到 `config/tauth-server.toml`。

### 许可证

MIT
