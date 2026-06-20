package top.tdrgame.auth.server

/**
 * 对 TrueUUID 的可选依赖桥接。
 *
 * TrueUUID 仅在服务端安装，且提供正版状态查询。模组不对其硬编码编译期依赖，
 * 通过反射调用 [cn.alini.trueuuid.api.TrueuuidApi.isPremium] 实现可选联动。
 *
 * 已对照 TrueUUID 源码核实：`cn.alini.trueuuid.api.TrueuuidApi` 为 public 类，
 * `isPremium(String)` 为 public static 方法，参数不区分大小写。
 * 未安装时所有方法安全降级（返回 false / 不抛异常）。
 */
object TrueUuidBridge {

    /** TrueUUID 是否已加载（仅查询一次缓存结果）。 */
    private val available: Boolean by lazy {
        runCatching { Class.forName("cn.alini.trueuuid.api.TrueuuidApi") }.isSuccess
    }

    /**
     * 查询 [name] 是否被 TrueUUID 判定为正版（已通过在线验证）。
     * @return TrueUUID 未安装或查询失败时返回 false。
     */
    fun isPremium(name: String): Boolean {
        if (!available) return false
        return try {
            val apiClass = Class.forName("cn.alini.trueuuid.api.TrueuuidApi")
            val method = apiClass.getMethod("isPremium", String::class.java)
            method.invoke(null, name.lowercase()) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }
}
