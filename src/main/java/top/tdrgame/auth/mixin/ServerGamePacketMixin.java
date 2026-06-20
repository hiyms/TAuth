package top.tdrgame.auth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tdrgame.auth.config.AuthConfig;
import top.tdrgame.auth.server.AuthManager;

/**
 * 冻结未认证玩家的移动。
 *
 * 未认证玩家进入服务器时其位置即为「锚点」。在认证通过前，任何
 * {@link ServerboundMovePlayerPacket}（移动/转向）都在处理前被取消，
 * 等价于完全禁止移动，避免 nedologin 式 100ms 回弹的视觉抖动。
 *
 * 仅在 auth.enabled 开启时生效；已认证玩家不受影响，零开销。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketMixin {

    @Inject(
        method = "handleMovePlayer",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tauth$freezeIfUnauthenticated(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!AuthConfig.isEnabled()) {
            return;
        }
        // ServerLifecycleHooks.currentServer 为 null 表示尚未完成启动；此时放行避免误伤。
        if (ServerLifecycleHooks.getCurrentServer() == null) {
            return;
        }

        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).getPlayer();
        if (player == null) {
            return;
        }
        if (!AuthManager.INSTANCE.isAuthenticated(player)) {
            // 仅当玩家试图改变位置或朝向时取消。纯 onGround 心跳包保留，
            // 以维持连接活性。
            if (packet.hasPosition() || packet.hasRotation()) {
                ci.cancel();
            }
        }
    }
}
