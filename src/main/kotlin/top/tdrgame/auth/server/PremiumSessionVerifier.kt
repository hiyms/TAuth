package top.tdrgame.auth.server

import com.google.gson.JsonParser
import top.tdrgame.auth.TAuth
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * 内置正版会话证明：客户端本地调用 Mojang joinServer，服务端用 hasJoined 校验 nonce。
 *
 * 该流程不接入 TrueUUID，也不接触玩家 access token；它只验证当前客户端是否能让
 * Mojang session server 为本次 nonce 返回对应正版 UUID。
 */
object PremiumSessionVerifier {

    private const val HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
    private val random = SecureRandom()
    private val httpClient = HttpClient.newHttpClient()

    data class HasJoinedResult(val uuid: UUID, val name: String)

    fun newNonce(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun verifyAsync(playerName: String, nonce: String): CompletableFuture<HasJoinedResult?> {
        val url = HAS_JOINED_URL +
            "?username=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8) +
            "&serverId=" + URLEncoder.encode(nonce, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) return@thenApply null
                val result = parseHasJoinedResponse(response.body()) ?: return@thenApply null
                if (isValidProof(playerName, result)) result else null
            }
            .exceptionally { throwable ->
                TAuth.LOGGER.warn("Premium session verification failed for {}: {}", playerName, throwable.message)
                null
            }
    }

    fun parseHasJoinedResponse(json: String): HasJoinedResult? {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val id = obj.get("id")?.asString ?: return null
            val name = obj.get("name")?.asString ?: return null
            HasJoinedResult(parseUuid(id), name)
        } catch (_: Exception) {
            null
        }
    }

    fun isValidProof(expectedName: String, result: HasJoinedResult): Boolean =
        result.name.equals(expectedName, ignoreCase = true) && result.uuid != AuthManager.offlineUuidForName(result.name)

    private fun parseUuid(raw: String): UUID {
        val compact = raw.replace("-", "")
        val dashed = compact.replaceFirst(
            Regex("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})"),
            "$1-$2-$3-$4-$5"
        )
        return UUID.fromString(dashed)
    }
}
