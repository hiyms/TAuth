package top.tdrgame.auth

import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.event.config.ModConfigEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import org.apache.logging.log4j.LogManager
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import top.tdrgame.auth.config.AuthConfig
import top.tdrgame.auth.network.AuthPackets
import top.tdrgame.auth.server.CommandHandler
import top.tdrgame.auth.server.EventHandler
import top.tdrgame.auth.server.PasswordStorage
import top.tdrgame.auth.server.TAuthHolder

/**
 * TAuth — 服务器端离线玩家认证模组。
 *
 * 服务器必装，客户端选装。
 * 为离线（盗版）玩家提供 PBKDF2 密码验证保护。
 *
 * @see <a href="https://github.com/tdrgame/TAuth">GitHub</a>
 */
@Mod(TAuth.ID)
object TAuth {

    const val ID = "tauth"
    val LOGGER = LogManager.getLogger(ID)

    lateinit var storage: PasswordStorage
        private set

    init {
        LOGGER.info("TAuth initializing...")

        // 1. 注册 Forge ModConfig
        AuthConfig.register()

        // 2. 仅服务端打开密码数据库（客户端选装时无需 auth.db）
        storage = PasswordStorage()
        TAuthHolder.storage = storage

        // 3. 注册网络包
        AuthPackets.register()

        // 4. 注册事件总线。Kotlin object + @EventBusSubscriber 在生产环境下不够直观，显式注册更可靠。
        MOD_BUS.register(ModEvents::class.java)
        MinecraftForge.EVENT_BUS.register(EventHandler::class.java)
        MinecraftForge.EVENT_BUS.register(CommandHandler::class.java)
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
            Runnable {
                MinecraftForge.EVENT_BUS.register(Class.forName("top.tdrgame.auth.client.ClientEventListener"))
            }
        }
        LOGGER.info("Registered TAuth mod and Forge static event handler classes.")
    }

    object ModEvents {

        @JvmStatic
        @net.minecraftforge.eventbus.api.SubscribeEvent
        fun onCommonSetup(event: FMLCommonSetupEvent) {
            // Common setup intentionally does not open/migrate the auth database.
            // Migration is gated by auth.enabled during server lifecycle events.
        }

        @JvmStatic
        @net.minecraftforge.eventbus.api.SubscribeEvent
        fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
            LOGGER.info("TAuth server-side initialized. Auth enabled: {}", AuthConfig.enabled.get())
        }

        @JvmStatic
        @net.minecraftforge.eventbus.api.SubscribeEvent
        fun onConfigLoading(event: ModConfigEvent.Loading) {
            if (event.config.modId == ID && event.config.fileName == "tauth-server.toml") {
                LOGGER.info("TAuth config loaded ({}): auth.enabled={}, timeout={}s, maxFailAttempts={}",
                    event.config.fileName, AuthConfig.enabled.get(), AuthConfig.loginTimeoutSeconds.get(), AuthConfig.maxFailAttempts.get())
            }
        }

        @JvmStatic
        @net.minecraftforge.eventbus.api.SubscribeEvent
        fun onConfigReloading(event: ModConfigEvent.Reloading) {
            if (event.config.modId == ID && event.config.fileName == "tauth-server.toml") {
                LOGGER.info("TAuth config reloaded ({}): auth.enabled={}, timeout={}s, maxFailAttempts={}",
                    event.config.fileName, AuthConfig.enabled.get(), AuthConfig.loginTimeoutSeconds.get(), AuthConfig.maxFailAttempts.get())
            }
        }
    }
}
