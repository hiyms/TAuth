package top.tdrgame.auth.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel
import java.util.function.Supplier

/**
 * 所有网络包定义。挑战-响应协议。
 */
object AuthPackets {

    private const val PROTOCOL_VERSION = "1"
    val CHANNEL: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation("tauth", "auth"),
        { PROTOCOL_VERSION },
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    )

    fun register() {
        var id = 0
        CHANNEL.registerMessage(id++, LoginRequestPacket::class.java,
            LoginRequestPacket::encode, LoginRequestPacket::decode, LoginRequestPacket::handle)
        CHANNEL.registerMessage(id++, ChallengeRequestPacket::class.java,
            ChallengeRequestPacket::encode, ChallengeRequestPacket::decode, ChallengeRequestPacket::handle)
        CHANNEL.registerMessage(id++, ChallengePacket::class.java,
            ChallengePacket::encode, ChallengePacket::decode, ChallengePacket::handle)
        CHANNEL.registerMessage(id++, ChallengeResponsePacket::class.java,
            ChallengeResponsePacket::encode, ChallengeResponsePacket::decode, ChallengeResponsePacket::handle)
        CHANNEL.registerMessage(id++, LoginResultPacket::class.java,
            LoginResultPacket::encode, LoginResultPacket::decode, LoginResultPacket::handle)
    }

    class LoginRequestPacket(private val mode: String = "login") {
        constructor(buf: FriendlyByteBuf) : this(buf.readUtf())
        fun encode(buf: FriendlyByteBuf) { buf.writeUtf(mode) }
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork { /* Client-side: pop UI */ }
            ctx.get().packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = LoginRequestPacket(buf)
        }
    }

    class ChallengeRequestPacket {
        fun encode(buf: FriendlyByteBuf) {}
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork { /* Server-side: generate challenge */ }
            ctx.get().packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = ChallengeRequestPacket()
        }
    }

    class ChallengePacket(
        private val challenge: Long,
        private val salt: ByteArray
    ) {
        constructor(buf: FriendlyByteBuf) : this(buf.readLong(), buf.readByteArray())
        fun encode(buf: FriendlyByteBuf) {
            buf.writeLong(challenge)
            buf.writeByteArray(salt)
        }
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork { /* Client-side: compute response */ }
            ctx.get().packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = ChallengePacket(buf)
        }
    }

    class ChallengeResponsePacket(private val responseHash: ByteArray) {
        constructor(buf: FriendlyByteBuf) : this(buf.readByteArray())
        fun encode(buf: FriendlyByteBuf) { buf.writeByteArray(responseHash) }
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork { /* Server-side: verify */ }
            ctx.get().packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = ChallengeResponsePacket(buf)
        }
    }

    class LoginResultPacket(private val success: Boolean, private val message: String = "") {
        constructor(buf: FriendlyByteBuf) : this(buf.readBoolean(), buf.readUtf())
        fun encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(success)
            buf.writeUtf(message)
        }
        fun handle(ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork { /* Client-side: close UI */ }
            ctx.get().packetHandled = true
        }
        companion object {
            fun decode(buf: FriendlyByteBuf) = LoginResultPacket(buf)
        }
    }
}
