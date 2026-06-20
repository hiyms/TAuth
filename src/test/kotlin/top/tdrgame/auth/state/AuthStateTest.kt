package top.tdrgame.auth.state

import org.junit.jupiter.api.Test
import kotlin.test.*

class AuthStateTest {

    @Test
    fun `Pending state stores joinTime`() {
        val time = System.currentTimeMillis()
        val state = AuthState.Pending(time)
        assertEquals(time, state.joinTime)
    }

    @Test
    fun `Authenticated state stores loginTime`() {
        val time = System.currentTimeMillis()
        val state = AuthState.Authenticated(time)
        assertEquals(time, state.loginTime)
    }

    @Test
    fun `TimedOut state stores reason`() {
        val reason = "timeout"
        val state = AuthState.TimedOut(reason)
        assertEquals(reason, state.reason)
    }

    @Test
    fun `Authenticating is a singleton object`() {
        assertSame(AuthState.Authenticating, AuthState.Authenticating)
    }

    @Test
    fun `all states are distinct sealed subtypes`() {
        val states: List<AuthState> = listOf(
            AuthState.Pending(0L),
            AuthState.Authenticating,
            AuthState.Authenticated(0L),
            AuthState.TimedOut("test")
        )

        // Each should be recognized by when/is checks
        for (state in states) {
            val result = when (state) {
                is AuthState.Pending -> "pending"
                is AuthState.Authenticating -> "authenticating"
                is AuthState.Authenticated -> "authenticated"
                is AuthState.TimedOut -> "timedout"
            }
            assertNotNull(result)
        }

        assertEquals(4, states.toSet().size)
    }
}
