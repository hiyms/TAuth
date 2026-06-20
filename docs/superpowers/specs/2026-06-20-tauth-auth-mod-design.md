# TAuth - Minecraft Forge 1.20.1 认证模组设计文档

## 概述

TAuth 是一个 Minecraft Forge 1.20.1 的服务器端认证模组（客户端选装），为离线（盗版）玩家提供密码验证保护。模组使用 Kotlin 编写，密码通过 PBKDF2WithHmacSHA256 安全哈希存储，数据持久化使用 Nitrite v4 嵌入式数据库。

**规格：** 服务器必装，客户端选装。

## 项目结构

```
src/main/kotlin/top/tdrgame/auth/
├── TAuth.kt                    # @Mod 入口，总线注册
├── config/
│   └── AuthConfig.kt           # Forge ModConfig TOML 配置
├── state/
│   ├── AuthState.kt            # sealed class 状态枚举
│   └── AuthStateMachine.kt     # 每玩家状态转移逻辑
├── server/
│   ├── AuthManager.kt          # 全局认证管理器（loggedIn 集合、超时检测）
│   ├── EventHandler.kt         # @SubscribeEvent 事件拦截层
│   ├── CommandHandler.kt       # /login /register /resetpasswd
│   ├── PasswordStorage.kt      # Nitrite ObjectRepository 封装
│   ├── MigrationService.kt     # 从 offlineauth/TrueUUID 迁移数据
│   └── SafeRespawn.kt          # 死亡后重生到出生点
├── client/
│   ├── ClientAuthHandler.kt    # 客户端网络包处理
│   ├── LoginScreen.kt          # LDLib 登录界面
│   ├── RegisterScreen.kt       # LDLib 注册界面
│   └── AutoLoginManager.kt     # IP + machineId 自动登录
└── network/
    └── AuthPackets.kt          # 挑战-响应网络包定义

src/main/java/top/tdrgame/auth/mixin/
├── CommandMixin.java           # 拦截未认证玩家的命令执行
└── PlayerRespawnMixin.java     # 死亡退出后重生处理
```

## 依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| kotlinforforge | 4.11.0 | Kotlin 语言支持 |
| LDLib | latest | 客户端 GUI 框架（compileOnly） |
| TrueUUID | latest | 正版判断 API `TrueuuidApi.isPremium(name)`（compileOnly） |
| nitrite-bom | 4.x | Nitrite 版本管理 |
| nitrite | 4.x | NoSQL 嵌入式文档数据库 |
| nitrite-mvstore-adapter | 4.x | 文件持久化引擎 |
| potassium-nitrite | 4.x | Nitrite Kotlin 扩展 |

构建系统使用 Gradle Kotlin DSL (`build.gradle.kts`)。

## 核心架构

### 数据模型

```kotlin
data class PlayerAuthData(
    @Id val playerName: String,       // 主键，玩家名
    val passwordHash: String,         // PBKDF2 格式 "saltBase64:hashBase64"
    val verified: Boolean = false,    // 是否已证明拥有正版账号
    val lastLoginType: String? = null // 上次登录类型 "online" / "offline"
)
```

### 认证状态机

```kotlin
sealed class AuthState {
    /** 刚进入服务器，等待登录或注册 */
    data class Pending(val joinTime: Long) : AuthState()

    /** 等待客户端哈希计算结果 */
    object Authenticating : AuthState()

    /** 已验证通过，正常游戏 */
    data class Authenticated(val loginTime: Long) : AuthState()

    /** 超时或错误过多，即将踢出 */
    data class TimedOut(val reason: String) : AuthState()
}
```

**状态转移：**

```
PENDING ──[玩家提交登录/注册命令]──▶ AUTHENTICATING
PENDING ──[超时]──────────────────▶ TIMED_OUT → 踢出
PENDING ──[已是正版+verified=true]─▶ AUTHENTICATED (跳过验证)
PENDING ──[收到客户端ChallengeRequestPacket]──▶ AUTHENTICATING

AUTHENTICATING ──[哈希匹配]────▶ AUTHENTICATED (登录成功，failCount 清零)
AUTHENTICATING ──[哈希不匹配]──▶ PENDING (failCount++)
```

