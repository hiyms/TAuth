package top.tdrgame.auth.server

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.ServerChatEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.item.ItemTossEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.entity.player.*
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import top.tdrgame.auth.TAuth
import top.tdrgame.auth.config.AuthConfig
import top.tdrgame.auth.i18n.ServerI18n
import top.tdrgame.auth.network.AuthPackets
import top.tdrgame.auth.server.PremiumLoginVerifier

/**
 * 事件拦截层：限制未认证玩家的所有敏感行为。
 *
 * 每个 handler 先检查 auth.enabled 和认证状态，
 * 不满足条件则取消事件并提示。
 */
object EventHandler {

    /**
     * 背包备份标记。Key 为玩家名，表示该玩家当前正处于「未认证 + 已清空背包」状态。
     * 实际数据落盘在 [InventoryBackupStore]，内存集合仅用于快速判断与登出清理。
     */
    private val inventoryBackups = mutableSetOf<String>()

    @SubscribeEvent
    @JvmStatic
    fun onServerStarting(event: ServerStartingEvent) {
        if (!AuthConfig.enabled.get()) {
            TAuth.LOGGER.info("Authentication is disabled; skipping all server-side auth enforcement.")
            return
        }
        TAuth.LOGGER.info("Authentication is enabled; enabling auth enforcement.")
    }

    @SubscribeEvent
    @JvmStatic
    fun onServerStopping(event: ServerStoppingEvent) {
        TAuthHolder.storage.close()
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val name = player.name.string
        if (!AuthConfig.enabled.get()) {
            TAuth.LOGGER.info("Auth disabled: allowing player {} without authentication checks.", name)
            return
        }

        val isPremium = AuthManager.isPremiumSession(player)
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

        TAuth.LOGGER.info("Auth enabled: player {} joined. premium={}, registered={}, verified={}",
            name, isPremium, isRegistered, isVerified)
        AuthManager.onPlayerJoin(player, isPremium, isVerified, isRegistered)

        // Apply Mojang skin/textures if available
        val textures = PremiumLoginVerifier.consumeTextures(name)
        if (textures != null && textures.isNotEmpty()) {
            val profile = player.gameProfile
            profile.properties.removeAll("textures")
            textures.forEach { profile.properties.put("textures", it) }
            TAuth.LOGGER.info("Applied Mojang textures for {}", name)
            // Refresh player list to show updated skin
            player.server.playerList.sendAllPlayerInfo(player)
        }

        if (!AuthManager.isAuthenticated(player)) {
            TAuth.LOGGER.info("Auth enforcement active for player {}; hiding inventory and waiting for login/register.", name)
            hideInventoryForAuth(player)
            AuthPackets.sendToPlayerIfPresent(player, AuthPackets.StartAuthPacket())
        } else {
            TAuth.LOGGER.info("Player {} is already authenticated by policy; no login prompt required.", name)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        // 下线前恢复已清空的背包和原始状态，避免服务端把空背包/Adventure 状态保存进 playerdata。
        restoreInventory(player, notify = false)
        CommandHandler.clearPendingChallenge(player.name.string)
        AuthManager.onPlayerLeave(player)
        inventoryBackups.remove(player.name.string)
    }

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (!AuthConfig.enabled.get()) return
        if (event.phase != TickEvent.Phase.END) return

        AuthManager.tick()
        event.server.playerList.players.forEach { player ->
            AuthManager.enforcePendingRestriction(player)
        }
        AuthManager.collectKicks().forEach { (name, reason) ->
            val player = event.server.playerList.getPlayerByName(name) ?: return@forEach
            restoreInventory(player, notify = false)
            CommandHandler.clearPendingChallenge(name)
            AuthManager.restoreOriginalState(player)
            player.connection.disconnect(ServerI18n.text(reason))
        }
    }

    @SubscribeEvent @JvmStatic
    fun onChat(event: ServerChatEvent) {
        if (!AuthConfig.enabled.get()) return
        if (!AuthManager.isAuthenticated(event.player)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent @JvmStatic
    fun onBlockBreak(event: BlockEvent.BreakEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.player as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent @JvmStatic
    fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent @JvmStatic
    fun onItemToss(event: ItemTossEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.player as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
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
        }
    }

    @SubscribeEvent @JvmStatic
    fun onAttackEntity(event: AttackEntityEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
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

    @SubscribeEvent @JvmStatic
    fun onHurt(event: LivingHurtEvent) {
        if (!AuthConfig.enabled.get()) return
        val attacker = event.source.entity as? ServerPlayer
        if (attacker != null && !AuthManager.isAuthenticated(attacker)) {
            event.isCanceled = true
            return
        }
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
            player.health = player.maxHealth
        }
    }

    @SubscribeEvent @JvmStatic
    fun onDeath(event: LivingDeathEvent) {
        if (!AuthConfig.enabled.get()) return
        val player = event.entity as? ServerPlayer ?: return
        if (!AuthManager.isAuthenticated(player)) {
            event.isCanceled = true
            player.health = player.maxHealth
        }
    }

    /** 进入未认证状态时隐藏玩家背包。 */
    fun hideInventoryForAuth(player: ServerPlayer) {
        val name = player.name.string
        val backup = player.inventory.save(net.minecraft.nbt.ListTag())
        InventoryBackupStore.saveIfAbsent(name, backup)
        inventoryBackups.add(name)
        player.inventory.clearContent()
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()
    }

    /** 认证通过后恢复玩家背包（从磁盘备份读取）。 */
    fun restoreInventory(player: ServerPlayer) {
        restoreInventory(player, notify = true)
    }

    fun restoreInventory(player: ServerPlayer, @Suppress("UNUSED_PARAMETER") notify: Boolean) {
        val name = player.name.string
        inventoryBackups.remove(name)
        val backup = InventoryBackupStore.load(name) ?: return
        player.inventory.load(backup)
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()
        InventoryBackupStore.consume(name)
    }
}

/** PasswordStorage 单例桥，在 TAuth 初始化时设置。 */
object TAuthHolder {
    lateinit var storage: PasswordStorage
}
