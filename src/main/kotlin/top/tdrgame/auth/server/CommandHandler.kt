package top.tdrgame.auth.server

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import top.tdrgame.auth.config.AuthConfig
import top.tdrgame.auth.network.AuthPackets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册 /login、/register、/resetpasswd 命令。
 */
@Mod.EventBusSubscriber
object CommandHandler {

    @SubscribeEvent
    @JvmStatic
    fun registerCommands(event: RegisterCommandsEvent) {
        // /register <password> <confirm>
        event.dispatcher.register(
            Commands.literal("register")
                .then(Commands.argument("password", StringArgumentType.string())
                    .then(Commands.argument("confirm", StringArgumentType.string())
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            if (!AuthConfig.enabled.get()) {
                                player.sendSystemMessage(Component.literal("§c认证功能未启用。"))
                                return@executes 1
                            }
                            handleRegister(player,
                                StringArgumentType.getString(ctx, "password"),
                                StringArgumentType.getString(ctx, "confirm"))
                            return@executes 1
                        })))

        // /reg (alias)
        event.dispatcher.register(
            Commands.literal("reg")
                .then(Commands.argument("password", StringArgumentType.string())
                    .then(Commands.argument("confirm", StringArgumentType.string())
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            if (!AuthConfig.enabled.get()) return@executes 1
                            handleRegister(player,
                                StringArgumentType.getString(ctx, "password"),
                                StringArgumentType.getString(ctx, "confirm"))
                            return@executes 1
                        })))

        // /login <password>
        event.dispatcher.register(
            Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.string())
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        if (!AuthConfig.enabled.get()) return@executes 1
                        handleLogin(player,
                            StringArgumentType.getString(ctx, "password"))
                        return@executes 1
                    }))

        // /l (alias)
        event.dispatcher.register(
            Commands.literal("l")
                .then(Commands.argument("password", StringArgumentType.string())
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        if (!AuthConfig.enabled.get()) return@executes 1
                        handleLogin(player,
                            StringArgumentType.getString(ctx, "password"))
                        return@executes 1
                    }))

        // /resetpasswd <player> (OP only)
        event.dispatcher.register(
            Commands.literal("resetpasswd")
                .requires { it.hasPermission(2) }
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes { ctx ->
                        val targetName = StringArgumentType.getString(ctx, "player")
                        if (!AuthConfig.enabled.get()) return@executes 1
                        TAuthHolder.storage.delete(targetName)
                        pendingChallenges.remove(targetName)
                        val target = ctx.source.server.playerList.getPlayerByName(targetName)
                        if (target != null) {
                            AuthManager.forcePending(target,
                                isPremium = TrueUuidBridge.isPremium(target.name.string),
                                isVerified = false,
                                isRegistered = false)
                            EventHandler.hideInventoryForAuth(target)
                            target.sendSystemMessage(Component.literal(
                                "§c你的密码已被管理员重置，请重新 /register。"))
                        }
                        ctx.source.sendSuccess(
                            { Component.literal("§a已重置 $targetName 的密码。该玩家需要重新注册。") },
                            true
                        )
                        return@executes 1
                    }))
    }

    private fun handleRegister(player: ServerPlayer, password: String, confirm: String) {
        val name = player.name.string
        val storage = TAuthHolder.storage
        val machine = AuthManager.getStateMachine(player)

        if (password != confirm) {
            player.sendSystemMessage(Component.literal("§c两次输入的密码不一致！"))
            machine?.onLoginFail()
            return
        }

        if (storage.isRegistered(name)) {
            player.sendSystemMessage(Component.literal("§c你已注册。请使用 /login 密码 登录！"))
            return
        }

        val isPremium = TrueUuidBridge.isPremium(name)
        val loginType = if (isPremium) "online" else "offline"
        storage.register(name, password, verified = isPremium, loginType = loginType)
        finishLogin(player)
        player.sendSystemMessage(Component.literal("§a注册成功，已自动登录！"))
    }

    private fun handleLogin(player: ServerPlayer, password: String) {
        val name = player.name.string
        val storage = TAuthHolder.storage
        val machine = AuthManager.getStateMachine(player)

        if (!storage.isRegistered(name)) {
            player.sendSystemMessage(Component.literal("§c你尚未注册。请使用 /register 密码 确认密码 注册！"))
            machine?.onLoginFail()
            return
        }

        if (!storage.checkPassword(name, password)) {
            player.sendSystemMessage(Component.literal("§c密码错误！"))
            machine?.onLoginFail()
            return
        }

        val isPremium = TrueUuidBridge.isPremium(name)
        val loginType = if (isPremium) "online" else "offline"
        storage.updateVerification(name, isPremium = isPremium, loginType = loginType)

        finishLogin(player)
        player.sendSystemMessage(Component.literal("§a登录成功！"))
    }

    private fun finishLogin(player: ServerPlayer) {
        val machine = AuthManager.getStateMachine(player)
        machine?.onLoginSuccess()
        AuthManager.markAuthenticated(player)
        AuthManager.unrestrictPlayer(player)
        EventHandler.restoreInventory(player)
    }

    // ───────────────────────── 网络挑战-响应流程 ─────────────────────────

    private data class ChallengeSession(
        val challenge: Long,
        val expected: ByteArray,
        val derivedKey: ByteArray,
        val machineId: String?,
        val autoLogin: Boolean
    )

    /** 服务端待处理挑战：玩家名 → challenge 会话。仅在内存，登录完成即清除。 */
    private val pendingChallenges = ConcurrentHashMap<String, ChallengeSession>()

    /** 客户端发起登录请求：生成挑战并回发。 */
    fun handleLoginRequest(player: ServerPlayer, packet: AuthPackets.LoginRequestPacket) {
        val name = player.name.string
        val storage = TAuthHolder.storage
        if (AuthManager.isAuthenticated(player)) {
            AuthPackets.sendToPlayer(player, result(true, AuthPackets.CODE_LOGIN_OK, "已认证。"))
            return
        }

        val params = storage.getHashParams(name)
        if (params == null) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_NOT_REGISTERED,
                    "你尚未注册。请使用 /register 注册！", policy = storage.hashPolicy()))
            return
        }

        val autoLogin = packet.mode == "auto"
        val machineId = packet.machineId
        if (autoLogin && !storage.isAutoLoginAllowed(name, machineId, AuthManager.getPlayerIp(player))) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_AUTO_DENIED, "自动登录条件不匹配，请手动登录。"))
            return
        }

        // 随机挑战值；期望响应 = SHA256(storedHash || challenge)
        val challenge = SecureRandom().nextLong()
        val expected = AuthPackets.challengeResponse(params.hash, challenge)
        pendingChallenges[name] = ChallengeSession(challenge, expected, params.hash, machineId, autoLogin)
        AuthPackets.sendToPlayer(player,
            AuthPackets.ChallengePacket(
                challenge, params.salt, params.iterations, params.keyLengthBits, autoLogin))
    }

    /** 客户端回传挑战响应：校验并完成登录。 */
    fun handleChallengeResponse(player: ServerPlayer, packet: AuthPackets.ChallengeResponsePacket) {
        val name = player.name.string
        val session = pendingChallenges.remove(name)
        if (session == null) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_ERROR, "无待处理挑战，请重试。"))
            return
        }
        if (!MessageDigest.isEqual(session.expected, packet.response)) {
            AuthManager.getStateMachine(player)?.onLoginFail()
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_BAD_PASSWORD, "密码错误！"))
            return
        }
        val isPremium = TrueUuidBridge.isPremium(name)
        val loginType = if (isPremium) "online" else "offline"
        TAuthHolder.storage.updateVerification(name, isPremium = isPremium, loginType = loginType)
        if (!session.machineId.isNullOrBlank()) {
            TAuthHolder.storage.updateAutoLogin(name, session.machineId, AuthManager.getPlayerIp(player))
        }
        finishLogin(player)
        AuthPackets.sendToPlayer(player,
            result(true, AuthPackets.CODE_LOGIN_OK, "登录成功！",
                rememberKey = if (!session.machineId.isNullOrBlank()) session.derivedKey else null))
    }

    /** 客户端提交注册（已本地哈希）：写入并完成登录。 */
    fun handleRegisterSubmit(player: ServerPlayer, packet: AuthPackets.RegisterSubmitPacket) {
        val name = player.name.string
        val isPremium = TrueUuidBridge.isPremium(name)
        val loginType = if (isPremium) "online" else "offline"
        val parsed = PasswordHasher.parse(packet.passwordHash)
        if (parsed == null) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_ERROR, "密码哈希格式非法，请重试。"))
            return
        }
        if (!TAuthHolder.storage.registerWithHash(name, packet.passwordHash, verified = isPremium, loginType = loginType)) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_ALREADY_REGISTERED, "你已注册，请直接登录。"))
            return
        }
        if (!packet.machineId.isNullOrBlank()) {
            TAuthHolder.storage.updateAutoLogin(name, packet.machineId, AuthManager.getPlayerIp(player))
        }
        finishLogin(player)
        AuthPackets.sendToPlayer(player,
            result(true, AuthPackets.CODE_REGISTER_OK, "注册成功，已自动登录！",
                rememberKey = if (!packet.machineId.isNullOrBlank()) parsed.hash else null))
    }

    private fun result(
        success: Boolean,
        code: String,
        message: String,
        rememberKey: ByteArray? = null,
        policy: PasswordStorage.HashPolicy = TAuthHolder.storage.hashPolicy()
    ): AuthPackets.LoginResultPacket = AuthPackets.LoginResultPacket(
        success = success,
        code = code,
        message = message,
        rememberKey = rememberKey,
        saltBytes = policy.saltBytes,
        iterations = policy.iterations,
        keyBits = policy.keyLengthBits
    )
}
