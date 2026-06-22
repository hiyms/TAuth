package top.tdrgame.auth.mixin;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.util.Crypt;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.security.PublicKey;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientPremiumLoginMixin {

    @Shadow private Connection connection;

    @Unique
    private static final Logger T_LOG = org.slf4j.LoggerFactory.getLogger("TAuth/ClientPremium");

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void tauth$handleHelloSafe(ClientboundHelloPacket packet, CallbackInfo ci) {
        if (!tauth$isPresent()) return;

        Cipher decryptCipher;
        Cipher encryptCipher;
        ServerboundKeyPacket keyPacket;
        String serverId;
        try {
            SecretKey secretKey = Crypt.generateSecretKey();
            PublicKey publicKey = packet.getPublicKey();
            serverId = new BigInteger(Crypt.digestData(packet.getServerId(), publicKey, secretKey)).toString(16);
            decryptCipher = Crypt.getCipher(2, secretKey);
            encryptCipher = Crypt.getCipher(1, secretKey);
            keyPacket = new ServerboundKeyPacket(secretKey, publicKey, packet.getChallenge());
        } catch (Exception e) {
            return; // protocol error, let vanilla handle
        }

        ci.cancel();

        final Cipher dec = decryptCipher;
        final Cipher enc = encryptCipher;
        final ServerboundKeyPacket kp = keyPacket;
        final String sid = serverId;

        Util.backgroundExecutor().execute(() -> {
            tauth$joinServerWithoutDisconnect(sid);
            connection.send(kp, PacketSendListener.thenRun(
                () -> connection.setEncryptionKey(dec, enc)));
        });
    }

    @Unique
    private static void tauth$joinServerWithoutDisconnect(String serverId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            mc.getMinecraftSessionService().joinServer(
                mc.getUser().getGameProfile(),
                mc.getUser().getAccessToken(),
                serverId
            );
            T_LOG.debug("joinServer succeeded");
        } catch (Exception e) {
            T_LOG.debug("joinServer failed (server may fall back to password): {}", e.toString());
        }
    }

    @Unique
    private static boolean tauth$isPresent() {
        try {
            Class.forName("top.tdrgame.auth.TAuth");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
