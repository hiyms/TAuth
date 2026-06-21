package top.tdrgame.auth.client

import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * 客户端事件监听。
 *
 * 玩家加入服务器时触发自动登录流程：向服务端发起 [top.tdrgame.auth.network.AuthPackets.LoginRequestPacket]，
 * 服务端按需回挑战或直接放行。
 *
 * 单独成类并限定 [Dist.CLIENT]，确保服务端不会加载客户端事件类。
 */
object ClientEventListener {

    private const val AUTH_START_RETRY_TICKS = 100
    private var authStartRetries = 0

    @SubscribeEvent
    @JvmStatic
    fun onClientPlayerLogin(event: ClientPlayerNetworkEvent.LoggingIn) {
        authStartRetries = AUTH_START_RETRY_TICKS
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientPlayerLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        authStartRetries = 0
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END || authStartRetries <= 0) return
        if (ClientAuthHandler.tryStartAuth()) {
            authStartRetries = 0
        } else {
            authStartRetries--
        }
    }
}
