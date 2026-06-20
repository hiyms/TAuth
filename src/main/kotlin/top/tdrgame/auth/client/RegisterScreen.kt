package top.tdrgame.auth.client

import com.lowdragmc.lowdraglib.gui.widget.*
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * LDLib 注册界面。
 *
 * 两个密码输入框（密码 + 确认密码），注册/返回按钮。
 */
@OnlyIn(Dist.CLIENT)
object RegisterScreen {

    fun create(): ModularUI {
        val group = WidgetGroup(0, 0, 176, 120)

        group.addWidget(
            LabelWidget(8, 10, Component.literal("§6§l注册"))
        )

        val passwordField = TextFieldWidget(8, 30, 160, 16, { "" }, {})
        group.addWidget(passwordField)

        val confirmField = TextFieldWidget(8, 50, 160, 16, { "" }, {})
        group.addWidget(confirmField)

        // 注册按钮
        val registerBtn = ButtonWidget(8, 75, 70, 16) { _ ->
            val pw = passwordField.currentString
            val cf = confirmField.currentString
            if (pw.isNotEmpty() && pw == cf) {
                // 提交注册请求
            }
        }
        group.addWidget(registerBtn)
        group.addWidget(LabelWidget(8, 75, Component.literal("  注册")))

        // 返回按钮
        val backBtn = ButtonWidget(88, 75, 70, 16) { _ ->
            // 返回 LoginScreen
        }
        group.addWidget(backBtn)
        group.addWidget(LabelWidget(88, 75, Component.literal("  返回")))

        val player = Minecraft.getInstance().player
        return ModularUI(group, IUIHolder.EMPTY, player)
    }
}
