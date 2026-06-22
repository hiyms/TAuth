package top.tdrgame.auth.mixin;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.util.Crypt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.PublicKey;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientPremiumLoginMixin {

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void tauth$bypassJoinServer(ClientboundHelloPacket packet, CallbackInfo ci) {
        if (!tauth$isPresent()) return;

        try {
            SecretKey secretKey = Crypt.generateSecretKey();
            PublicKey publicKey = packet.getPublicKey();
            Cipher decryptCipher = Crypt.getCipher(2, secretKey);
            Cipher encryptCipher = Crypt.getCipher(1, secretKey);
            ServerboundKeyPacket keyPacket = new ServerboundKeyPacket(secretKey, publicKey, packet.getChallenge());

            ci.cancel();
            Connection conn = tauth$getConnection(this);
            conn.send(keyPacket, PacketSendListener.thenRun(() -> conn.setEncryptionKey(decryptCipher, encryptCipher)));
        } catch (Exception ignored) {
            // fall through to vanilla handling
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

    @Unique
    private static Connection tauth$getConnection(Object self) throws ReflectiveOperationException {
        java.lang.reflect.Field field = ClientHandshakePacketListenerImpl.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (Connection) field.get(self);
    }
}
