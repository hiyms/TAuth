package top.tdrgame.auth.client

import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import top.tdrgame.auth.network.AuthPackets

/**
 * 客户端侧认证协调器。
 *
 * 职责：
 * - 进入服务器后自动发起 [AuthPackets.LoginRequestPacket]，触发挑战-响应流程；
 * - 收到挑战后，若有匹配 IP 的自动登录缓存则静默完成，否则弹出 [LoginScreen]；
 * - 登录界面提交密码后本地 PBKDF2 + 挑战响应；
 * - 登录成功且服务端返回「记住此设备」凭证时写入 [AutoLoginManager]。
 *
 * 仅在客户端安装 TAuth 时运行；未装客户端的玩家仍可通过聊天命令认证。
 */
@OnlyIn(Dist.CLIENT)
object ClientAuthHandler {

    /** 当前等待玩家输入密码的挑战上下文。 */
    private var pendingChallenge: AuthPackets.ChallengePacket? = null

    /** 当前展示的认证界面，用于登录成功后关闭。 */
    private var currentScreen: ModularUIGuiContainer? = null

    /**
     * 由客户端玩家加入服务器事件触发：向服务端发起登录请求。
     * 由 [top.tdrgame.auth.client.ClientEventListener] 调用，避免在此处直接订阅
     * 引入客户端类导致服务端类加载失败。
     */
    fun onClientPlayerJoin() {
        val mc = Minecraft.getInstance()
        val name = mc.user?.name ?: return
        // 稍候发送，确保通道已就绪
        mc.executeBlocking {
            AuthPackets.sendToServer(AuthPackets.LoginRequestPacket("login", name))
        }
    }

    /** 收到服务端挑战：尝试自动登录，否则弹登录界面。 */
    fun onChallenge(packet: AuthPackets.ChallengePacket) {
        val cache = AutoLoginManager.load()
        val currentIp = currentServerIp()
        // 自动登录条件：缓存存在且 IP 一致。机器 ID 由本机生成，天然一致。
        if (cache != null && cache.lastIp == currentIp && cache.saltedHash.isNotEmpty()) {
            // 用缓存派生 key 直接响应挑战
            val resp = AuthPackets.challengeResponse(cache.saltedHash, packet.challenge)
            AuthPackets.sendToServer(AuthPackets.ChallengeResponsePacket(resp))
            return
        }
        // 需要手动输入：保存挑战并弹界面
        pendingChallenge = packet
        showLoginScreen()
    }

    /** 登录界面提交密码：本地 PBKDF2 后回应挑战。 */
    fun submitPassword(password: String) {
        val challenge = pendingChallenge ?: return
        if (password.isEmpty()) return
        val key = ClientHashUtil.derive(
            password, challenge.salt, challenge.iterations, challenge.keyBits)
        val resp = AuthPackets.challengeResponse(key, challenge.challenge)
        AuthPackets.sendToServer(AuthPackets.ChallengeResponsePacket(resp))
    }

    /** 注册界面提交：本地哈希后发送注册包。 */
    fun submitRegister(password: String, confirm: String) {
        if (password.isEmpty() || password != confirm) {
            return
        }
        val hash = ClientHashUtil.newHash(
            password,
            ClientHashUtil.DEFAULT_SALT_BYTES,
            ClientHashUtil.DEFAULT_ITERATIONS,
            ClientHashUtil.DEFAULT_KEY_BITS)
        val name = Minecraft.getInstance().user?.name ?: return
        // 客户端无法确知自身是否正版，置 false；服务端会按 TrueUUID 判定重写 verified。
        AuthPackets.sendToServer(AuthPackets.RegisterSubmitPacket(name, hash, false))
    }

    /** 收到登录/注册结果：更新界面与自动登录缓存。 */
    fun onLoginResult(packet: AuthPackets.LoginResultPacket) {
        pendingChallenge = null
        closeScreen()
        val mc = Minecraft.getInstance()
        if (packet.success) {
            // 服务端回传「记住此设备」凭证（期望响应字节，非明文密码）时写入缓存。
            val key = packet.rememberKey
            if (key != null && key.isNotEmpty()) {
                AutoLoginManager.save(key, currentServerIp())
            }
        } else if (packet.message.isNotEmpty()) {
            mc.player?.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§c${packet.message}"), false)
        }
    }

    /** 弹出登录界面（LDLib ModularUIGuiContainer）。 */
    private fun showLoginScreen() {
        val mc = Minecraft.getInstance()
        mc.executeBlocking {
            val ui = LoginScreen.create()
            ui.initWidgets()
            val screen = ModularUIGuiContainer(ui, 0)
            currentScreen = screen
            mc.setScreen(screen)
        }
    }

    /** 切换到注册界面。由登录界面的「注册」按钮调用。 */
    fun showRegisterScreen() {
        val mc = Minecraft.getInstance()
        mc.executeBlocking {
            val ui = RegisterScreen.create()
            ui.initWidgets()
            val screen = ModularUIGuiContainer(ui, 0)
            currentScreen = screen
            mc.setScreen(screen)
        }
    }

    /** 从注册界面返回登录界面。 */
    fun showLoginScreenFromRegister() {
        showLoginScreen()
    }

    /** 关闭当前认证界面。 */
    private fun closeScreen() {
        val mc = Minecraft.getInstance()
        mc.executeBlocking {
            if (mc.screen === currentScreen) {
                mc.setScreen(null)
            }
            currentScreen = null
        }
    }

    /** 取当前连接的服务端 IP（用于自动登录匹配）。无法获取时返回空串。 */
    private fun currentServerIp(): String {
        return try {
            // Minecraft#getConnection() 在 Kotlin 中为属性 connection，
            // ClientPacketListener#getConnection() 同理；Connection#getRemoteAddress() 为属性 remoteAddress。
            val conn = Minecraft.getInstance().connection
            val addr = conn?.connection?.remoteAddress
            addr?.toString() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
