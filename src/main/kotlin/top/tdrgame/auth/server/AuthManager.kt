package top.tdrgame.auth.server

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import top.tdrgame.auth.state.AuthStateMachine
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局认证管理器。
 *
 * 使用 [ConcurrentHashMap] 确保线程安全。
 */
object AuthManager {

    private data class RestrictionSnapshot(
        val gameMode: GameType,
        val invulnerable: Boolean,
        val x: Double,
        val y: Double,
        val z: Double
    )

    private val pendingPlayers = ConcurrentHashMap<String, AuthStateMachine>()
    private val authenticatedPlayers = ConcurrentHashMap.newKeySet<String>()
    private val restrictionSnapshots = ConcurrentHashMap<String, RestrictionSnapshot>()

    fun onPlayerJoin(
        player: ServerPlayer,
        isPremium: Boolean,
        isVerified: Boolean,
        isRegistered: Boolean
    ) {
        val name = player.name.string
        val machine = AuthStateMachine(player, isPremium, isVerified, isRegistered)

        if (machine.isAuthenticated()) {
            authenticatedPlayers.add(name)
        } else {
            pendingPlayers[name] = machine
            restrictPlayer(player)
        }
    }

    fun onPlayerLeave(player: ServerPlayer) {
        val name = player.name.string
        restoreOriginalState(player)
        pendingPlayers.remove(name)
        authenticatedPlayers.remove(name)
    }

    fun tick() {
        pendingPlayers.values.forEach { it.tick() }
    }

    fun collectKicks(): List<Pair<String, String>> {
        val kicks = mutableListOf<Pair<String, String>>()
        val iter = pendingPlayers.iterator()
        while (iter.hasNext()) {
            val (name, machine) = iter.next()
            if (machine.shouldKick()) {
                kicks.add(name to machine.kickReason())
                iter.remove()
            }
        }
        return kicks
    }

    fun getStateMachine(player: ServerPlayer): AuthStateMachine? =
        pendingPlayers[player.name.string]

    fun isAuthenticated(player: ServerPlayer): Boolean =
        authenticatedPlayers.contains(player.name.string)

    fun markAuthenticated(player: ServerPlayer) {
        val name = player.name.string
        pendingPlayers.remove(name)
        authenticatedPlayers.add(name)
    }

    fun forcePending(player: ServerPlayer, isPremium: Boolean, isVerified: Boolean, isRegistered: Boolean) {
        val name = player.name.string
        authenticatedPlayers.remove(name)
        pendingPlayers[name] = AuthStateMachine(player, isPremium, isVerified, isRegistered)
        restrictPlayer(player)
    }

    fun isPremiumSession(player: ServerPlayer): Boolean = player.server.usesAuthentication()

    fun getPlayerIp(player: ServerPlayer): String {
        val remote = player.connection.remoteAddress ?: return "unknown"
        if (remote is InetSocketAddress) {
            return remote.address?.hostAddress ?: remote.hostString ?: "unknown"
        }
        val raw = remote.toString().removePrefix("/")
        val lastColon = raw.lastIndexOf(':')
        return if (lastColon > 0 && raw.indexOf(':') == lastColon) raw.substring(0, lastColon) else raw
    }

    fun restoreOriginalState(player: ServerPlayer) {
        val name = player.name.string
        val snapshot = restrictionSnapshots.remove(name) ?: return
        player.setInvulnerable(snapshot.invulnerable)
        player.setGameMode(snapshot.gameMode)
    }

    fun enforcePendingRestriction(player: ServerPlayer) {
        val name = player.name.string
        if (!pendingPlayers.containsKey(name)) return
        val snapshot = restrictionSnapshots[name] ?: return
        player.setInvulnerable(true)
        player.setGameMode(GameType.ADVENTURE)
        player.fallDistance = 0.0f
        player.connection.teleport(snapshot.x, snapshot.y, snapshot.z, player.yRot, player.xRot)
    }

    private fun restrictPlayer(player: ServerPlayer) {
        val name = player.name.string
        restrictionSnapshots.computeIfAbsent(name) {
            RestrictionSnapshot(
                gameMode = player.gameMode.gameModeForPlayer,
                invulnerable = player.isInvulnerable,
                x = player.x,
                y = player.y,
                z = player.z
            )
        }
        enforcePendingRestriction(player)
    }

    fun unrestrictPlayer(player: ServerPlayer) {
        restoreOriginalState(player)
    }
}
