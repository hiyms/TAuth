package top.tdrgame.auth.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig

/**
 * 模组配置入口，使用 Forge ModConfig TOML 格式。
 *
 * 配置文件路径：config/tauth-server.toml
 * [auth.enabled] 为 false 时模组完全跳过所有逻辑，性能零开销。
 */
object AuthConfig {

    val spec: ForgeConfigSpec

    /** 是否启用认证。默认 false，需手动开启。 */
    val enabled: ForgeConfigSpec.BooleanValue

    /** 登录超时秒数。超时未登录的玩家会被踢出。 */
    val loginTimeoutSeconds: ForgeConfigSpec.IntValue

    /** 密码错误最大尝试次数，达到即踢出。 */
    val maxFailAttempts: ForgeConfigSpec.IntValue

    init {
        val builder = ForgeConfigSpec.Builder()
        builder.push("auth")
        enabled = builder.comment("Enable authentication").define("enabled", false)
        loginTimeoutSeconds = builder.comment("Login timeout in seconds")
            .defineInRange("loginTimeoutSeconds", 90, 10, 3600)
        maxFailAttempts = builder.comment("Max wrong password attempts before kick")
            .defineInRange("maxFailAttempts", 5, 1, 100)
        builder.pop()
        spec = builder.build()
    }

    /**
     * 在模组初始化时注册配置。
     * 必须在 [ModLoadingContext] 有效期内调用。
     */
    fun register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, spec, "tauth-server.toml")
    }
}
