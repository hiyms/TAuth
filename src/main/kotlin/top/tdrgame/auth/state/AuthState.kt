package top.tdrgame.auth.state

/**
 * 玩家认证会话状态。
 *
 * 每个需要认证的在线玩家持有一个 [AuthState] 实例，
 * 由 [AuthStateMachine] 管理，仅存于服务端内存，不持久化。
 */
sealed class AuthState {

    /**
     * 刚进入服务器，等待登录或注册。
     * @param joinTime 进入时的 [System.currentTimeMillis]，用于超时判定。
     */
    data class Pending(val joinTime: Long) : AuthState()

    /**
     * 正在验证密码哈希。无论是服务端计算 PBKDF2 还是
     * 等待客户端 [ChallengeResponsePacket]，都暂存于此状态。
     */
    object Authenticating : AuthState()

    /**
     * 已验证通过，正常游戏。
     * @param loginTime 验证通过时间。
     */
    data class Authenticated(val loginTime: Long) : AuthState()

    /**
     * 超时或错误次数过多，即将被踢出。
     * @param reason 发送给玩家的断开连接提示。
     */
    data class TimedOut(val reason: String) : AuthState()
}
