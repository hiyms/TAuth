package top.tdrgame.auth.client

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import top.tdrgame.auth.config.AuthConfig
import java.io.File
import java.util.*

/**
 * 客户端自动登录管理器。
 *
 * 首次登录成功后缓存 PBKDF2 派生 key、机器 ID 和服务器地址，
 * 下次进入时自动走挑战-响应流程，无需手动输入密码。
 *
 * 缓存文件：config/tauth/autologin.nbt
 * 缓存内容不包含明文密码，但派生 key 可用于本服务器挑战，请视作敏感数据。
 */
@OnlyIn(Dist.CLIENT)
object AutoLoginManager {

    private val file = File("config/tauth/autologin.nbt")

    data class AutoLoginCache(
        val machineId: UUID,
        val derivedKey: ByteArray,
        val lastServer: String
    )

    private fun generateMachineId(): UUID = UUID.randomUUID()

    fun machineId(): UUID {
        val existing = load()
        if (existing != null) return existing.machineId
        val id = generateMachineId()
        saveRaw(id, ByteArray(0), "")
        return id
    }

    fun save(derivedKey: ByteArray, lastServer: String) {
        val existing = load()
        saveRaw(existing?.machineId ?: generateMachineId(), derivedKey, lastServer)
    }

    fun isAutoLoginEnabled(): Boolean = AuthConfig.autoLoginEnabled.get()

    fun applyLegacyConfigMigration() {
        val legacyEnabled = loadLegacyAutoLoginEnabled() ?: return
        val config = AuthConfig.autoLoginEnabled
        if (config.getDefault() == config.get()) {
            config.set(legacyEnabled)
            config.save()
        }
        removeLegacyAutoLoginEnabled()
    }

    private fun saveRaw(machineId: UUID, derivedKey: ByteArray, lastServer: String) {
        file.parentFile?.mkdirs()
        val tag = CompoundTag().apply {
            putUUID("machineId", machineId)
            putByteArray("derivedKey", derivedKey)
            putString("lastServer", lastServer)
        }
        file.outputStream().use { NbtIo.writeCompressed(tag, it) }
    }

    fun load(): AutoLoginCache? {
        val tag = loadTag() ?: return null
        return try {
            AutoLoginCache(
                machineId = tag.getUUID("machineId"),
                derivedKey = tag.getByteArray("derivedKey"),
                lastServer = tag.getString("lastServer")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun loadLegacyAutoLoginEnabled(): Boolean? = loadTag()
        ?.takeIf { it.contains("autoLoginEnabled") }
        ?.getBoolean("autoLoginEnabled")

    private fun removeLegacyAutoLoginEnabled() {
        val tag = loadTag() ?: return
        if (!tag.contains("autoLoginEnabled")) return
        tag.remove("autoLoginEnabled")
        file.outputStream().use { NbtIo.writeCompressed(tag, it) }
    }

    private fun loadTag(): CompoundTag? {
        if (!file.exists()) return null
        return try {
            file.inputStream().use { NbtIo.readCompressed(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() { file.delete() }
}
