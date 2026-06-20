package top.tdrgame.auth.server

import org.dizitart.no2.Nitrite
import org.dizitart.no2.filters.Filter
import org.dizitart.no2.filters.FluentFilter
import org.dizitart.no2.mvstore.MVStoreModule
import org.dizitart.no2.repository.ObjectRepository
import top.tdrgame.auth.config.AuthConfig

/**
 * 存储在 Nitrite 数据库中的玩家认证数据。
 */
data class PlayerAuthData(
    /** 主键：玩家名（大小写按游戏内实际名称存储）。 */
    val playerName: String,
    /** PBKDF2 哈希结果。新条目为 "v1:iter:keyBits:saltB64:hashB64"；迁移自 offlineauth 的为 "saltB64:hashB64"。 */
    val passwordHash: String,
    /** 是否已证明拥有正版账号。true 时仅正版进入可免验证，离线进入仍需要 /login。 */
    val verified: Boolean = false,
    /** 上次登录类型："online" 或 "offline"，null 表示从未登录过。 */
    val lastLoginType: String? = null,
    /** 客户端自动登录绑定的机器 ID。 */
    val autoLoginMachineId: String? = null,
    /** 客户端自动登录绑定的上次来源 IP（服务端观察值）。 */
    val autoLoginIp: String? = null
)

/**
 * 密码持久化存储，封装 Nitrite v4 的 CRUD 操作。
 *
 * 使用 Nitrite 的 Java API（不使用 potassium-nitrite，避免版本兼容问题）。
 *
 * 哈希算法为 PBKDF2WithHmacSHA256，迭代次数 / 密钥长度 / 加盐字节数由
 * [AuthConfig] 提供（默认与 offlineauth 兼容：10000 / 256 / 16）。
 *
 * 存储格式（TAuth 自身写入）：
 * ```
 * v1:<iterations>:<keyBits>:<base64(salt)>:<base64(hash)>
 * ```
 * 验证时兼容两种来源：
 * - 带 `v1:` 前缀的本模组条目，参数取自条目本身；
 * - 无前缀的旧格式 `base64(salt):base64(hash)`（offlineauth），按默认参数解析。
 *
 * 数据文件：config/tauth/auth.db
 */
class PasswordStorage {

    // 延迟打开数据库：客户端选装时构造本对象不会创建 auth.db，
    // 只有服务端真正访问 repo 时才落盘。
    private val repo: ObjectRepository<PlayerAuthData> by lazy { openRepo() }

    companion object {
        /** offlineauth 默认加盐字节数。 */
        const val LEGACY_SALT_BYTES = 16
    }

    private var db: Nitrite? = null

    private fun openRepo(): ObjectRepository<PlayerAuthData> {
        // Nitrite 不会自动创建父目录
        java.io.File("config/tauth").mkdirs()
        // Nitrite v4 默认使用 SimpleNitriteMapper，无需显式加载 Jackson mapper
        val opened = Nitrite.builder()
            .loadModule(MVStoreModule("config/tauth/auth.db"))
            .openOrCreate()
        db = opened
        return opened.getRepository(PlayerAuthData::class.java)
    }

    fun isRegistered(playerName: String): Boolean {
        return repo.find(byName(playerName)).firstOrNull() != null
    }

    /**
     * 注册新玩家，密码以 PBKDF2 哈希存储。
     */
    fun register(playerName: String, password: String, verified: Boolean = false, loginType: String? = null) {
        repo.insert(
            PlayerAuthData(
                playerName = playerName,
                passwordHash = hashPassword(password),
                verified = verified,
                lastLoginType = loginType
            )
        )
    }

    /**
     * 验证密码是否正确。
     */
    fun checkPassword(playerName: String, password: String): Boolean {
        val data = repo.find(byName(playerName)).firstOrNull() ?: return false
        return verifyPassword(password, data.passwordHash)
    }

    /**
     * 修改密码（旧密码已在调用方校验通过）。
     */
    fun changePassword(playerName: String, newPassword: String) {
        val data = repo.find(byName(playerName)).firstOrNull() ?: return
        repo.update(data.copy(passwordHash = hashPassword(newPassword)))
    }

