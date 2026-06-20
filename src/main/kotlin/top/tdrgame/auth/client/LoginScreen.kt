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
 * 仅客户端构造；输入框 / 按钮均为客户端组件（setClientSideWidget），
 * 密码明文不通过网络同步，提交后由 [ClientAuthHandler] 本地 PBKDF2 + 挑战响应。
 */
@OnlyIn(Dist.CLIENT)
object LoginScreen {

    /** 当前输入的密码（由输入框 responder 实时回写）。 */
    private var currentPassword: String = ""

    fun create(): ModularUI {
        currentPassword = ""
        val group = WidgetGroup(0, 0, 176, 100).setClientSideWidget()

        // 标题
        group.addWidget(LabelWidget(8, 8, "§6§lTAuth 登录"))

        // 密码输入框：supplier 给初值，responder 回写本地变量
        val passwordField = TextFieldWidget(8, 30, 160, 16, { currentPassword }, { currentPassword = it })
            .setClientSideWidget()
        group.addWidget(passwordField)

        // 登录按钮：TextTexture 渲染文字
        val loginBtn = ButtonWidget(8, 55, 70, 16,
            TextTexture("登录"),
            { _ -> ClientAuthHandler.submitPassword(currentPassword) })
            .setClientSideWidget()
        group.addWidget(loginBtn)

        // 注册按钮：切换到注册界面
        val registerBtn = ButtonWidget(88, 55, 70, 16,
            TextTexture("注册"),
            { _ -> ClientAuthHandler.showRegisterScreen() })
            .setClientSideWidget()
        group.addWidget(registerBtn)

        val player = Minecraft.getInstance().player
        return ModularUI(group, IUIHolder.EMPTY, player)
    }
}
