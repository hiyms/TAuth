package top.tdrgame.auth.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import org.apache.logging.log4j.LogManager

/**
 * 模组配置入口，使用 Forge ModConfig TOML 格式。
 *
 * 配置文件路径：config/tauth-server.toml
 * [auth.enabled] 为 false 时模组完全跳过所有逻辑，性能零开销。
 */
object AuthConfig {

    private val logger = LogManager.getLogger("tauth/AuthConfig")

    val spec: ForgeConfigSpec

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

    init {
        val builder = ForgeConfigSpec.Builder()
        builder.push("auth")
        enabled = builder.comment("Enable authentication").define("enabled", false)
        loginTimeoutSeconds = builder.comment("Login timeout in seconds")
            .defineInRange("loginTimeoutSeconds", 90, 10, 3600)
        maxFailAttempts = builder.comment("Max wrong password attempts before kick")
            .defineInRange("maxFailAttempts", 5, 1, 100)
        // 密码加盐配置：默认与 offlineauth / TrueUUID 兼容的参数
        saltBytes = builder.comment("Salt length in bytes for password hashing")
            .defineInRange("saltBytes", 16, 8, 64)
        iterations = builder.comment("PBKDF2 iteration count")
            .defineInRange("iterations", 10000, 1000, 500000)
        keyLengthBits = builder.comment("PBKDF2 derived key length in bits")
            .defineInRange("keyLengthBits", 256, 128, 512)
        builder.pop()
        spec = builder.build()
    }

    /**
     * 在模组初始化时注册配置。
     * 必须在 [ModLoadingContext] 有效期内调用。
     */
    fun register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, spec, "tauth-server.toml")
        logger.info("Registered TAuth SERVER config as tauth-server.toml. Edit the world/serverconfig copy for dedicated server worlds.")
    }

    /** 供 Java 代码调用的便捷开关：auth.enabled 是否开启。 */
    @JvmStatic
    fun isEnabled(): Boolean = enabled.get()
}
