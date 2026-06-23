package top.tdrgame.auth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.apache.commons.lang3.Validate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tdrgame.auth.config.AuthConfig;
import top.tdrgame.auth.server.PremiumLoginVerifier;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.UUID;

/** AuthMe-style premium bypass for offline-mode Forge servers. */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class PremiumLoginMixin {

    @Unique private byte[] tauth$premiumChallenge;
    @Unique private String tauth$premiumName;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void tauth$startPremiumVerification(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!AuthConfig.isEnabled()) {
            return;
        }
        Validate.validState(tauth$getState() == tauth$state("HELLO"), "Unexpected hello packet");
        Validate.validState(ServerLoginPacketListenerImpl.isValidUsername(packet.name()), "Invalid characters in username");

        MinecraftServer server = tauth$getServer();
        Connection connection = tauth$getConnection();
        if (!PremiumLoginVerifier.INSTANCE.shouldVerify(server, connection, packet.name())) {
            return;
        }

        tauth$setGameProfile(new GameProfile((UUID) null, packet.name()));
        this.tauth$premiumName = packet.name();
        this.tauth$premiumChallenge = PremiumLoginVerifier.INSTANCE.newChallenge();
        tauth$setState(tauth$state("KEY"));
        connection.send(new ClientboundHelloPacket("", server.getKeyPair().getPublic().getEncoded(), this.tauth$premiumChallenge));
        ci.cancel();
    }

    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void tauth$finishPremiumVerification(ServerboundKeyPacket packet, CallbackInfo ci) {
        if (this.tauth$premiumChallenge == null || this.tauth$premiumName == null) {
            return;
        }
        Validate.validState(tauth$getState() == tauth$state("KEY"), "Unexpected key packet");
        ci.cancel();

        final MinecraftServer server = tauth$getServer();
        final Connection connection = tauth$getConnection();
        final String name = this.tauth$premiumName;
        final byte[] challenge = this.tauth$premiumChallenge;
        this.tauth$premiumChallenge = null;
        this.tauth$premiumName = null;
        tauth$setState(tauth$state("AUTHENTICATING"));

        // Step 1 - sync: decrypt shared secret, validate token, get AES ciphers
        PremiumLoginVerifier.EncryptionContext enc = PremiumLoginVerifier.INSTANCE.prepareEncryption(server, packet, challenge);
        if (enc == null) {
            tauth$disconnect(Component.literal("Token validation failed during premium verification."));
            return;
        }

        // Step 2 - sync: install AES encryption immediately (client is already in AES mode)
        connection.setEncryptionKey(enc.getDecryptCipher(), enc.getEncryptCipher());

        // Step 3 - async: hasJoined Mojang verification
        final byte[] sharedSecret = enc.getSharedSecret();
        PremiumLoginVerifier.INSTANCE.verifySessionAsync(server, connection, name, sharedSecret)
            .whenComplete((result, throwable) -> {
                // Runs on worker thread, just like vanilla handleKey's spawned thread.
                // verifiedSessions is ConcurrentHashMap — thread-safe.
                if (throwable != null || result == null) {
                    tauth$setState(tauth$state("NEGOTIATING"));
                    return;
                }
                PremiumLoginVerifier.INSTANCE.storeVerified(name, result.getProfile().getId(), result.getTextures());
                tauth$setState(tauth$state("NEGOTIATING"));
            });
    }

    /** Inject textures into gameProfile BEFORE placeNewPlayer sends initial player info. */
    @Inject(method = "handleAcceptedLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void tauth$injectTexturesBeforeBroadcast(CallbackInfo ci) {
        if (this.tauth$premiumName != null) return;
        GameProfile profile = tauth$getGameProfile();
        if (profile == null) return;
        Collection<com.mojang.authlib.properties.Property> textures = PremiumLoginVerifier.INSTANCE.consumeTextures(profile.getName());
        if (textures != null && !textures.isEmpty()) {
            profile.getProperties().removeAll("textures");
            for (com.mojang.authlib.properties.Property tex : textures) {
                profile.getProperties().put("textures", tex);
            }
        }
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object tauth$state(String name) {
        return Enum.valueOf((Class<Enum>) (Class) tauth$stateClass(), name);
    }

    @Unique
    private static Class<?> tauth$stateClass() {
        for (Class<?> nested : ServerLoginPacketListenerImpl.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("State")) {
                return nested;
            }
        }
        throw new IllegalStateException("ServerLoginPacketListenerImpl.State not found");
    }

    @Unique
    private MinecraftServer tauth$getServer() {
        return (MinecraftServer) tauth$getField("server", "f_10018_");
    }

    @Unique
    private Connection tauth$getConnection() {
        return (Connection) tauth$getField("connection", "f_10013_");
    }

    @Unique
    private Object tauth$getState() {
        return tauth$getField("state", "f_10019_");
    }

    @Unique
    private void tauth$setState(Object state) {
        tauth$setField(state, "state", "f_10019_");
    }

    @Unique
    private GameProfile tauth$getGameProfile() {
        return (GameProfile) tauth$getField("gameProfile", "f_10021_");
    }

    @Unique
    private void tauth$setGameProfile(GameProfile profile) {
        tauth$setField(profile, "gameProfile", "f_10021_");
    }

    @Unique
    private Object tauth$getField(String deobfName, String srgName) {
        try {
            return tauth$field(deobfName, srgName).get(this);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read login field " + deobfName, exception);
        }
    }

    @Unique
    private void tauth$setField(Object value, String deobfName, String srgName) {
        try {
            tauth$field(deobfName, srgName).set(this, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot write login field " + deobfName, exception);
        }
    }

    @Unique
    private void tauth$disconnect(Component reason) {
        tauth$getConnection().send(new ClientboundLoginDisconnectPacket(reason));
        tauth$getConnection().disconnect(reason);
    }

    @Unique
    private static Field tauth$field(String deobfName, String srgName) throws NoSuchFieldException {
        Class<ServerLoginPacketListenerImpl> type = ServerLoginPacketListenerImpl.class;
        try {
            Field field = type.getDeclaredField(deobfName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ignored) {
            Field field = type.getDeclaredField(srgName);
            field.setAccessible(true);
            return field;
        }
    }
}
