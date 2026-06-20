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

        storage.register(name, password)
        finishLogin(player, "offline")
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

        val isPremium = tryDetectPremium(name)
        val loginType = if (isPremium) "online" else "offline"
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

    private fun tryDetectPremium(name: String): Boolean {
        return try {
            val apiClass = Class.forName("cn.alini.trueuuid.api.TrueuuidApi")
            val method = apiClass.getMethod("isPremium", String::class.java)
            method.invoke(null, name.lowercase()) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
    }
}
