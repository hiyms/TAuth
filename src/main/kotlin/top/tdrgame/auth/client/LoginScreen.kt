package top.tdrgame.auth.client

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import com.lowdragmc.lowdraglib.gui.texture.TextTexture
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * LDLib 登录界面。
 *
 * 布局：标题 → 密码输入框 → [登录] [注册] 按钮。
 */
@OnlyIn(Dist.CLIENT)
object LoginScreen {

    /** 当前输入的密码（由输入框 responder 实时回写）。 */
    private var currentPassword: String = ""

    fun create(message: String = ""): ModularUI {
        currentPassword = ""
        val group = WidgetGroup(0, 0, 176, 118).setClientSideWidget()

        group.addWidget(LabelWidget(8, 8, "§6§lTAuth 登录"))
        if (message.isNotEmpty()) {
            group.addWidget(LabelWidget(8, 22, "§c${message.take(24)}"))
        }

        val passwordField = TextFieldWidget(8, 42, 160, 16, { currentPassword }, { currentPassword = it })
            .setClientSideWidget()
        group.addWidget(passwordField)

        val loginBtn = ButtonWidget(8, 68, 70, 16,
            TextTexture("登录"),
            { _ -> ClientAuthHandler.submitPassword(currentPassword) })
            .setClientSideWidget()
        group.addWidget(loginBtn)

        val registerBtn = ButtonWidget(88, 68, 70, 16,
            TextTexture("注册"),
            { _ -> ClientAuthHandler.showRegisterScreen() })
            .setClientSideWidget()
        group.addWidget(registerBtn)

        val player = Minecraft.getInstance().player
        return ModularUI(group, IUIHolder.EMPTY, player)
    }
}
