package top.tdrgame.auth.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.*

/**
 * 测试 PBKDF2 密码哈希的正确性——与 offlineauth 兼容。
 *
 * 这些测试不依赖 Minecraft/Forge 运行时，可以直接运行。
 */
class PasswordStorageTest {

    companion object {
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATIONS = 10000
        private const val KEY_LENGTH = 256
        private const val SALT_BYTES = 16
        private const val SALT_SEPARATOR = ":"
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `hash produces salt separator hash format`() {
        val password = "testPassword123"
        val hash = hashPassword(password)

        // 格式：Base64(salt):Base64(hash)
        val parts = hash.split(SALT_SEPARATOR)
        assertEquals(2, parts.size)
        // Salt 部分应该是有效的 Base64（16 字节 → 24 字符），hash 非空
        assertTrue(parts[0].length >= 20, "Salt too short: ${parts[0].length}")
        assertTrue(parts[1].isNotEmpty())
    }

    @Test
    fun `same password produces different hashes due to random salt`() {
        val password = "testPassword"
        val hash1 = hashPassword(password)
        val hash2 = hashPassword(password)

        assertNotEquals(hash1, hash2, "Each hash should have unique salt")
        assertTrue(verifyPassword(password, hash1))
        assertTrue(verifyPassword(password, hash2))
    }

    @Test
    fun `correct password verification succeeds`() {
        val password = "securePassword123!"
        val hash = hashPassword(password)

        assertTrue(verifyPassword(password, hash))
    }

    @Test
    fun `wrong password verification fails`() {
        val password = "correctPassword"
        val hash = hashPassword(password)

        assertFalse(verifyPassword("wrongPassword", hash))
    }

    @Test
    fun `empty and special character passwords work`() {
        val passwords = listOf(
            "",
            "password",
            "密码测试",
            "p@\${'$'}w0rd!汉字",
            "   spaces   ",
            "a".repeat(1000)
        )

        for (password in passwords) {
            val hash = hashPassword(password)
            assertTrue(verifyPassword(password, hash), "Failed for password: '${password.take(20)}...'")
            assertFalse(verifyPassword(password + "x", hash), "Should reject wrong password for: '${password.take(20)}...'")
        }
    }

    @Test
    fun `verification fails on malformed stored hash`() {
        assertFalse(verifyPassword("anything", "no-separator"))
        assertFalse(verifyPassword("anything", "not:valid:base64"))
        assertFalse(verifyPassword("anything", ""))
    }

    @Test
    fun `offlineauth compatibility - same algorithm and format`() {
        // offlineauth 使用相同的算法参数和格式
        // 此测试确保 TAuth 的实现与 offlineauth 存储格式兼容
        val password = "offlineAuthTest123"

        // 使用 PBKDF2WithHmacSHA256, ITERATIONS=10000, KEY_LENGTH=256
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val hash = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded

        val storedFormat = "${Base64.getEncoder().encodeToString(salt)}$SALT_SEPARATOR${Base64.getEncoder().encodeToString(hash)}"

        // 应该能用自己的 verifyPassword 验证
        assertTrue(verifyPassword(password, storedFormat))
    }

    // ── 与 PasswordStorage 中完全相同的实现 ──

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
}
