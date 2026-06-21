package top.tdrgame.auth.config

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AuthConfigTest {

    @Test
    fun `auto login client config defaults to enabled`() {
        assertTrue(AuthConfig.autoLoginEnabled.getDefault())
        assertTrue(AuthConfig.autoLoginEnabled.path == listOf("client", "autoLoginEnabled"))
    }
}
