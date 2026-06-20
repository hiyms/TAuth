package top.tdrgame.auth.state

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import top.tdrgame.auth.config.AuthConfig

/**
 * 每个在线玩家的认证状态管理器。
 *
 * 负责状态转移、超时检测和密码错误计数。
 * 所有数据仅存于服务端内存 — 重启后清除是预期行为。
 *
 * 放行规则（与 TASK 对齐）：
 * - 正版全新玩家（isPremium=true 且从未注册）→ 永不需要验证，直接放行。
 * - 任何已验证玩家（isVerified=true）→ 直接放行。涵盖「首次正版登录后永久免验证」
 *   以及「曾在正版状态下 register、之后离线登录」两种情形。
 * - 其余情况进入 [AuthState.Pending]，等待 /login 或 /register。
 */
class AuthStateMachine(
    private val player: ServerPlayer,
    private val isPremium: Boolean,
    private val isVerified: Boolean,
    private val isRegistered: Boolean
) {
    var state: AuthState = AuthState.Pending(System.currentTimeMillis())
        private set

    /** 连续密码错误次数。 */
    var failCount: Int = 0
        private set

    /** 登录/注册提示消息的 tick 计数器。 */
    private var remindTick: Int = 0

    init {
        // 正版全新玩家：从未注册过，本轮为正版登录 → 免验证。
        // 已验证玩家：无论正版/离线，历史已通过验证 → 免验证。
        val shouldAutoPass = (isPremium && !isRegistered) || isVerified
        if (shouldAutoPass) {
            state = AuthState.Authenticated(System.currentTimeMillis())
        }
    }

    /**
     * 每 tick 由 [AuthManager] 调度。
     */
    fun tick() {
        val pending = state as? AuthState.Pending ?: return
        val elapsed = (System.currentTimeMillis() - pending.joinTime) / 1000

        if (elapsed > AuthConfig.loginTimeoutSeconds.get()) {
            state = AuthState.TimedOut("§c登录超时，你已被踢出！")
            return
        }

        remindTick++
        if (remindTick >= 100) {
            remindTick = 0
            if (!isRegistered) {
                player.sendSystemMessage(Component.literal(
                    "§c首次进服，请使用 /register 密码 确认密码 注册账户！"))
            } else {
                player.sendSystemMessage(Component.literal(
                    "§e请使用 /login 密码 登录账户！"))
            }
        }
    }

    /** 密码验证成功。 */
    fun onLoginSuccess() {
        state = AuthState.Authenticated(System.currentTimeMillis())
        failCount = 0
    }

    /**
     * 密码验证失败。
     * @return true 表示已达到最大尝试次数，应踢出。
     */
    fun onLoginFail(): Boolean {
        failCount++
        if (failCount >= AuthConfig.maxFailAttempts.get()) {
            state = AuthState.TimedOut("§c密码错误次数过多，你已被踢出！")
            return true
        }
        state = AuthState.Pending(System.currentTimeMillis())
        return false
    }

    fun isAuthenticated(): Boolean = state is AuthState.Authenticated

    fun shouldKick(): Boolean = state is AuthState.TimedOut

    fun kickReason(): String = (state as? AuthState.TimedOut)?.reason ?: "Unknown"
}
