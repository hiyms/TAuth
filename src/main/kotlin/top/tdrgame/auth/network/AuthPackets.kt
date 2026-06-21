package top.tdrgame.auth.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import net.minecraft.network.chat.Component
import top.tdrgame.auth.i18n.I18nKeys
import top.tdrgame.auth.i18n.ServerI18n
import top.tdrgame.auth.server.CommandHandler
import java.security.MessageDigest
import java.util.function.Supplier

/**
 * 挑战-响应认证协议。
 *
 * 核心要求：PBKDF2 哈希计算在客户端完成（见 TASK「哈希计算移到客户端」）。
 * 服务端仍保留命令登录路径，确保客户端未安装本模组时也可以登录/注册。
 */
object AuthPackets {

    const val CODE_LOGIN_OK = "LOGIN_OK"
    const val CODE_REGISTER_OK = "REGISTER_OK"
    const val CODE_BAD_PASSWORD = "BAD_PASSWORD"
    const val CODE_NOT_REGISTERED = "NOT_REGISTERED"
    const val CODE_ALREADY_REGISTERED = "ALREADY_REGISTERED"
    const val CODE_AUTO_DENIED = "AUTO_DENIED"
    const val CODE_POLICY_DENIED = "POLICY_DENIED"
    const val CODE_ERROR = "ERROR"