### 认证流程规则

1. **纯离线玩家：** 首次进入 `/register <pass> <pass>` → 之后每次 `/login <pass>`
2. **纯正版玩家（从未离线）：** TrueUUID 判定为 premium → 自动标记 `verified=true` → 永不需要验证
3. **离线玩家首次正版进入：** 需 `/login` 验证一次 → `verified=true` → 之后正版进入**免验证**，但离线进入**仍需** `/login`
4. **正版玩家想离线进入：** 先正版登录状态下 `/register` 设置密码 → 离线模式下 `/login` 密码登录
5. **OP `/resetpasswd <player>`：** 清除密码 + 重置 `verified` → 该玩家无论何种方式进入都需要重新验证

## 密码存储

- **算法：** PBKDF2WithHmacSHA256
- **参数：** 迭代 10000 次，密钥长度 256 位，随机 16 字节 salt
- **存储格式：** `Base64(salt):Base64(hash)` — 与 offlineauth 的 `auth_hash.json` 格式完全兼容
- **持久化：** Nitrite `ObjectRepository<PlayerAuthData>`，数据文件位于 `config/tauth/auth.db`

密码哈希始终在服务端计算。客户端安装模组时通过挑战-响应协议验证，密码原文**不出客户端**。

## 网络协议（客户端安装时）

当玩家处于 Pending 状态、服务端检测到客户端安装了 TAuth 时，自动走网络验证流程。
纯服务端模式下（客户端未装 TAuth），玩家通过 `/login` `/register` 命令提交密码，服务端直接计算 PBKDF2 验证。

```
服务端                          客户端
  │                               │
  │── LoginRequestPacket ────────▶│  提示需要登录
  │                               │
  │◀── ChallengeRequestPacket ────│  请求挑战码
  │                               │
  │── ChallengePacket ───────────▶│  随机 challenge + 玩家 salt
  │                               │
  │       客户端计算:
  │       saltedHash = PBKDF2(password, salt)
  │       response = SHA-256(saltedHash + challenge)
  │                               │
  │◀── ChallengeResponsePacket ───│  返回 response
  │                               │
  │  服务端计算:
  │  expected = SHA-256(storedPasswordHash + challenge)
  │  比对: expected == response ?
  │── LoginResultPacket ─────────▶│  验证结果
```

客户端计算出 `saltedHash`（PBKDF2 结果）后，将明文密码从内存中丢弃。

## 事件拦截

未认证玩家（`AuthState != Authenticated`）受以下限制：

| 拦截目标 | Forge 事件 | 行为 |
|---|---|---|
| 聊天 | `ServerChatEvent` | 取消，提示未登录 |
| 方块破坏 | `BlockEvent.BreakEvent` | 取消 |
| 方块放置 | `BlockEvent.EntityPlaceEvent` | 取消 |
| 物品丢弃 | `ItemTossEvent` | 取消 |
| 物品拾取 | `EntityItemPickupEvent` | 取消 |
| 物品/方块/实体交互 | `PlayerInteractEvent` | 取消 |
| 容器打开 | `PlayerContainerEvent.Open` | 关闭容器 |
| 受伤 | `LivingDamageEvent` | 取消 |
| 死亡 | `LivingDeathEvent` | 防止掉落物品 |
| 移动 | `PlayerTickEvent` | teleport 锁定进入坐标 |
| 指令 | `CommandMixin` | 仅允许 `/login` `/register` 及少量白名单指令 |
| 背包 | 进入时 | `clearContent()`，登录后恢复 |

进入时 `setInvulnerable(true)`，登录成功后恢复 `false`。

## 超时与容错

