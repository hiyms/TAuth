package top.tdrgame.auth.client

import com.lowdragmc.lowdraglib.gui.modular.ModularUI
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

    private var loginMessage: String = ""
    private var registerMessage: String = ""
    private var suppressCloseCancel: Boolean = false
    private var authFlowEnding: Boolean = false

    /**
     * 由客户端玩家加入服务器事件触发：向服务端发起登录请求。
     */
    fun tryStartAuth(): Boolean {
        val mc = Minecraft.getInstance()
        val name = mc.user?.name ?: return false
        if (!isAuthChannelAvailable()) return false
        val machineId = AutoLoginManager.machineId().toString()
        val cache = AutoLoginManager.load()
        val server = currentServerId()
        val mode = if (cache != null && cache.lastServer == server && cache.derivedKey.isNotEmpty()) "auto" else "login"
        loginMessage = ""
        registerMessage = ""
        authFlowEnding = false
        AuthPackets.sendToServer(AuthPackets.LoginRequestPacket(mode, name, machineId))
        return true
    }

    /** 收到服务端认证提示：启动自动登录或普通登录流程。 */
    fun onServerAuthPrompt(packet: AuthPackets.StartAuthPacket) {
        tryStartAuth()
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
        if (password.isEmpty()) {
            loginMessage = "请输入密码。"
            showLoginScreen()
            return
        }
        val key = ClientHashUtil.derive(
            password, challenge.salt, challenge.iterations, challenge.keyBits)
        val resp = AuthPackets.challengeResponse(key, challenge.challenge)
        AuthPackets.sendToServer(AuthPackets.ChallengeResponsePacket(resp))
    }

    /** 登录界面点击忘记密码：请求服务端断开并显示管理员重置提示。 */
    fun forgotPassword() {
        if (isAuthChannelAvailable()) {
            authFlowEnding = true
            AuthPackets.sendToServer(AuthPackets.ForgotPasswordPacket())
        }
    }

    /** 注册界面提交：本地哈希后发送注册包。 */
    fun submitRegister(password: String, confirm: String) {
        if (password.isEmpty()) {
            registerMessage = "请输入密码。"
            showRegisterScreen()
            return
        }
        if (password != confirm) {
            registerMessage = "两次输入的密码不一致。"
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
        if (packet.success) {
            pendingChallenge = null
            loginMessage = ""
            registerMessage = ""
            authFlowEnding = true
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

        when (packet.code) {
            AuthPackets.CODE_NOT_REGISTERED -> {
                pendingRegisterPolicy = RegisterPolicy(packet.saltBytes, packet.iterations, packet.keyBits)
                registerMessage = ""
                showRegisterScreen()
            }
            AuthPackets.CODE_AUTO_DENIED -> {
                pendingChallenge = null
                loginMessage = ""
                AuthPackets.sendToServer(AuthPackets.LoginRequestPacket(
                    "login", mc.user?.name ?: return, AutoLoginManager.machineId().toString()))
            }
            AuthPackets.CODE_BAD_PASSWORD -> {
                pendingChallenge = null
                loginMessage = "密码错误。"
                AuthPackets.sendToServer(AuthPackets.LoginRequestPacket(
                    "login", mc.user?.name ?: return, AutoLoginManager.machineId().toString()))
            }
            else -> {
                pendingChallenge = null
                loginMessage = ""
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
        mc.execute {
            openAuthScreen(LoginScreen.create(loginMessage))
        }
    }

    /** 显示注册界面。 */
    private fun showRegisterScreen() {
        val mc = Minecraft.getInstance()
        mc.execute {
            openAuthScreen(RegisterScreen.create(registerMessage))
        }
    }

    private fun openAuthScreen(ui: ModularUI) {
        val mc = Minecraft.getInstance()
        var screen: ModularUIGuiContainer? = null
        ui.registerCloseListener {
            if (currentScreen === screen) {
                currentScreen = null
            }
            if (!suppressCloseCancel && !authFlowEnding && isAuthChannelAvailable()) {
                authFlowEnding = true
                AuthPackets.sendToServer(AuthPackets.CancelAuthPacket())
            }
        }
        ui.initWidgets()
        screen = ModularUIGuiContainer(ui, 0)
        suppressCloseCancel = true
        try {
            currentScreen = screen
            mc.setScreen(screen)
        } finally {
            suppressCloseCancel = false
        }
    }

    /** 关闭当前认证界面。 */
    private fun closeScreen() {
        val mc = Minecraft.getInstance()
        mc.execute {
            suppressCloseCancel = true
            try {
                if (mc.screen === currentScreen) {
                    mc.setScreen(null)
                }
                currentScreen = null
            } finally {
                suppressCloseCancel = false
            }
        }
    }

    /** 当前连接是否支持 TAuth 网络通道。 */
    private fun isAuthChannelAvailable(): Boolean {
        val connection = Minecraft.getInstance().connection?.connection ?: return false
        return AuthPackets.CHANNEL.isRemotePresent(connection)
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
