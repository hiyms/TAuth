package top.tdrgame.auth.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.logging.log4j.LogManager
import java.io.File

/**
 * 从旧认证模组（offlineauth、TrueUUID）迁移数据到 Nitrite。
 */
object MigrationService {

    private val logger = LogManager.getLogger("TAuth/Migration")
    private const val MARKER_FILE = "config/tauth/.migrated"
    private val gson = Gson()

    fun runIfNeeded(storage: PasswordStorage) {
        if (File(MARKER_FILE).exists()) {
            logger.info("Migration marker found, skipping.")
            return
        }
        if (!storage.isEmpty()) {
            logger.info("Nitrite database not empty, assuming already migrated.")
            File(MARKER_FILE).createNewFile()
            return
        }

        var migrated = false
        migrated = migrated or migrateOfflineauth(storage)
        migrated = migrated or migrateTrueUUID(storage)

        if (migrated) {
            File(MARKER_FILE).createNewFile()
            logger.info("Migration completed.")
        } else {
            logger.info("No legacy data found to migrate.")
        }
    }

    private fun migrateOfflineauth(storage: PasswordStorage): Boolean {
        val oldFile = File("config/offlineauth/auth_hash.json")
        if (!oldFile.exists()) return false

        try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String>? = gson.fromJson(oldFile.reader(), type)
            if (map.isNullOrEmpty()) return false

            map.forEach { (name, hash) ->
                storage.insertRaw(PlayerAuthData(playerName = name, passwordHash = hash, verified = false))
            }

            oldFile.renameTo(File("config/offlineauth/auth_hash.json.migrated"))
            logger.info("Migrated ${map.size} entries from offlineauth.")
            return true
        } catch (e: Exception) {
            logger.error("Failed to migrate offlineauth data", e)
            return false
        }
    }

    /**
     * 从 TrueUUID 迁移「已验证为正版」的标记。
     *
     * TrueUUID 把已知正版玩家名持久化在 [config/trueuuid-registry.json]，
     * 结构为 `Map<小写玩家名, {premiumUuid, firstVerifiedAt, ...}>`。
     * 该模组并未提供 `getKnownPremiumNames()` 之类的公共 API，因此直接读取 JSON。
     *
     * 仅对已存在于 TAuth 数据库的玩家回填 verified=true；不会凭空创建密码条目。
     */
    private fun migrateTrueUUID(storage: PasswordStorage): Boolean {
        val registryFile = File("config/trueuuid-registry.json")
        if (!registryFile.exists()) return false

        return try {
            val root = com.google.gson.JsonParser.parseReader(registryFile.reader()).asJsonObject
            if (root.size() == 0) return false

            var count = 0
            root.keySet().forEach { name ->
                // 仅当条目确实记录了 premiumUuid 才视为正版
                val entry = root.getAsJsonObject(name)
                if (entry.has("premiumUuid")) {
                    val existing = storage.get(name)
                    if (existing != null) {
                        storage.updateVerification(name, verified = true, loginType = "online")
                        count++
                    }
                }
            }

            logger.info("Migrated $count TrueUUID verified flags.")
            count > 0
        } catch (e: Exception) {
            logger.error("Failed to migrate TrueUUID data", e)
            false
        }
    }
}
