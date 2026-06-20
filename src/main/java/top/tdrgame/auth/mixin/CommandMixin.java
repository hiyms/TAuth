package top.tdrgame.auth.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tdrgame.auth.server.AuthManager;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 拦截未认证玩家的命令执行。
 * 未认证玩家仅允许白名单中的命令（/login, /register, /help 等）。
 */
@Mixin(Commands.class)
public class CommandMixin {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "login", "l", "register", "reg", "help", "?", "resetpasswd"
    );

    /**
     * 在命令执行前检查认证状态。使用 Redirect 避免破坏 Forge 命令链。
     */
    @Redirect(
        method = "performCommand",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;execute(Lcom/mojang/brigadier/ParseResults;)I"
        )
    )
    private int onExecuteCommand(
        CommandDispatcher<CommandSourceStack> dispatcher,
        ParseResults<CommandSourceStack> parse
    ) {
        CommandSourceStack source = parse.getContext().getSource();
        ServerPlayer player = source.getPlayer();

        if (player != null && !AuthManager.INSTANCE.isAuthenticated(player)) {
            String commandName = parse.getReader().getString()
                .replaceFirst("^/", "")
                .split(" ")[0];

            if (!ALLOWED_COMMANDS.contains(commandName.toLowerCase(Locale.ROOT))) {
                player.sendSystemMessage(Component.literal(
                    "§c未登录禁止使用此命令！请先 /login 登录或 /register 注册。"));
                return 0;
            }
        }

        try {
            return dispatcher.execute(parse);
        } catch (CommandSyntaxException e) {
            // 命令语法错误，静默处理（原始行为）
            return 0;
        }
    }
}
