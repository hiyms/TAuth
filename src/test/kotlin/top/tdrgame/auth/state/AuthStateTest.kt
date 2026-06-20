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

    @Test
    fun `auto pass requires registered verified premium session`() {
        assertTrue(AuthStateMachine.shouldAutoPass(
            isPremium = true,
            isVerified = true,
            isRegistered = true
        ))
    }

    @Test
    fun `first premium login does not auto pass before register`() {
        assertFalse(AuthStateMachine.shouldAutoPass(
            isPremium = true,
            isVerified = false,
            isRegistered = false
        ))
    }

    @Test
    fun `verified account does not auto pass on offline session`() {
        assertFalse(AuthStateMachine.shouldAutoPass(
            isPremium = false,
            isVerified = true,
            isRegistered = true
        ))
    }

    @Test
    fun `premium registered but unverified account still needs login once`() {
        assertFalse(AuthStateMachine.shouldAutoPass(
            isPremium = true,
            isVerified = false,
            isRegistered = true
        ))
    }
}
