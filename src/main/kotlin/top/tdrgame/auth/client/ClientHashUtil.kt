package top.tdrgame.auth.client

import java.security.SecureRandom
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 客户端侧 PBKDF2 哈希工具。
 *
 * TASK 要求「哈希计算移到客户端」：密码明文不再经过网络与服务端，
 * PBKDF2WithHmacSHA256 在客户端本地执行，服务端只保存/比对派生结果。
 */
object ClientHashUtil {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    data class HashResult(val storedHash: String, val derivedKey: ByteArray)

    /** 用给定参数派生 key（用于登录挑战响应）。 */
    fun derive(password: String, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBits)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    /**
     * 生成一份新的离线密码哈希，格式与服务端一致：
     * `v1:<iterations>:<keyBits>:<base64(salt)>:<base64(hash)>`。
     */
    fun newHash(password: String, saltBytes: Int, iterations: Int, keyBits: Int): HashResult {
        val salt = ByteArray(saltBytes).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt, iterations, keyBits)
        val b64 = Base64.getEncoder()
        return HashResult(
            storedHash = "v1:$iterations:$keyBits:${b64.encodeToString(salt)}:${b64.encodeToString(hash)}",
            derivedKey = hash
        )
    }

    /** 默认参数（仅在服务端未下发注册策略时兜底）。 */
    const val DEFAULT_SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 10000
    const val DEFAULT_KEY_BITS = 256
}
