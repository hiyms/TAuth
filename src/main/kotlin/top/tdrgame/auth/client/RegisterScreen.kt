package top.tdrgame.auth.client

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * LDLib 注册界面。
 *
 * 当客户端安装了 TAuth + LDLib 时，弹出注册界面。
 * 包含两个密码输入框（密码 + 确认密码）和注册/返回按钮。
 *
 * 当前为占位实现 — 实际 UI 需要 LDLib 依赖运行时可用。
 */
@OnlyIn(Dist.CLIENT)
object RegisterScreen {
    // LDLib 依赖 TLS 问题暂未解决，保留结构。
    // 功能：密码输入框、确认密码输入框、注册按钮、返回 LoginScreen 的按钮。
}
