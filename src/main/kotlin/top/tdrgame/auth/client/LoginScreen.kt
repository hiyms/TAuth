package top.tdrgame.auth.client

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * LDLib 登录界面。
 *
 * 当客户端安装了 TAuth + LDLib 时，弹出密码登录界面。
 * 使用 LDLib Widget 体系：WidgetGroup + TextFieldWidget + ButtonWidget。
 *
 * 当前为占位实现 — 实际 UI 需要 LDLib 依赖运行时可用。
 *
 * TODO: 在 LDLib 依赖可解析后，引入以下组件：
 *   - com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget
 *   - com.lowdragmc.lowdraglib.gui.widget.ButtonWidget
 *   - com.lowdragmc.lowdraglib.gui.widget.LabelWidget
 *   - com.lowdragmc.lowdraglib.gui.modular.ModularUI
 */
@OnlyIn(Dist.CLIENT)
object LoginScreen {
    // LDLib 依赖 TLS 问题暂未解决，保留结构。
    // 功能：密码输入框（masked）、登录按钮、切换到 RegisterScreen 的按钮。
}
