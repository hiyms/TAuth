package top.tdrgame.auth.server

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumSessionVerifierTest {

    @Test
    fun `nonce is URL-safe and long enough`() {
        val nonce = PremiumSessionVerifier.newNonce()

        assertTrue(nonce.length >= 32)
        assertTrue(nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `parse mojang hasJoined response`() {
        val json = """
            {
              "id": "8667ba71b85a4004af54457a9734eed7",
              "name": "Steve",
              "properties": []
            }
        """.trimIndent()

        val result = PremiumSessionVerifier.parseHasJoinedResponse(json)

        assertEquals(UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), result?.uuid)
        assertEquals("Steve", result?.name)
    }

    @Test
    fun `invalid hasJoined response is rejected`() {
        assertEquals(null, PremiumSessionVerifier.parseHasJoinedResponse("{}"))
        assertEquals(null, PremiumSessionVerifier.parseHasJoinedResponse("not-json"))
    }

    @Test
    fun `matching verified name marks premium proof valid`() {
        val result = PremiumSessionVerifier.HasJoinedResult(
            uuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"),
            name = "Steve"
        )

        assertTrue(PremiumSessionVerifier.isValidProof("steve", result))
    }

    @Test
    fun `offline uuid cannot be accepted as premium proof`() {
        val result = PremiumSessionVerifier.HasJoinedResult(
            uuid = AuthManager.offlineUuidForName("Steve"),
            name = "Steve"
        )

        assertFalse(PremiumSessionVerifier.isValidProof("Steve", result))
    }
}
