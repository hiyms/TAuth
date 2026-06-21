package top.tdrgame.auth.client

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture
import com.lowdragmc.lowdraglib.gui.texture.TextTexture
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * LDLib 登录界面。
 *
 * 布局：背景框 → 标题 → 密码标签/输入框 → [登录] 按钮。
 */
@OnlyIn(Dist.CLIENT)
object LoginScreen {

    /** 当前输入的密码（由输入框 responder 实时回写）。 */
    private var currentPassword: String = ""

    fun create(message: String = ""): ModularUI {
        currentPassword = ""
        val showAutoLoginWarning = !ClientAuthHandler.isAutoLoginEnabled()
        val height = if (showAutoLoginWarning) 132 else 118
        val group = WidgetGroup(0, 0, 176, height).setClientSideWidget()

        group.addWidget(ImageWidget(0, 0, 176, height, ResourceBorderTexture.BORDERED_BACKGROUND)
            .setClientSideWidget())
        group.addWidget(LabelWidget(12, 10, "§6§lTAuth 登录"))
        if (message.isNotEmpty()) {
            group.addWidget(LabelWidget(12, 28, "§c${message.take(24)}"))
        }

        group.addWidget(LabelWidget(12, 44, "§f密码"))
        group.addWidget(ImageWidget(52, 41, 114, 18, ResourceBorderTexture.BAR)
            .setClientSideWidget())
        val passwordField = TextFieldWidget(56, 42, 106, 16, { currentPassword }, { currentPassword = it })
            .setClientSideWidget()
        group.addWidget(passwordField)

        val loginBtn = ButtonWidget(54, 74, 68, 18,
            GuiTextureGroup(ResourceBorderTexture.BUTTON_COMMON, TextTexture("登录")),
            { _ -> ClientAuthHandler.submitPassword(currentPassword) })
            .setClientSideWidget()
        group.addWidget(loginBtn)

        if (showAutoLoginWarning) {
            group.addWidget(LabelWidget(12, 96, "§e自动登录已在配置中禁用"))
        }

        val forgotBtn = ButtonWidget(100, if (showAutoLoginWarning) 110 else 96, 64, 14,
            TextTexture("§n忘记密码", 0xFFB8B8B8.toInt()),
            { _ -> ClientAuthHandler.forgotPassword() })
            .setClientSideWidget()
        group.addWidget(forgotBtn)

        val player = Minecraft.getInstance().player
        return ModularUI(group, IUIHolder.EMPTY, player)
    }
}
