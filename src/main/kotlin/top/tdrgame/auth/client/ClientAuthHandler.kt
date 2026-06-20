package top.tdrgame.auth.client

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * 客户端侧网络包处理器。
 *
 * 监听来自服务端的认证包，触发 LDLib GUI 并管理挑战-响应流程。
 * 仅在客户端安装了 TAuth 时生效。
 */
@OnlyIn(Dist.CLIENT)
object ClientAuthHandler {
    // 具体实现在 LoginScreen / RegisterScreen 中
    // 通过 Minecraft.getInstance().setScreen() 弹出界面
}
