package top.tdrgame.auth.server

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import top.tdrgame.auth.config.AuthConfig

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
                        ctx.source.sendSuccess(
                            { Component.literal("§a已重置 $targetName 的密码。该玩家下次登录需重新验证。") },
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

        // TASK 规则：曾经用过正版登录的玩家，若想切换到离线登录，
        // 必须在「正版登录」状态下执行 /register 绑定密码。
        // 因此仅当当前为正版登录时，register 才允许在「已有正版身份」的前提下生效；
        // 全新离线玩家（无任何身份）仍可 register。
        val isPremium = TrueUuidBridge.isPremium(name)
        if (storage.hasPremiumHistory(name) && !isPremium) {
            player.sendSystemMessage(Component.literal(
                "§c你曾使用正版登录，必须先以正版身份登录后再 /register 绑定离线密码！"))
            machine?.onLoginFail()
            return
        }

        storage.register(name, password)
        // 注册即视为本轮已认证；若是正版注册，一并记为已验证。
        finishLogin(player, if (isPremium) "online" else "offline")
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
        // 正版登录验证通过后置 verified=true，此后正版登录永远免验证。
        storage.updateVerification(name, verified = isPremium, loginType = loginType)

        finishLogin(player, loginType)
        player.sendSystemMessage(Component.literal("§a登录成功！"))
    }

    private fun finishLogin(player: ServerPlayer, loginType: String) {
        val machine = AuthManager.getStateMachine(player)
        machine?.onLoginSuccess()
        AuthManager.markAuthenticated(player)
        AuthManager.unrestrictPlayer(player)
        EventHandler.restoreInventory(player)
    }

    // ───────────────────────── 网络挑战-响应流程 ─────────────────────────

    /** 服务端待处理挑战：玩家名 → (challenge, 期望响应)。仅在内存，登录完成即清除。 */
    private val pendingChallenges = mutableMapOf<String, Pair<Long, ByteArray>>()

    /** 客户端发起登录请求：生成挑战并回发。 */
    fun handleLoginRequest(player: ServerPlayer, packet: top.tdrgame.auth.network.AuthPackets.LoginRequestPacket) {
        val name = player.name.string
        val storage = TAuthHolder.storage
        // 已认证（正版全新玩家、历史已验证等）→ 直接告知客户端放行，无需挑战。
        if (AuthManager.isAuthenticated(player)) {
            top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
                top.tdrgame.auth.network.AuthPackets.LoginResultPacket(true, "已认证。", null))
            return
        }
        val params = storage.getHashParams(name)
        if (params == null) {
            top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
                top.tdrgame.auth.network.AuthPackets.LoginResultPacket(false,
                    "你尚未注册。请使用 /register 注册！", null))
            AuthManager.getStateMachine(player)?.onLoginFail()
            return
        }
        // 随机挑战值；期望响应 = SHA256(storedHash || challenge)
        val challenge = java.security.SecureRandom().nextLong()
        val expected = top.tdrgame.auth.network.AuthPackets.challengeResponse(params.hash, challenge)
        pendingChallenges[name] = challenge to expected
        top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
            top.tdrgame.auth.network.AuthPackets.ChallengePacket(
                challenge, params.salt, params.iterations, params.keyLengthBits))
    }

    /** 客户端回传挑战响应：校验并完成登录。 */
    fun handleChallengeResponse(player: ServerPlayer, packet: top.tdrgame.auth.network.AuthPackets.ChallengeResponsePacket) {
        val name = player.name.string
        val pending = pendingChallenges.remove(name)
        if (pending == null) {
            top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
                top.tdrgame.auth.network.AuthPackets.LoginResultPacket(false, "无待处理挑战，请重试。", null))
            return
        }
        val (_, expected) = pending
        if (!java.util.Arrays.equals(expected, packet.response)) {
            AuthManager.getStateMachine(player)?.onLoginFail()
            top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
                top.tdrgame.auth.network.AuthPackets.LoginResultPacket(false, "密码错误！", null))
            return
        }
        val isPremium = TrueUuidBridge.isPremium(name)
        val loginType = if (isPremium) "online" else "offline"
        TAuthHolder.storage.updateVerification(name, verified = isPremium, loginType = loginType)
        finishLogin(player, loginType)
        // 回传成功 + 期望响应字节作为「记住此设备」的凭证（非明文密码）。
        top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
            top.tdrgame.auth.network.AuthPackets.LoginResultPacket(true, "登录成功！", expected))
    }

    /** 客户端提交注册（已本地哈希）：写入并完成登录。 */
    fun handleRegisterSubmit(player: ServerPlayer, packet: top.tdrgame.auth.network.AuthPackets.RegisterSubmitPacket) {
        val name = player.name.string
        val isPremium = packet.isPremium
        // 与命令路径同样的约束：曾用正版登录的玩家必须正版身份下注册。
        if (TAuthHolder.storage.hasPremiumHistory(name) && !isPremium) {
            top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
                top.tdrgame.auth.network.AuthPackets.LoginResultPacket(false,
                    "你曾使用正版登录，必须先以正版身份登录后再注册绑定离线密码！", null))
            AuthManager.getStateMachine(player)?.onLoginFail()
            return
        }
        if (!TAuthHolder.storage.registerWithHash(name, packet.passwordHash)) {
            top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
                top.tdrgame.auth.network.AuthPackets.LoginResultPacket(false, "你已注册，请直接登录。", null))
            return
        }
        finishLogin(player, if (isPremium) "online" else "offline")
        top.tdrgame.auth.network.AuthPackets.sendToPlayer(player,
            top.tdrgame.auth.network.AuthPackets.LoginResultPacket(true, "注册成功，已自动登录！", null))
    }
}