    private const val PROTOCOL_VERSION = "4"
    private val acceptsRemote = NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION)

    val CHANNEL: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation("tauth", "auth"),
        { PROTOCOL_VERSION },
        acceptsRemote,
        acceptsRemote
    )

    fun register() {
        var id = 0
        // 服务端 → 客户端：提示客户端启动登录流程
        CHANNEL.registerMessage(id++, StartAuthPacket::class.java,
            StartAuthPacket::encode, StartAuthPacket::decode, StartAuthPacket::handle)
        // 客户端 → 服务端：请求登录挑战
        CHANNEL.registerMessage(id++, LoginRequestPacket::class.java,
            LoginRequestPacket::encode, LoginRequestPacket::decode, LoginRequestPacket::handle)
        // 服务端 → 客户端：下发挑战（salt + 哈希参数 + nonce）
        CHANNEL.registerMessage(id++, ChallengePacket::class.java,
            ChallengePacket::encode, ChallengePacket::decode, ChallengePacket::handle)
        // 客户端 → 服务端：挑战响应
        CHANNEL.registerMessage(id++, ChallengeResponsePacket::class.java,
            ChallengeResponsePacket::encode, ChallengeResponsePacket::decode, ChallengeResponsePacket::handle)
        // 客户端 → 服务端：忘记密码，提示联系管理员并断开连接
        CHANNEL.registerMessage(id++, ForgotPasswordPacket::class.java,
            ForgotPasswordPacket::encode, ForgotPasswordPacket::decode, ForgotPasswordPacket::handle)
        // 客户端 → 服务端：玩家关闭认证 GUI，取消验证会话
        CHANNEL.registerMessage(id++, CancelAuthPacket::class.java,
            CancelAuthPacket::encode, CancelAuthPacket::decode, CancelAuthPacket::handle)
        // 客户端 → 服务端：注册提交（已本地哈希）
        CHANNEL.registerMessage(id++, RegisterSubmitPacket::class.java,
            RegisterSubmitPacket::encode, RegisterSubmitPacket::decode, RegisterSubmitPacket::handle)
        // 服务端 → 客户端：结果 / 注册策略
        CHANNEL.registerMessage(id++, LoginResultPacket::class.java,
            LoginResultPacket::encode, LoginResultPacket::decode, LoginResultPacket::handle)
    }

    /** 服务端 → 客户端：提示客户端发起登录请求。 */
    class StartAuthPacket {
        constructor()
        constructor(buf: FriendlyByteBuf)
        fun encode(buf: FriendlyByteBuf) {}
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork { runClientHandler("onServerAuthPrompt", this) }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = StartAuthPacket(buf)
        }
    }

    /** 客户端 → 服务端：发起登录请求。 */
    class LoginRequestPacket(
        val mode: String,
        val playerName: String,
        val machineId: String?
    ) {
        constructor(buf: FriendlyByteBuf) : this(
            buf.readUtf(16),
            buf.readUtf(64),
            if (buf.readBoolean()) buf.readUtf(64) else null
        )
        fun encode(buf: FriendlyByteBuf) {
            buf.writeUtf(mode)
            buf.writeUtf(playerName)
            buf.writeBoolean(machineId != null)
            if (machineId != null) buf.writeUtf(machineId)
        }
        /** 服务端处理：生成挑战并回发。 */
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                CommandHandler.handleLoginRequest(player, this)
            }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = LoginRequestPacket(buf)
        }
    }

    /** 服务端 → 客户端：挑战包。 */
    class ChallengePacket(
        val challenge: Long,
        val salt: ByteArray,
        val iterations: Int,
        val keyBits: Int,
        val autoLogin: Boolean
    ) {
        constructor(buf: FriendlyByteBuf) : this(
            buf.readLong(), buf.readByteArray(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()
        )
        fun encode(buf: FriendlyByteBuf) {
            buf.writeLong(challenge)
            buf.writeByteArray(salt)
            buf.writeVarInt(iterations)
            buf.writeVarInt(keyBits)
            buf.writeBoolean(autoLogin)
        }
        /** 客户端处理：用当前输入（或缓存）的密码本地计算响应。 */
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork { runClientHandler("onChallenge", this) }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = ChallengePacket(buf)
        }
    }

    /** 客户端 → 服务端：挑战响应。 */
    class ChallengeResponsePacket(val response: ByteArray) {
        constructor(buf: FriendlyByteBuf) : this(buf.readByteArray())
        fun encode(buf: FriendlyByteBuf) { buf.writeByteArray(response) }
        /** 服务端处理：校验响应并回发结果。 */
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                CommandHandler.handleChallengeResponse(player, this)
            }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = ChallengeResponsePacket(buf)
        }
    }

    /** 客户端 → 服务端：忘记密码，服务端断开连接并提示联系管理员。 */
    class ForgotPasswordPacket {
        constructor()
        constructor(buf: FriendlyByteBuf)
        fun encode(buf: FriendlyByteBuf) {}
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                player.connection.disconnect(ServerI18n.text(I18nKeys.FORGOT_PASSWORD_DISCONNECT))
            }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = ForgotPasswordPacket(buf)
        }
    }

    /** 客户端 → 服务端：玩家关闭认证 GUI，服务端断开连接。 */
    class CancelAuthPacket {
        constructor()
        constructor(buf: FriendlyByteBuf)
        fun encode(buf: FriendlyByteBuf) {}
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                player.connection.disconnect(ServerI18n.text(I18nKeys.CANCEL_AUTH_DISCONNECT))
            }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = CancelAuthPacket(buf)
        }
    }

    /** 客户端 → 服务端：注册提交（passwordHash 已在客户端本地计算）。 */
    class RegisterSubmitPacket(
        val passwordHash: String,
        val machineId: String?
    ) {
        constructor(buf: FriendlyByteBuf) : this(
            buf.readUtf(512),
            if (buf.readBoolean()) buf.readUtf(64) else null
        )
        fun encode(buf: FriendlyByteBuf) {
            buf.writeUtf(passwordHash)
            buf.writeBoolean(machineId != null)
            if (machineId != null) buf.writeUtf(machineId)
        }
        /** 服务端处理：写入数据库并回发结果。 */
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                CommandHandler.handleRegisterSubmit(player, this)
            }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = RegisterSubmitPacket(buf)
        }
    }

    /** 服务端 → 客户端：登录/注册结果。 */
    class LoginResultPacket(
        val success: Boolean,
        val code: String,
        val message: String,
        val rememberKey: ByteArray?,
        val saltBytes: Int,
        val iterations: Int,
        val keyBits: Int
    ) {
        constructor(buf: FriendlyByteBuf) : this(
            buf.readBoolean(), buf.readUtf(32), buf.readUtf(256),
            if (buf.readBoolean()) buf.readByteArray() else null,
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt()
        )
        fun encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(success)
            buf.writeUtf(code)
            buf.writeUtf(message)
            if (rememberKey != null) {
                buf.writeBoolean(true)
                buf.writeByteArray(rememberKey)
            } else {
                buf.writeBoolean(false)
            }
            buf.writeVarInt(saltBytes)
            buf.writeVarInt(iterations)
            buf.writeVarInt(keyBits)
        }
        /** 客户端处理：更新 UI 与自动登录缓存。 */
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            val context = ctx.get()
            context.enqueueWork { runClientHandler("onLoginResult", this) }
            context.packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = LoginResultPacket(buf)
        }
    }

    /** 工具：计算 PBKDF2 派生 key 与 challenge 的 SHA-256。客户端与服务端共用。 */
    fun challengeResponse(derivedKey: ByteArray, challenge: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(derivedKey)
        val buf = java.nio.ByteBuffer.allocate(8).putLong(challenge).array()
        md.update(buf)
        return md.digest()
    }

    /** 向指定玩家发包的便捷方法。 */
    fun sendToPlayer(player: ServerPlayer, packet: Any) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, packet)
    }

    /** 仅当客户端安装了 TAuth 且完成通道握手时发包。 */
    fun sendToPlayerIfPresent(player: ServerPlayer, packet: Any) {
        if (CHANNEL.isRemotePresent(player.connection.connection)) {
            sendToPlayer(player, packet)
        }
    }

    /** 客户端向服务端发包的便捷方法。 */
    fun sendToServer(packet: Any) {
        CHANNEL.sendToServer(packet)
    }

    private fun runClientHandler(methodName: String, packet: Any) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
            Runnable {
                try {
                    val handlerClass = Class.forName("top.tdrgame.auth.client.ClientAuthHandler")
                    val instance = handlerClass.getField("INSTANCE").get(null)
                    val method = handlerClass.methods.firstOrNull { method ->
                        method.name == methodName && method.parameterTypes.size == 1 &&
                            method.parameterTypes[0].isAssignableFrom(packet.javaClass)
                    } ?: return@Runnable
                    method.invoke(instance, packet)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }
}
