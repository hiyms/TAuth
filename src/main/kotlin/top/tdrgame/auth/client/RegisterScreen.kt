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
 * LDLib 注册界面。
 *
 * 布局：标题 → 密码输入框 → 确认密码输入框 → [注册] [返回] 按钮。
 * 提交后由 [ClientAuthHandler] 本地 PBKDF2 生成哈希并发送给服务端。
 */
@OnlyIn(Dist.CLIENT)
object RegisterScreen {

    private var currentPassword: String = ""
    private var currentConfirm: String = ""

    fun create(message: String = ""): ModularUI {
        currentPassword = ""
        currentConfirm = ""
        val group = WidgetGroup(0, 0, 176, 136).setClientSideWidget()

        group.addWidget(LabelWidget(8, 8, "§6§lTAuth 注册"))
        if (message.isNotEmpty()) {
            group.addWidget(LabelWidget(8, 22, "§c${message.take(24)}"))
        }

        val passwordField = TextFieldWidget(8, 42, 160, 16, { currentPassword }, { currentPassword = it })
            .setClientSideWidget()
        group.addWidget(passwordField)

        val confirmField = TextFieldWidget(8, 64, 160, 16, { currentConfirm }, { currentConfirm = it })
            .setClientSideWidget()
        group.addWidget(confirmField)

        val registerBtn = ButtonWidget(8, 90, 70, 16,
            TextTexture("注册"),
            { _ -> ClientAuthHandler.submitRegister(currentPassword, currentConfirm) })
            .setClientSideWidget()
        group.addWidget(registerBtn)

        val backBtn = ButtonWidget(88, 90, 70, 16,
            TextTexture("返回"),
            { _ -> ClientAuthHandler.showLoginScreenFromRegister() })
            .setClientSideWidget()
        group.addWidget(backBtn)

        val player = Minecraft.getInstance().player
        return ModularUI(group, IUIHolder.EMPTY, player)
    }
}
