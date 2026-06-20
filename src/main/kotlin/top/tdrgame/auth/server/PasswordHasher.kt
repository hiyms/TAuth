package top.tdrgame.auth.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2WithHmacSHA256 密码哈希的纯算法实现，不依赖 Forge / Nitrite。
 *
 * 抽离自 [PasswordStorage]，使其可被单元测试直接覆盖，避免测试中复制粘贴算法。
 *
 * 存储格式（本模组写入）：`v1:<iterations>:<keyBits>:<base64(salt)>:<base64(hash)>`
 * 兼容 offlineauth 旧格式：`<base64(salt)>:<base64(hash)>`（按默认参数解析）。
 */
object PasswordHasher {

    const val ALGORITHM = "PBKDF2WithHmacSHA256"
    const val SALT_SEPARATOR = ":"
    const val FORMAT_PREFIX = "v1"

    /** offlineauth 默认参数，用于解析无前缀的旧格式哈希。 */
    const val LEGACY_ITERATIONS = 10000
    const val LEGACY_KEY_LENGTH = 256

    /** 解析后的哈希参数与原始 salt/hash。 */
    data class ParsedHash(
        val iterations: Int,
        val keyLengthBits: Int,
        val salt: ByteArray,
        val hash: ByteArray
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** 用给定参数生成新的哈希字符串。 */
    fun hash(password: String, saltBytes: Int, iterations: Int, keyBits: Int): String {
        require(saltBytes > 0) { "saltBytes must be positive" }
        require(iterations > 0) { "iterations must be positive" }
        require(keyBits > 0) { "keyBits must be positive" }
        val salt = ByteArray(saltBytes).also { SecureRandom().nextBytes(it) }
        val derived = derive(password, salt, iterations, keyBits)
        val b64 = Base64.getEncoder()
        return "$FORMAT_PREFIX$SALT_SEPARATOR$iterations$SALT_SEPARATOR$keyBits$SALT_SEPARATOR" +
            "${b64.encodeToString(salt)}$SALT_SEPARATOR${b64.encodeToString(derived)}"
    }

    /** 校验输入密码与存储哈希是否匹配。 */
    fun verify(input: String, stored: String): Boolean {
        return try {
            val parsed = parse(stored) ?: return false
            val inputHash = derive(input, parsed.salt, parsed.iterations, parsed.keyLengthBits)
            MessageDigest.isEqual(parsed.hash, inputHash)
        } catch (_: Exception) {
            false
        }
    }

    /** 解析存储的哈希字符串，支持 v1 五段与 offlineauth 两段格式。 */
    fun parse(stored: String): ParsedHash? {
        return try {
            val parts = stored.split(SALT_SEPARATOR)
            val parsed = when {
                parts.size == 5 && parts[0] == FORMAT_PREFIX -> {
                    ParsedHash(
                        iterations = parts[1].toInt(),
                        keyLengthBits = parts[2].toInt(),
                        salt = Base64.getDecoder().decode(parts[3]),
                        hash = Base64.getDecoder().decode(parts[4])
                    )
                }
                parts.size == 2 -> {
                    // offlineauth 旧格式，无前缀，按默认参数解析
                    ParsedHash(
                        iterations = LEGACY_ITERATIONS,
                        keyLengthBits = LEGACY_KEY_LENGTH,
                        salt = Base64.getDecoder().decode(parts[0]),
                        hash = Base64.getDecoder().decode(parts[1])
                    )
                }
                else -> null
            }
            parsed?.takeIf { isValidParsedHash(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidParsedHash(parsed: ParsedHash): Boolean =
        parsed.iterations > 0 &&
            parsed.keyLengthBits > 0 &&
            parsed.salt.isNotEmpty() &&
            parsed.hash.isNotEmpty()

    /** PBKDF2 派生 key。 */
    fun derive(password: String, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }
}
