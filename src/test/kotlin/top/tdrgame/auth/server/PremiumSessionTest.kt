package top.tdrgame.auth.server

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumSessionTest {

    @Test
    fun `online-mode Mojang UUID is premium`() {
        val playerName = "Steve"
        val onlineUuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")

        assertTrue(AuthManager.isPremiumIdentity(playerName, onlineUuid, serverUsesAuthentication = true))
    }

    @Test
    fun `online-mode offline UUID is not premium`() {
        val playerName = "Steve"
        val offlineUuid = AuthManager.offlineUuidForName(playerName)

        assertFalse(AuthManager.isPremiumIdentity(playerName, offlineUuid, serverUsesAuthentication = true))
    }

    @Test
    fun `offline-mode session is not premium without external proof`() {
        val playerName = "Steve"
        val onlineUuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")

        assertFalse(AuthManager.isPremiumIdentity(playerName, onlineUuid, serverUsesAuthentication = false))
    }
}
