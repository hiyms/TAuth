package top.tdrgame.auth.state

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import top.tdrgame.auth.config.AuthConfig
import top.tdrgame.auth.i18n.I18nKeys
import top.tdrgame.auth.i18n.ServerI18n

/**
 * 每个在线玩家的认证状态管理器。
 *
 * 负责状态转移、超时检测和密码错误计数。
 * 所有数据仅存于服务端内存 — 重启后清除是预期行为。
 *
 * 放行规则（与 TASK 对齐）：
 * - 首次登录（无论正版/离线）都必须 /register。
 * - 已注册且曾经正版验证过的玩家，仅当本次也是正版登录时免 /login。
 * - 离线/盗版登录始终需要 /login。
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
        if (shouldAutoPass(isPremium, isVerified, isRegistered)) {
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
            state = AuthState.TimedOut(I18nKeys.LOGIN_TIMEOUT)
            return
        }

        remindTick++
        if (remindTick >= 100) {
            remindTick = 0
            if (!isRegistered) {
                player.sendSystemMessage(ServerI18n.text(I18nKeys.FIRST_JOIN_REGISTER).withStyle(net.minecraft.ChatFormatting.RED))
            } else {
                player.sendSystemMessage(ServerI18n.text(I18nKeys.PROMPT_LOGIN).withStyle(net.minecraft.ChatFormatting.YELLOW))
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
            state = AuthState.TimedOut(I18nKeys.TOO_MANY_FAILURES)
            return true
        }
        state = AuthState.Pending(System.currentTimeMillis())
        return false
    }

    fun isAuthenticated(): Boolean = state is AuthState.Authenticated

    fun shouldKick(): Boolean = state is AuthState.TimedOut

    fun kickReason(): String = (state as? AuthState.TimedOut)?.reason ?: I18nKeys.UNKNOWN_KICK

    companion object {
        /** 纯策略：只有已注册、历史正版验证、本次正版登录三者同时满足才可自动放行。 */
        fun shouldAutoPass(isPremium: Boolean, isVerified: Boolean, isRegistered: Boolean): Boolean =
            isRegistered && isPremium && isVerified
    }
}