    /** 删除玩家所有认证数据（由 /resetpasswd 触发）。 */
    fun delete(playerName: String) {
        repo.remove(byName(playerName))
    }

    fun get(playerName: String): PlayerAuthData? {
        return repo.find(byName(playerName)).firstOrNull()
    }

    /**
     * 取出已存储哈希的解析结果（iterations / keyBits / salt / 期望哈希），
     * 供服务端挑战-响应校验使用。未注册或格式损坏返回 null。
     */
    fun getHashParams(playerName: String): PasswordHasher.ParsedHash? {
        val data = repo.find(byName(playerName)).firstOrNull() ?: return null
        return PasswordHasher.parse(data.passwordHash)
    }

    /**
     * 客户端已在本地完成 PBKDF2 并提交哈希字符串，服务端校验格式后落库。
     * @return 是否注册成功（已存在或格式非法则返回 false）。
     */
    fun registerWithHash(playerName: String, passwordHash: String, verified: Boolean = false, loginType: String? = null): Boolean {
        if (repo.find(byName(playerName)).firstOrNull() != null) return false
        if (PasswordHasher.parse(passwordHash) == null) return false
        repo.insert(PlayerAuthData(
            playerName = playerName,
            passwordHash = passwordHash,
            verified = verified,
            lastLoginType = loginType
        ))
        return true
    }

    /** 该玩家是否曾被标记为正版验证过（即有过正版登录历史）。 */
    fun hasPremiumHistory(playerName: String): Boolean =
        repo.find(byName(playerName)).firstOrNull()?.verified == true

    /** 更新 verified 标志和登录类型。verified 为曾经正版验证过，离线登录不会清除它。 */
    fun updateVerification(playerName: String, isPremium: Boolean, loginType: String) {
        val data = repo.find(byName(playerName)).firstOrNull() ?: return
        repo.update(data.copy(verified = data.verified || isPremium, lastLoginType = loginType))
    }

    /** 记录/更新自动登录绑定信息。 */
    fun updateAutoLogin(playerName: String, machineId: String?, ip: String?) {
        val data = repo.find(byName(playerName)).firstOrNull() ?: return
        repo.update(data.copy(autoLoginMachineId = machineId, autoLoginIp = ip))
    }

    fun isAutoLoginAllowed(playerName: String, machineId: String?, ip: String): Boolean {
        if (machineId.isNullOrBlank()) return false
        val data = repo.find(byName(playerName)).firstOrNull() ?: return false
        return data.autoLoginMachineId == machineId && data.autoLoginIp == ip
    }

    /** 获取所有已注册玩家名（迁移遍历用）。 */
    fun allPlayerNames(): List<String> {
        val result = mutableListOf<String>()
        repo.find().toList().forEach { result.add(it.playerName) }
        return result
    }

    /** 数据库是否为空，用于判断是否需要执行迁移。 */
    fun isEmpty(): Boolean = !repo.find().iterator().hasNext()

    /** 直接插入一条数据（迁移用）。 */
    fun insertRaw(data: PlayerAuthData) {
        repo.insert(data)
    }

    fun hashPolicy(): HashPolicy = HashPolicy(
        saltBytes = AuthConfig.saltBytes.get().coerceIn(LEGACY_SALT_BYTES, 64),
        iterations = AuthConfig.iterations.get().coerceAtLeast(1000),
        keyLengthBits = AuthConfig.keyLengthBits.get().coerceAtLeast(128)
    )

    data class HashPolicy(val saltBytes: Int, val iterations: Int, val keyLengthBits: Int)

    private fun byName(name: String): Filter =
        FluentFilter.where("playerName").eq(name)

    /** 用 [AuthConfig] 配置参数生成哈希。算法实现见 [PasswordHasher]。 */
    private fun hashPassword(password: String): String {
        val policy = hashPolicy()
        return PasswordHasher.hash(password, policy.saltBytes, policy.iterations, policy.keyLengthBits)
    }

    private fun verifyPassword(input: String, stored: String): Boolean =
        PasswordHasher.verify(input, stored)

    fun close() { db?.close() }
}
