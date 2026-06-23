package top.tdrgame.auth.server

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumIdentityTest {

    @Test
    fun `online-mode Mojang UUID is premium`() {
        assertTrue(AuthManager.isPremiumIdentity(
            playerName = "Steve",
            playerUuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"),
            serverUsesAuthentication = true
        ))
    }

    @Test
    fun `offline uuid is not premium even when server uses authentication`() {
        assertFalse(AuthManager.isPremiumIdentity(
            playerName = "Steve",
            playerUuid = AuthManager.offlineUuidForName("Steve"),
            serverUsesAuthentication = true
        ))
    }

    @Test
    fun `offline-mode profile is not premium without protocol proof`() {
        assertFalse(AuthManager.isPremiumIdentity(
            playerName = "Steve",
            playerUuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"),
            serverUsesAuthentication = false
        ))
    }
}
