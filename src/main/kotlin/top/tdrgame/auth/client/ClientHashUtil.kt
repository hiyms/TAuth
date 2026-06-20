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
 *
 * 参数与服务端 [top.tdrgame.auth.server.PasswordStorage] 一致，
 * 服务端会在挑战包里把 iterations / keyBits 一并发来，客户端按之计算，
 * 因此即使服务端配置变化也保持兼容。
 *
 * 不加 @OnlyIn：本对象仅由客户端类（ClientAuthHandler 等）调用，
 * 服务端不会触达；避免注解处理对 JDK 类解析产生干扰。
 */
object ClientHashUtil {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** 用给定参数派生 key（用于登录挑战响应）。 */
    fun derive(password: String, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBits)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    /**
     * 生成一份新的离线密码哈希，格式与服务端一致：
     * `v1:<iterations>:<keyBits>:<base64(salt)>:<base64(hash)>`。
     *
     * @param saltBytes salt 字节数。
     * @param iterations PBKDF2 迭代次数。
     * @param keyBits 输出密钥位数。
     */
    fun newHash(password: String, saltBytes: Int, iterations: Int, keyBits: Int): String {
        val salt = ByteArray(saltBytes).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt, iterations, keyBits)
        val b64 = Base64.getEncoder()
        return "v1:$iterations:$keyBits:${b64.encodeToString(salt)}:${b64.encodeToString(hash)}"
    }

    /** 默认参数（与服务端默认对齐，客户端无配置文件时使用）。 */
    const val DEFAULT_SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 10000
    const val DEFAULT_KEY_BITS = 256
}
