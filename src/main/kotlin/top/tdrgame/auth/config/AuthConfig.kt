package top.tdrgame.auth.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import org.apache.logging.log4j.LogManager

/**
 * 模组配置入口，使用 Forge ModConfig TOML 格式。
 *
 * 服务端配置文件路径：config/tauth-server.toml
 * 客户端配置文件路径：config/tauth-client.toml
 * [auth.enabled] 为 false 时模组完全跳过所有逻辑，性能零开销。
 */
object AuthConfig {

    private val logger = LogManager.getLogger("tauth/AuthConfig")

    val spec: ForgeConfigSpec
    val clientSpec: ForgeConfigSpec

    /** 是否启用认证。默认 false，需手动开启。 */
    val enabled: ForgeConfigSpec.BooleanValue

    /** 登录超时秒数。超时未登录的玩家会被踢出。 */
    val loginTimeoutSeconds: ForgeConfigSpec.IntValue

    /** 密码错误最大尝试次数，达到即踢出。 */
    val maxFailAttempts: ForgeConfigSpec.IntValue

    /** PBKDF2 加盐字节数。默认 16，范围 [8,64]。 */
    val saltBytes: ForgeConfigSpec.IntValue

    /** PBKDF2 迭代次数。默认 10000，与 offlineauth 对齐。 */
    val iterations: ForgeConfigSpec.IntValue

    /** PBKDF2 输出密钥位数。默认 256。 */
    val keyLengthBits: ForgeConfigSpec.IntValue

    /** 当客户端安装 TAuth 时，是否自动通过 Mojang 会话证明该客户端正版身份，跳过 /premium。 */
    val premiumAutoProofEnabled: ForgeConfigSpec.BooleanValue

    /** 客户端是否启用自动登录缓存。 */
    val autoLoginEnabled: ForgeConfigSpec.BooleanValue

    /** 客户端是否探测并上报 Mojang 正版身份。默认为 true；设为 false 后始终走密码登录。 */
    val premiumAutoDetectionEnabled: ForgeConfigSpec.BooleanValue

    init {
        val serverBuilder = ForgeConfigSpec.Builder()
        serverBuilder.push("auth")
        enabled = serverBuilder.comment("Enable authentication").define("enabled", false)
        loginTimeoutSeconds = serverBuilder.comment("Login timeout in seconds")
            .defineInRange("loginTimeoutSeconds", 90, 10, 3600)
        maxFailAttempts = serverBuilder.comment("Max wrong password attempts before kick")
            .defineInRange("maxFailAttempts", 5, 1, 100)
        // 密码加盐配置：默认与 offlineauth / TrueUUID 兼容的参数
        saltBytes = serverBuilder.comment("Salt length in bytes for password hashing")
            .defineInRange("saltBytes", 16, 8, 64)
        iterations = serverBuilder.comment("PBKDF2 iteration count")
            .defineInRange("iterations", 10000, 1000, 500000)
        keyLengthBits = serverBuilder.comment("PBKDF2 derived key length in bits")
            .defineInRange("keyLengthBits", 256, 128, 512)
        premiumAutoProofEnabled = serverBuilder.comment("When a client also installs TAuth, auto-detect and prove premium status via Mojang session")
            .define("premiumAutoProofEnabled", true)
        serverBuilder.pop()
        spec = serverBuilder.build()

        val clientBuilder = ForgeConfigSpec.Builder()
        clientBuilder.push("client")
        autoLoginEnabled = clientBuilder.comment("Enable automatic login cache on this client")
            .define("autoLoginEnabled", true)
        premiumAutoDetectionEnabled = clientBuilder.comment("Detect and report Mojang premium status to server (disable to always use password login)")
            .define("premiumAutoDetectionEnabled", true)
        clientBuilder.pop()
        clientSpec = clientBuilder.build()
    }

    /**
     * 在模组初始化时注册配置。
     * 必须在 [ModLoadingContext] 有效期内调用。
     */
    fun register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, spec, "tauth-server.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, clientSpec, "tauth-client.toml")
        logger.info("Registered TAuth SERVER config as tauth-server.toml. Edit the world/serverconfig copy for dedicated server worlds.")
        logger.info("Registered TAuth CLIENT config as tauth-client.toml.")
    }

    /** 供 Java 代码调用的便捷开关：auth.enabled 是否开启。 */
    @JvmStatic
    fun isEnabled(): Boolean = enabled.get()
}
