package top.tdrgame.auth.server

import org.dizitart.no2.Nitrite
import org.dizitart.no2.mvstore.MVStoreModule
import org.dizitart.no2.repository.ObjectRepository
import org.dizitart.no2.filters.Filter
import org.dizitart.no2.filters.FluentFilter
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 存储在 Nitrite 数据库中的玩家认证数据。
 */
data class PlayerAuthData(
    /** 主键：玩家名（大小写按游戏内实际名称存储）。 */
    val playerName: String,
    /** PBKDF2 哈希结果，格式 "saltBase64:hashBase64"。与 offlineauth 兼容。 */
    val passwordHash: String,
    /** 是否已证明拥有正版账号。true 时正版进入免验证，离线进入仍需要 /login。 */
    val verified: Boolean = false,
    /** 上次登录类型："online" 或 "offline"，null 表示从未登录过。 */
    val lastLoginType: String? = null
)

/**
 * 密码持久化存储，封装 Nitrite v4 的 CRUD 操作。
 *
 * 使用 Nitrite 的 Java API（不使用 potassium-nitrite，避免版本兼容问题）。
 *
 * PBKDF2WithHmacSHA256 参数与 offlineauth 完全一致：
 * - 迭代次数 10000，密钥长度 256 位
 * - 随机 16 字节 salt
 * - 存储格式 Base64(salt):Base64(hash)
 *
 * 数据文件：config/tauth/auth.db
 */
class PasswordStorage {

    private val db: Nitrite
    private val repo: ObjectRepository<PlayerAuthData>

    companion object {
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATIONS = 10000
        private const val KEY_LENGTH = 256
        private const val SALT_BYTES = 16
        private const val SALT_SEPARATOR = ":"
    }

    init {
        // Nitrite 不会自动创建父目录
        java.io.File("config/tauth").mkdirs()

        // Nitrite v4 默认使用 SimpleNitriteMapper，无需显式加载 Jackson mapper
        db = Nitrite.builder()
            .loadModule(MVStoreModule("config/tauth/auth.db"))
            .openOrCreate()

        repo = db.getRepository(PlayerAuthData::class.java)
    }

    fun isRegistered(playerName: String): Boolean {
        return repo.find(byName(playerName)).firstOrNull() != null
    }

    /**
     * 注册新玩家，密码以 PBKDF2 哈希存储。
     */
    fun register(playerName: String, password: String) {
        repo.insert(
            PlayerAuthData(
                playerName = playerName,
                passwordHash = hashPassword(password),
                verified = false
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

    /** 更新 verified 标志和登录类型。 */
    fun updateVerification(playerName: String, verified: Boolean, loginType: String) {
        val data = repo.find(byName(playerName)).firstOrNull() ?: return
        repo.update(data.copy(verified = verified, lastLoginType = loginType))
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

    private fun byName(name: String): Filter =
        FluentFilter.where("playerName").eq(name)

    private fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val hash = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        return "${Base64.getEncoder().encodeToString(salt)}$SALT_SEPARATOR${Base64.getEncoder().encodeToString(hash)}"
    }

    private fun verifyPassword(input: String, stored: String): Boolean {
        return try {
            val parts = stored.split(SALT_SEPARATOR)
            if (parts.size != 2) return false
            val salt = Base64.getDecoder().decode(parts[0])
            val hash = Base64.getDecoder().decode(parts[1])
            val spec = PBEKeySpec(input.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
            val inputHash = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            Arrays.equals(hash, inputHash)
        } catch (_: Exception) {
            false
        }
    }

    fun close() { db.close() }
}