- **登录超时：** Pending 状态超过 `loginTimeoutSeconds`（默认 90 秒）→ 踢出
- **密码错误：** 在内存中计数（不持久化），达到 `maxFailAttempts`（默认 5 次）→ 踢出
- **提示频率：** 每 100 tick（5 秒）发送一次登录/注册提示消息

## 死亡重生处理

玩家在 Pending 状态下死亡 → `PlayerRespawnMixin` 拦截 → `SafeRespawn` 强制重生到世界出生点 / 玩家床 → 回到 Pending 状态继续要求登录。

## 客户端功能

### LDLib 登录/注册界面

- `LoginScreen`：`TextFieldWidget`（密码输入，masked）+ `ButtonWidget`（登录）
- `RegisterScreen`：两个 `TextFieldWidget`（密码 + 确认密码）+ `ButtonWidget`（注册）
- 触发：收到 `LoginRequestPacket` → 弹出对应界面

### 自动登录

客户端本地存储（NBT 格式 `config/tauth/autologin.dat`）：

```kotlin
data class AutoLoginData(
    val machineId: UUID,       // 首次启动生成的随机 UUID，标识这台机器
    val saltedHash: ByteArray, // PBKDF2 计算结果缓存（避免每次输入密码）
    val lastIp: String         // 上次登录 IP
)
```

首次登录成功后，客户端将 `saltedHash`（PBKDF2 结果，不是明文密码）缓存到本地。
下次进入时：IP 不变 + machineId 不变 + 缓存存在 → 自动走挑战-响应流程（用缓存的 `saltedHash` 计算 response，无需用户输入密码）。
验证失败则清除缓存，要求手动登录。

## 数据迁移

在配置首次启用验证时（`auth.enabled` 从 `false` 变为 `true`）自动执行：

1. **offlineauth 迁移：** 检测 `config/offlineauth/auth_hash.json` → 逐条写入 Nitrite repo（格式兼容） → 旧文件重命名为 `.migrated`
2. **TrueUUID 迁移：** 调用 `TrueuuidApi.isPremium(name)` 遍历已知正版玩家 → 标记 `verified=true`
3. 迁移完成后写 `.migrated` 标记文件，防止重复执行

## 配置

使用 Forge ModConfig TOML 格式（`config/tauth-server.toml`）：

```toml
[auth]
enabled = false
# 登录超时时间（秒）
loginTimeoutSeconds = 90
# 密码错误最大尝试次数
maxFailAttempts = 5
```

`auth.enabled = false` 时整个模组逻辑跳过，性能零开销。

## 边界情况

| 情况 | 处理方式 |
|---|---|
| 玩家死亡后退出服务器再进入 | 先生成到出生点，再要求登录 |
| 离线玩家首次正版验证 | 需 `/login` 一次，之后正版免验证 |
| 正版玩家离线登录 | 先正版状态 `/register` 设密码，再离线 `/login` |
| OP 执行 `/resetpasswd` | 清除密码 + `verified` 标志，下次必须重新验证 |
| 多次密码错误 | 内存计数，达到上限直接踢出 |
| 配置禁用整个模组 | 跳过所有事件/命令注册，零性能开销 |
| 首次启用时旧数据存在 | 一次性迁移，标记文件防重复 |
| Nitrite 数据库损坏 | 重建空库，玩家需重新注册 |

## 代码风格

- **所有公开 API 必须有 KDoc 文档注释**，说明用途、参数、返回值
- **普通注释解释「为什么」而不是「做了什么」**，适量添加
- **禁止分隔线注释**（如 `// ==== 我是分隔线 ====` 的形式）
- Kotlin 代码使用官方代码风格，Java Mixin 遵循 Java 惯例

## 测试策略

- JUnit 5 单元测试：`AuthStateMachine` 状态转移逻辑、`PasswordStorage` CRUD 操作
- 手动游戏内测试：各认证流程（离线注册/登录、正版验证、死亡重生、指令拦截、超时踢出）

## 文档与 CI

- `README.md`：中英双语，介绍功能、用法、配置
- `.github/workflows/build.yml`：Gradle build + test on push/PR
