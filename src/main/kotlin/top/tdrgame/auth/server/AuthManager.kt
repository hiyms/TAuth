package top.tdrgame.auth.server

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import top.tdrgame.auth.state.AuthStateMachine
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局认证管理器。
 *
 * 使用 [ConcurrentHashMap] 确保线程安全。
 */
object AuthManager {

    private val pendingPlayers = ConcurrentHashMap<String, AuthStateMachine>()
    private val authenticatedPlayers = ConcurrentHashMap.newKeySet<String>()

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

    fun getPlayerIp(player: ServerPlayer): String {
        val raw = player.connection.remoteAddress?.toString() ?: return "unknown"
        val address = raw.removePrefix("/")
        val idx = address.indexOf(':')
        return if (idx > 0) address.substring(0, idx) else address
    }

    private fun restrictPlayer(player: ServerPlayer) {
        player.setInvulnerable(true)
        player.setGameMode(GameType.ADVENTURE)
    }

    fun unrestrictPlayer(player: ServerPlayer) {
        player.setInvulnerable(false)
        player.setGameMode(GameType.SURVIVAL)
    }
}
