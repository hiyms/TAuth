package top.tdrgame.auth.server

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.ServerChatEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.item.ItemTossEvent
import net.minecraftforge.event.entity.player.*
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import top.tdrgame.auth.config.AuthConfig

/**
 * 事件拦截层：限制未认证玩家的所有敏感行为。
 *
 * 每个 handler 先检查 auth.enabled 和认证状态，
 * 不满足条件则取消事件并提示。
 */
@Mod.EventBusSubscriber
object EventHandler {

    /**
     * 背包备份标记。Key 为玩家名，表示该玩家当前正处于「未认证 + 已清空背包」状态。
     * 实际数据落盘在 [InventoryBackupStore]，内存集合仅用于快速判断与登出清理。
     */
    private val inventoryBackups = mutableSetOf<String>()

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return

        val name = player.name.string
        val isPremium = TrueUuidBridge.isPremium(name)
        val storage = TAuthHolder.storage
        val data = storage.get(name)
        val isVerified = data?.verified == true
        val isRegistered = data != null

        // 死亡后直接退出服务器且未重生就再次进入：先自动重生到出生点，再要求登录。
        // respawnPosition 可能为 null（无床），回退到世界共享出生点。
        if (player.isDeadOrDying) {
            val spawnPos = player.respawnPosition ?: player.serverLevel().sharedSpawnPos
            player.teleportTo(spawnPos.x.toDouble(), spawnPos.y.toDouble(), spawnPos.z.toDouble())
            player.health = player.maxHealth
        }

        AuthManager.onPlayerJoin(player, isPremium, isVerified, isRegistered)

        // 未认证 → 备份并清空背包（落盘，防止服务端崩溃导致丢背包）
        if (!AuthManager.isAuthenticated(player)) {
            val backup = player.inventory.save(net.minecraft.nbt.ListTag())
            InventoryBackupStore.save(name, backup)
            inventoryBackups.add(name)
            player.inventory.clearContent()
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        // 下线时若仍未认证，把背包直接还原回玩家对象并非必须；
        // 这里清理内存标记，磁盘备份保留以备其下次上线能立即恢复（认证通过后）。
        inventoryBackups.remove(player.name.string)
        AuthManager.onPlayerLeave(player)
    }

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (!AuthConfig.enabled.get()) return
        if (event.phase != TickEvent.Phase.END) return

        AuthManager.tick()
        AuthManager.collectKicks().forEach { (name, reason) ->
            val player = event.server.playerList.getPlayerByName(name) ?: return@forEach
            player.connection.disconnect(Component.literal(reason))
        }
    }

    @SubscribeEvent @JvmStatic
    fun onChat(event: ServerChatEvent) {
        if (!AuthConfig.enabled.get()) return
        if (!AuthManager.isAuthenticated(event.player)) {
            event.isCanceled = true
            event.player.sendSystemMessage(Component.literal("§c未登录禁止发言！"))
        }
    }

    @SubscribeEvent @JvmStatic
    fun onBlockBreak(event: BlockEvent.BreakEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.player as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
            player.sendSystemMessage(Component.literal("§c未登录禁止破坏方块！"))
        }
    }

    @SubscribeEvent @JvmStatic
    fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
            player.sendSystemMessage(Component.literal("§c未登录禁止放置方块！"))
        }
    }

    @SubscribeEvent @JvmStatic
    fun onItemToss(event: ItemTossEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.player as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
            player.sendSystemMessage(Component.literal("§c未登录禁止丢弃物品！"))
        }
    }

    @SubscribeEvent @JvmStatic
    fun onItemPickup(event: EntityItemPickupEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent @JvmStatic
    fun onInteract(event: PlayerInteractEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
            player.sendSystemMessage(Component.literal("§c未登录禁止交互！"))
        }
    }

    @SubscribeEvent @JvmStatic
    fun onContainerOpen(event: PlayerContainerEvent.Open) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            player.closeContainer()
        }
    }

    /** 认证通过后恢复玩家背包（从磁盘备份读取）。 */
    fun restoreInventory(player: ServerPlayer) {
        val name = player.name.string
        inventoryBackups.remove(name)
        val backup = InventoryBackupStore.loadAndConsume(name) ?: return
        player.inventory.load(backup)
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()
        player.sendSystemMessage(Component.literal("§a背包已恢复。"))
    }
}

/** PasswordStorage 单例桥，在 TAuth 初始化时设置。 */
object TAuthHolder {
    lateinit var storage: PasswordStorage
}
