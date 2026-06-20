package top.tdrgame.auth.client

import com.lowdragmc.lowdraglib.gui.widget.*
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * LDLib 登录界面。
 *
 * 使用 LDLib Widget 体系构建，在客户端弹出供玩家输入密码登录。
 * 布局：标题 Label → 密码输入框 → 登录/注册按钮。
 */
@OnlyIn(Dist.CLIENT)
object LoginScreen {

    fun create(): ModularUI {
        val group = WidgetGroup(0, 0, 176, 100)

        // 标题
        group.addWidget(
            LabelWidget(8, 10, Component.literal("§6§l登录"))
        )

        // 密码输入框
        val passwordField = TextFieldWidget(8, 30, 160, 16, { "" }, {})
        group.addWidget(passwordField)

        // 登录按钮：WidgetGroup 包裹 Label 模拟带文字按钮
        // LDLib 使用纹理渲染按钮文字，这里用 Label + 背景代替
        val loginBtn = ButtonWidget(8, 55, 70, 16) { _ ->
            val password = passwordField.currentString
            if (password.isNotEmpty()) {
                // 走挑战-响应验证流程
            }
        }
        group.addWidget(loginBtn)
        group.addWidget(LabelWidget(8, 55, Component.literal("  登录")))

        // 注册按钮
        val registerBtn = ButtonWidget(88, 55, 70, 16) { _ ->
            // 切换到 RegisterScreen
        }
        group.addWidget(registerBtn)
        group.addWidget(LabelWidget(88, 55, Component.literal("  注册")))

        val player = Minecraft.getInstance().player
        return ModularUI(group, IUIHolder.EMPTY, player)
    }
}
