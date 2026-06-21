package top.tdrgame.auth.client

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture
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
 * LDLib 注册界面。
 *
 * 布局：背景框 → 标题 → 密码/确认密码标签与输入框 → [注册] 按钮。
 * 提交后由 [ClientAuthHandler] 本地 PBKDF2 生成哈希并发送给服务端。
 */
@OnlyIn(Dist.CLIENT)
object RegisterScreen {

    private var currentPassword: String = ""
    private var currentConfirm: String = ""

    fun create(message: String = ""): ModularUI {
        currentPassword = ""
        currentConfirm = ""
        val group = WidgetGroup(0, 0, 196, 146).setClientSideWidget()

        group.addWidget(ImageWidget(0, 0, 196, 146, ColorRectTexture(0xFF202020.toInt()).setRadius(6f))
            .setBorder(1, 0xFF8A8A8A.toInt())
            .setClientSideWidget())
        group.addWidget(LabelWidget(12, 10, "§6§lTAuth 注册"))
        if (message.isNotEmpty()) {
            group.addWidget(LabelWidget(12, 28, "§c${message.take(28)}"))
        }

        group.addWidget(LabelWidget(12, 44, "§f密码"))
        val passwordField = TextFieldWidget(78, 42, 106, 16, { currentPassword }, { currentPassword = it })
            .setClientSideWidget()
        group.addWidget(passwordField)

        group.addWidget(LabelWidget(12, 68, "§f再次输入密码"))
        val confirmField = TextFieldWidget(78, 66, 106, 16, { currentConfirm }, { currentConfirm = it })
            .setClientSideWidget()
        group.addWidget(confirmField)

        val registerBtn = ButtonWidget(64, 102, 68, 18,
            TextTexture("注册"),
            { _ -> ClientAuthHandler.submitRegister(currentPassword, currentConfirm) })
            .setClientSideWidget()
        group.addWidget(registerBtn)

        val player = Minecraft.getInstance().player
        return ModularUI(group, IUIHolder.EMPTY, player)
    }
}
