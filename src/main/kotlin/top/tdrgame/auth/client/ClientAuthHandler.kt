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
 * - 收到挑战后，若有匹配服务器地址的自动登录缓存则静默完成，否则弹出 [LoginScreen]；
 * - 登录界面提交密码后本地 PBKDF2 + 挑战响应；
 * - 登录成功且服务端返回「记住此设备」凭证时写入 [AutoLoginManager]。
 */
@OnlyIn(Dist.CLIENT)
object ClientAuthHandler {

    /** 当前等待玩家输入密码的挑战上下文。 */
    private var pendingChallenge: AuthPackets.ChallengePacket? = null

    /** 当前展示的认证界面，用于登录成功后关闭。 */
    private var currentScreen: ModularUIGuiContainer? = null

    private var lastMessage: String = ""

    /**
     * 由客户端玩家加入服务器事件触发：向服务端发起登录请求。
     */
    fun onClientPlayerJoin() {
        val mc = Minecraft.getInstance()
        val name = mc.user?.name ?: return
        val machineId = AutoLoginManager.machineId().toString()
        val cache = AutoLoginManager.load()
        val server = currentServerId()
        val mode = if (cache != null && cache.lastServer == server && cache.derivedKey.isNotEmpty()) "auto" else "login"
        mc.executeBlocking {
            AuthPackets.sendToServer(AuthPackets.LoginRequestPacket(mode, name, machineId))
        }
    }

    /** 收到服务端挑战：尝试自动登录，否则弹登录界面。 */
    fun onChallenge(packet: AuthPackets.ChallengePacket) {
        if (packet.autoLogin) {
            val cache = AutoLoginManager.load()
            val currentServer = currentServerId()
            if (cache != null && cache.lastServer == currentServer && cache.derivedKey.isNotEmpty()) {
                val resp = AuthPackets.challengeResponse(cache.derivedKey, packet.challenge)
                AuthPackets.sendToServer(AuthPackets.ChallengeResponsePacket(resp))
                return
            }
        }
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
            lastMessage = "两次输入的密码不一致。"
            showRegisterScreen()
            return
        }
        val hash = ClientHashUtil.newHash(
            password,
            pendingRegisterSaltBytes(),
            pendingRegisterIterations(),
            pendingRegisterKeyBits())
        AuthPackets.sendToServer(AuthPackets.RegisterSubmitPacket(hash.storedHash, AutoLoginManager.machineId().toString()))
    }

    /** 收到登录/注册结果：更新界面与自动登录缓存。 */
    fun onLoginResult(packet: AuthPackets.LoginResultPacket) {
        val mc = Minecraft.getInstance()
        lastMessage = packet.message
        if (packet.success) {
            pendingChallenge = null
            closeScreen()
            val key = packet.rememberKey
            if (key != null && key.isNotEmpty()) {
                AutoLoginManager.save(key, currentServerId())
            }
            if (packet.message.isNotEmpty()) {
                mc.player?.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§a${packet.message}"), false)
            }
            return
        }

        if (packet.message.isNotEmpty()) {
            mc.player?.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§c${packet.message}"), false)
        }
        when (packet.code) {
            AuthPackets.CODE_NOT_REGISTERED -> {
                pendingRegisterPolicy = RegisterPolicy(packet.saltBytes, packet.iterations, packet.keyBits)
                showRegisterScreen()
            }
            AuthPackets.CODE_AUTO_DENIED -> {
                pendingChallenge = null
                AuthPackets.sendToServer(AuthPackets.LoginRequestPacket(
                    "login", mc.user?.name ?: return, AutoLoginManager.machineId().toString()))
            }
            else -> {
                pendingChallenge = null
                AuthPackets.sendToServer(AuthPackets.LoginRequestPacket(
                    "login", mc.user?.name ?: return, AutoLoginManager.machineId().toString()))
            }
        }
    }

    private data class RegisterPolicy(val saltBytes: Int, val iterations: Int, val keyBits: Int)
    private var pendingRegisterPolicy: RegisterPolicy? = null

    private fun pendingRegisterSaltBytes(): Int = pendingRegisterPolicy?.saltBytes ?: ClientHashUtil.DEFAULT_SALT_BYTES
    private fun pendingRegisterIterations(): Int = pendingRegisterPolicy?.iterations ?: ClientHashUtil.DEFAULT_ITERATIONS
    private fun pendingRegisterKeyBits(): Int = pendingRegisterPolicy?.keyBits ?: ClientHashUtil.DEFAULT_KEY_BITS

    /** 弹出登录界面（LDLib ModularUIGuiContainer）。 */
    private fun showLoginScreen() {
        val mc = Minecraft.getInstance()
        mc.executeBlocking {
            val ui = LoginScreen.create(lastMessage)
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
            val ui = RegisterScreen.create(lastMessage)
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

    /** 取当前连接的服务端地址（用于自动登录匹配）。 */
    private fun currentServerId(): String {
        val mc = Minecraft.getInstance()
        return mc.currentServer?.ip ?: try {
            val conn = mc.connection
            conn?.connection?.remoteAddress?.toString() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
