package top.tdrgame.auth.i18n

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

object ServerI18n {
    fun text(key: String, vararg args: Any): MutableComponent =
        Component.translatableWithFallback(key, fallback(key), *args)

    fun fallback(key: String): String = when (key) {
        I18nKeys.AUTH_DISABLED -> "认证功能未启用。"
        I18nKeys.RESET_SUCCESS -> "已重置 %s 的密码。该玩家需要重新注册。"
        I18nKeys.SET_PASSWORD_SUCCESS -> "已设置 %s 的密码。"
        I18nKeys.REGISTER_PASSWORD_MISMATCH -> "两次输入的密码不一致！"
        I18nKeys.ALREADY_REGISTERED_LOGIN -> "你已注册。请使用 /login 密码 登录！"
        I18nKeys.REGISTER_SUCCESS -> "注册成功！"
        I18nKeys.NOT_REGISTERED_REGISTER -> "你尚未注册。请使用 /register 密码 确认密码 注册！"
        I18nKeys.BAD_PASSWORD -> "密码错误！"
        I18nKeys.LOGIN_SUCCESS -> "登录成功！"
        I18nKeys.ALREADY_AUTHENTICATED -> "已认证。"
        I18nKeys.NOT_REGISTERED_GUI -> "你尚未注册。请使用 /register 注册！"
        I18nKeys.AUTO_LOGIN_DENIED -> "自动登录条件不匹配，请手动登录。"
        I18nKeys.NO_PENDING_CHALLENGE -> "无待处理挑战，请重试。"
        I18nKeys.INVALID_PASSWORD_HASH -> "密码哈希格式非法，请重试。"
        I18nKeys.ALREADY_REGISTERED_DIRECT_LOGIN -> "你已注册，请直接登录。"
        I18nKeys.FORGOT_PASSWORD_DISCONNECT -> "请联系管理员重置密码。"
        I18nKeys.CANCEL_AUTH_DISCONNECT -> "玩家已取消验证会话。"
        I18nKeys.LOGIN_TIMEOUT -> "登录超时，你已被踢出！"
        I18nKeys.TOO_MANY_FAILURES -> "密码错误次数过多，你已被踢出！"
        I18nKeys.UNKNOWN_KICK -> "未知原因"
        I18nKeys.COMMAND_BLOCKED -> "未登录禁止使用此命令！请先 /login 登录或 /register 注册。"
        I18nKeys.FIRST_JOIN_REGISTER -> "首次进服，请使用 /register 密码 确认密码 注册账户！"
        I18nKeys.PROMPT_LOGIN -> "请使用 /login 密码 登录账户！"
        else -> key
    }
}
