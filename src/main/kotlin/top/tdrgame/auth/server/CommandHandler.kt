package top.tdrgame.auth.server

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import top.tdrgame.auth.TAuth
import top.tdrgame.auth.config.AuthConfig
import top.tdrgame.auth.i18n.I18nKeys
import top.tdrgame.auth.i18n.ServerI18n
import top.tdrgame.auth.network.AuthPackets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册 /login、/register、/resetpasswd 命令。
 */
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
                                player.sendSystemMessage(ServerI18n.text(I18nKeys.AUTH_DISABLED).withStyle(net.minecraft.ChatFormatting.RED))
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
                        clearPendingChallenge(targetName)
                        val target = ctx.source.server.playerList.getPlayerByName(targetName)
                        if (target != null) {
                            AuthManager.forcePending(target,
                                isPremium = AuthManager.isPremiumSession(target),
                                isVerified = false,
                                isRegistered = false)
                            EventHandler.hideInventoryForAuth(target)
                            AuthPackets.sendToPlayerIfPresent(target, AuthPackets.StartAuthPacket())
                        }
                        ctx.source.sendSuccess(
                            { ServerI18n.text(I18nKeys.RESET_SUCCESS, targetName).withStyle(net.minecraft.ChatFormatting.GREEN) },
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
            player.sendSystemMessage(ServerI18n.text(I18nKeys.REGISTER_PASSWORD_MISMATCH).withStyle(net.minecraft.ChatFormatting.RED))
            machine?.onLoginFail()
            return
        }

        if (storage.isRegistered(name)) {
            player.sendSystemMessage(ServerI18n.text(I18nKeys.ALREADY_REGISTERED_LOGIN).withStyle(net.minecraft.ChatFormatting.RED))
            return
        }

        val isPremium = AuthManager.isPremiumSession(player)
        val loginType = if (isPremium) "online" else "offline"
        storage.register(name, password, verified = isPremium, loginType = loginType)
        finishLogin(player)
        player.sendSystemMessage(ServerI18n.text(I18nKeys.REGISTER_SUCCESS).withStyle(net.minecraft.ChatFormatting.GREEN))
    }

    private fun handleLogin(player: ServerPlayer, password: String) {
        val name = player.name.string
        val storage = TAuthHolder.storage
        val machine = AuthManager.getStateMachine(player)

        if (!storage.isRegistered(name)) {
            player.sendSystemMessage(ServerI18n.text(I18nKeys.NOT_REGISTERED_REGISTER).withStyle(net.minecraft.ChatFormatting.RED))
            machine?.onLoginFail()
            return
        }

        if (!storage.checkPassword(name, password)) {
            player.sendSystemMessage(ServerI18n.text(I18nKeys.BAD_PASSWORD).withStyle(net.minecraft.ChatFormatting.RED))
            machine?.onLoginFail()
            return
        }

        val isPremium = AuthManager.isPremiumSession(player)
        val loginType = if (isPremium) "online" else "offline"
        storage.updateVerification(name, isPremium = isPremium, loginType = loginType)

        finishLogin(player)
        player.sendSystemMessage(ServerI18n.text(I18nKeys.LOGIN_SUCCESS).withStyle(net.minecraft.ChatFormatting.GREEN))
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

    /** 玩家离线、被重置或被踢出时清理未完成的挑战会话。 */
    fun clearPendingChallenge(playerName: String) {
        pendingChallenges.remove(playerName)
    }

    /** 客户端发起登录请求：生成挑战并回发。 */
    fun handleLoginRequest(player: ServerPlayer, packet: AuthPackets.LoginRequestPacket) {
        if (!AuthConfig.enabled.get()) {
            TAuth.LOGGER.info("Ignoring client auth request from {} because authentication is disabled.", player.name.string)
            AuthPackets.sendToPlayer(player,
                result(true, AuthPackets.CODE_LOGIN_OK, ServerI18n.fallback(I18nKeys.AUTH_DISABLED)))
            return
        }
        val name = player.name.string
        val storage = TAuthHolder.storage
        if (AuthManager.isAuthenticated(player)) {
            AuthPackets.sendToPlayer(player, result(true, AuthPackets.CODE_LOGIN_OK, ServerI18n.fallback(I18nKeys.ALREADY_AUTHENTICATED)))
            return
        }

        val params = storage.getHashParams(name)
        if (params == null) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_NOT_REGISTERED,
                    ServerI18n.fallback(I18nKeys.NOT_REGISTERED_GUI), policy = storage.hashPolicy()))
            return
        }

        val autoLogin = packet.mode == "auto"
        val machineId = packet.machineId
        if (autoLogin && !storage.isAutoLoginAllowed(name, machineId, AuthManager.getPlayerIp(player))) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_AUTO_DENIED, ServerI18n.fallback(I18nKeys.AUTO_LOGIN_DENIED)))
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
        if (!AuthConfig.enabled.get()) {
            pendingChallenges.remove(player.name.string)
            AuthPackets.sendToPlayer(player,
                result(true, AuthPackets.CODE_LOGIN_OK, ServerI18n.fallback(I18nKeys.AUTH_DISABLED)))
            return
        }
        val name = player.name.string
        val session = pendingChallenges.remove(name)
        if (session == null) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_ERROR, ServerI18n.fallback(I18nKeys.NO_PENDING_CHALLENGE)))
            return
        }
        if (!MessageDigest.isEqual(session.expected, packet.response)) {
            AuthManager.getStateMachine(player)?.onLoginFail()
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_BAD_PASSWORD, ServerI18n.fallback(I18nKeys.BAD_PASSWORD)))
            return
        }
        val isPremium = AuthManager.isPremiumSession(player)
        val loginType = if (isPremium) "online" else "offline"
        TAuthHolder.storage.updateVerification(name, isPremium = isPremium, loginType = loginType)
        if (!session.machineId.isNullOrBlank()) {
            TAuthHolder.storage.updateAutoLogin(name, session.machineId, AuthManager.getPlayerIp(player))
        }
        finishLogin(player)
        AuthPackets.sendToPlayer(player,
            result(true, AuthPackets.CODE_LOGIN_OK, ServerI18n.fallback(I18nKeys.LOGIN_SUCCESS),
                rememberKey = if (!session.machineId.isNullOrBlank()) session.derivedKey else null))
    }

    /** 客户端提交注册（已本地哈希）：写入并完成登录。 */
    fun handleRegisterSubmit(player: ServerPlayer, packet: AuthPackets.RegisterSubmitPacket) {
        if (!AuthConfig.enabled.get()) {
            AuthPackets.sendToPlayer(player,
                result(true, AuthPackets.CODE_LOGIN_OK, ServerI18n.fallback(I18nKeys.AUTH_DISABLED)))
            return
        }
        val name = player.name.string
        val isPremium = AuthManager.isPremiumSession(player)
        val loginType = if (isPremium) "online" else "offline"
        val parsed = PasswordHasher.parse(packet.passwordHash)
        if (parsed == null) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_ERROR, ServerI18n.fallback(I18nKeys.INVALID_PASSWORD_HASH)))
            return
        }
        if (!TAuthHolder.storage.registerWithHash(name, packet.passwordHash, verified = isPremium, loginType = loginType)) {
            AuthPackets.sendToPlayer(player,
                result(false, AuthPackets.CODE_ALREADY_REGISTERED, ServerI18n.fallback(I18nKeys.ALREADY_REGISTERED_DIRECT_LOGIN)))
            return
        }
        if (!packet.machineId.isNullOrBlank()) {
            TAuthHolder.storage.updateAutoLogin(name, packet.machineId, AuthManager.getPlayerIp(player))
        }
        finishLogin(player)
        AuthPackets.sendToPlayer(player,
            result(true, AuthPackets.CODE_REGISTER_OK, ServerI18n.fallback(I18nKeys.REGISTER_SUCCESS),
                rememberKey = if (!packet.machineId.isNullOrBlank()) parsed.hash else null))
    }

    private fun result(
        success: Boolean,
        code: String,
        message: String,
        rememberKey: ByteArray? = null,
        policy: PasswordStorage.HashPolicy = configuredHashPolicy()
    ): AuthPackets.LoginResultPacket = AuthPackets.LoginResultPacket(
        success = success,
        code = code,
        message = message,
        rememberKey = rememberKey,
        saltBytes = policy.saltBytes,
        iterations = policy.iterations,
        keyBits = policy.keyLengthBits
    )

    private fun configuredHashPolicy(): PasswordStorage.HashPolicy = PasswordStorage.HashPolicy(
        saltBytes = AuthConfig.saltBytes.get().coerceIn(PasswordStorage.LEGACY_SALT_BYTES, 64),
        iterations = AuthConfig.iterations.get().coerceAtLeast(1000),
        keyLengthBits = AuthConfig.keyLengthBits.get().coerceAtLeast(128)
    )
}
