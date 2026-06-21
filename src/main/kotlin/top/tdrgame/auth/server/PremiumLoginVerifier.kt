package top.tdrgame.auth.server

import com.mojang.authlib.GameProfile
import net.minecraft.network.Connection
import net.minecraft.network.protocol.login.ServerboundKeyPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.core.UUIDUtil
import net.minecraft.util.Crypt
import net.minecraft.util.CryptException
import top.tdrgame.auth.TAuth
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AuthMe-style premium verifier for offline-mode servers.
 *
 * It reuses the vanilla client's EncryptionRequest/EncryptionResponse flow during LOGIN,
 * calls Mojang hasJoined through MinecraftServer.sessionService, and records verified
 * Mojang UUIDs for the current login plus future premium bypass attempts.
 */
object PremiumLoginVerifier {

    data class VerificationResult(val profile: GameProfile)
    data class EncryptionContext(val decryptCipher: Cipher, val encryptCipher: Cipher, val sharedSecret: ByteArray) {
        fun secretKey() = SecretKeySpec(sharedSecret, "AES")
    }

    private const val PENDING_TTL_MS = 5 * 60 * 1000L

    private data class PendingPremium(val expiresAt: Long)

    private val random = SecureRandom()
    private val verifiedSessions = ConcurrentHashMap<String, UUID>()
    private val pendingPremium = ConcurrentHashMap<String, PendingPremium>()

    fun shouldVerify(server: MinecraftServer, connection: Connection, name: String): Boolean {
        if (server.usesAuthentication() || connection.isMemoryConnection) return false
        if (!isValidName(name)) return false
        val storage = runCatching { TAuthHolder.storage }.getOrNull() ?: return false
        val data = storage.get(name) ?: return false
        val shouldVerify = data.premiumUuid != null || isPending(name)
        if (shouldVerify) {
            TAuth.LOGGER.info("Premium login verification challenge enabled for player {}", name)
        }
        return shouldVerify
    }

    fun newNonceString(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return BigInteger(1, bytes).toString(36)
    }

    fun newChallenge(): ByteArray {
        val bytes = ByteArray(4)
        random.nextBytes(bytes)
        return bytes
    }

    /**
     * 同步步骤：解密 shared secret、验证 token、返回 AES ciphers。
     * 调用方必须立即安装加密，然后调用 [verifySessionAsync]。
     * 返回 null 表示 token 不合法，应断开连接。
     */
    fun prepareEncryption(server: MinecraftServer, packet: ServerboundKeyPacket, challenge: ByteArray): EncryptionContext? {
        return try {
            val privateKey = server.keyPair.private
            if (!packet.isChallengeValid(challenge, privateKey)) return null
            val secretKey = packet.getSecretKey(privateKey)
            EncryptionContext(
                decryptCipher = Crypt.getCipher(Cipher.DECRYPT_MODE, secretKey),
                encryptCipher = Crypt.getCipher(Cipher.ENCRYPT_MODE, secretKey),
                sharedSecret = secretKey.encoded
            )
        } catch (exception: CryptException) {
            TAuth.LOGGER.warn("Premium verification protocol error: {}", exception.message)
            null
        }
    }

    /** 异步步骤：调用 Mojang hasJoined，成功后检查 grace period。 */
    fun verifySessionAsync(
        server: MinecraftServer,
        connection: Connection,
        name: String,
        sharedSecret: ByteArray
    ): CompletableFuture<VerificationResult?> = CompletableFuture.supplyAsync {
        try {
            val secretKey = SecretKeySpec(sharedSecret, "AES")
            val serverHash = BigInteger(Crypt.digestData("", server.keyPair.public, secretKey)).toString(16)
            val profile = server.sessionService.hasJoinedServer(GameProfile(null, name), serverHash, addressFor(server, connection))
                ?: return@supplyAsync null
            if (!profile.name.equals(name, ignoreCase = true) || profile.id == UUIDUtil.createOfflinePlayerUUID(profile.name)) {
                return@supplyAsync null
            }
            VerificationResult(profile)
        } catch (exception: Exception) {
            TAuth.LOGGER.warn("Premium verification failed for {}: {}", name, exception.message)
            null
        }
    }.orTimeout(10, TimeUnit.SECONDS)

    fun addPending(name: String) {
        pendingPremium[name.lowercase()] = PendingPremium(System.currentTimeMillis() + PENDING_TTL_MS)
        TAuth.LOGGER.info("Premium login verification pending for {}", name)
    }

    fun isPending(name: String): Boolean {
        val key = name.lowercase()
        val pending = pendingPremium[key] ?: return false
        if (System.currentTimeMillis() > pending.expiresAt) {
            pendingPremium.remove(key, pending)
            return false
        }
        return true
    }

    fun storeVerified(name: String, uuid: UUID) {
        verifiedSessions[name.lowercase()] = uuid
        TAuth.LOGGER.info("Premium login verification succeeded for {} ({})", name, uuid)
        if (pendingPremium.remove(name.lowercase()) != null) {
            runCatching {
                TAuthHolder.storage.updateVerification(name, isPremium = true, loginType = "online", premiumUuid = uuid)
            }
        }
    }

    /** hasJoined with nonce-based verification (for client auto-proof). */
    fun verifyNonceAsync(server: MinecraftServer, name: String, nonce: String): CompletableFuture<GameProfile?> =
        CompletableFuture.supplyAsync {
            try {
                server.sessionService.hasJoinedServer(GameProfile(null, name), nonce, null)
                    ?.takeIf { it.name.equals(name, ignoreCase = true) && it.id != UUIDUtil.createOfflinePlayerUUID(it.name) }
            } catch (exception: Exception) {
                TAuth.LOGGER.warn("Nonce verification failed for {}: {}", name, exception.message)
                null
            }
        }.orTimeout(10, TimeUnit.SECONDS)

    fun currentVerifiedUuid(name: String): UUID? = verifiedSessions[name.lowercase()]

    fun consumeVerified(name: String): UUID? = verifiedSessions.remove(name.lowercase())

    fun hasVerified(name: String): Boolean = verifiedSessions.containsKey(name.lowercase())

    private fun addressFor(server: MinecraftServer, connection: Connection): InetAddress? {
        val remote = connection.remoteAddress
        return if (server.preventProxyConnections && remote is InetSocketAddress) remote.address else null
    }

    private fun isValidName(name: String): Boolean = name.length in 2..16 && name.all { it.isLetterOrDigit() || it == '_' }
}
