package top.tdrgame.auth.server

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.*

/**
 * 密码哈希测试。
 *
 * 直接覆盖生产实现 [PasswordHasher]（而非复制粘贴一份算法副本），
 * 验证 PBKDF2 哈希/校验、salt 随机性、格式解析与 offlineauth 兼容性。
 */
class PasswordStorageTest {

    @Test
    fun `player auth data converter round trips all fields for Nitrite`() {
        val converter = PlayerAuthDataConverter()
        val source = PlayerAuthData(
            playerName = "Player",
            passwordHash = "v1:hash",
            verified = true,
            lastLoginType = "online",
            autoLoginMachineId = "machine",
            autoLoginIp = "127.0.0.1"
        )

        val document = converter.toDocument(source, org.dizitart.no2.common.mapper.SimpleNitriteMapper())
        val restored = converter.fromDocument(document, org.dizitart.no2.common.mapper.SimpleNitriteMapper())

        assertEquals(source, restored)
    }

    @Test
    fun `hash produces v1 five-segment format`() {
        val hash = PasswordHasher.hash("testPassword123", 16, 10000, 256)
        val parts = hash.split(PasswordHasher.SALT_SEPARATOR)
        assertEquals(5, parts.size)
        assertEquals("v1", parts[0])
        assertEquals("10000", parts[1])
        assertEquals("256", parts[2])
        assertTrue(parts[3].length >= 20, "Salt too short: ${parts[3].length}")
        assertTrue(parts[4].isNotEmpty())
    }

    @Test
    fun `same password produces different hashes due to random salt`() {
        val hash1 = PasswordHasher.hash("testPassword", 16, 10000, 256)
        val hash2 = PasswordHasher.hash("testPassword", 16, 10000, 256)

        assertNotEquals(hash1, hash2, "Each hash should have unique salt")
        assertTrue(PasswordHasher.verify("testPassword", hash1))
        assertTrue(PasswordHasher.verify("testPassword", hash2))
    }

    @Test
    fun `set password creates or replaces player password`() {
        val store = PasswordStorage { PasswordStorage.HashPolicy(16, 10000, 256) }
        val playerName = "SetPasswordUser-${UUID.randomUUID()}"
        try {
            assertFalse(store.isRegistered(playerName))
            store.setPassword(playerName, "firstPassword")
            assertTrue(store.isRegistered(playerName))
            assertTrue(store.checkPassword(playerName, "firstPassword"))
            assertFalse(store.checkPassword(playerName, "secondPassword"))

            store.setPassword(playerName, "secondPassword")
            assertFalse(store.checkPassword(playerName, "firstPassword"))
            assertTrue(store.checkPassword(playerName, "secondPassword"))
        } finally {
            store.delete(playerName)
            store.close()
        }
    }

    @Test
    fun `correct password verification succeeds`() {
        val hash = PasswordHasher.hash("securePassword123!", 16, 10000, 256)
        assertTrue(PasswordHasher.verify("securePassword123!", hash))
    }

    @Test
    fun `wrong password verification fails`() {
        val hash = PasswordHasher.hash("correctPassword", 16, 10000, 256)
        assertFalse(PasswordHasher.verify("wrongPassword", hash))
    }

    @Test
    fun `empty and special character passwords work`() {
        val passwords = listOf(
            "",
            "password",
            "密码测试",
            "p@\$w0rd!汉字",
            "   spaces   ",
            "a".repeat(1000)
        )

        for (password in passwords) {
            val hash = PasswordHasher.hash(password, 16, 10000, 256)
            assertTrue(PasswordHasher.verify(password, hash),
                "Failed for password: '${password.take(20)}...'")
            assertFalse(PasswordHasher.verify(password + "x", hash),
                "Should reject wrong password for: '${password.take(20)}...'")
        }
    }

    @Test
    fun `verification fails on malformed stored hash`() {
        assertFalse(PasswordHasher.verify("anything", "no-separator"))
        assertFalse(PasswordHasher.verify("anything", "not:valid:base64"))
        assertFalse(PasswordHasher.verify("anything", ""))
        assertFalse(PasswordHasher.verify("anything", "v1:10000:256:onlyone"))
    }

    @Test
    fun `parse rejects semantically invalid hash params`() {
        val b64 = Base64.getEncoder()
        val salt = b64.encodeToString(ByteArray(16) { 1 })
        val hash = b64.encodeToString(ByteArray(32) { 2 })
        assertNull(PasswordHasher.parse("v1:0:256:$salt:$hash"))
        assertNull(PasswordHasher.parse("v1:-1:256:$salt:$hash"))
        assertNull(PasswordHasher.parse("v1:10000:0:$salt:$hash"))
        assertNull(PasswordHasher.parse("v1:10000:256::${hash}"))
        assertNull(PasswordHasher.parse("v1:10000:256:$salt:"))
    }

    @Test
    fun `configurable parameters are honored`() {
        val hash = PasswordHasher.hash("pw", 32, 20000, 512)
        val parsed = PasswordHasher.parse(hash)
        assertNotNull(parsed)
        assertEquals(20000, parsed.iterations)
        assertEquals(512, parsed.keyLengthBits)
        assertEquals(32, parsed.salt.size)
        assertTrue(PasswordHasher.verify("pw", hash))
    }

    @Test
    fun `offlineauth legacy two-segment format is verified with default params`() {
        val password = "offlineAuthTest123"
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val legacy = "${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(hash)}"

        assertTrue(PasswordHasher.verify(password, legacy),
            "Legacy offlineauth salt:hash must verify")
        val parsed = PasswordHasher.parse(legacy)
        assertNotNull(parsed)
        assertEquals(PasswordHasher.LEGACY_ITERATIONS, parsed.iterations)
        assertEquals(PasswordHasher.LEGACY_KEY_LENGTH, parsed.keyLengthBits)
        assertFalse(PasswordHasher.verify("wrongPassword", legacy))
    }

    @Test
    fun `challenge response is stable and challenge-specific`() {
        val key = ByteArray(32) { it.toByte() }
        val response1 = challengeResponse(key, 1L)
        val response1Again = challengeResponse(key, 1L)
        val response2 = challengeResponse(key, 2L)
        assertContentEquals(response1, response1Again)
        assertFalse(MessageDigest.isEqual(response1, response2))
        assertEquals(32, response1.size)
    }

    private fun challengeResponse(derivedKey: ByteArray, challenge: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(derivedKey)
        val buf = java.nio.ByteBuffer.allocate(8).putLong(challenge).array()
        md.update(buf)
        return md.digest()
    }
}
