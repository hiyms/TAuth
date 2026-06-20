package top.tdrgame.auth.client

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.io.File
import java.util.*

/**
 * 客户端自动登录管理器。
 *
 * 首次登录成功后缓存 PBKDF2 结果、机器 ID 和 IP，
 * 下次进入时自动走挑战-响应流程，无需手动输入密码。
 *
 * 缓存文件：config/tauth/autologin.nbt
 * 缓存内容不包含明文密码。
 */
@OnlyIn(Dist.CLIENT)
object AutoLoginManager {

    private val file = File("config/tauth/autologin.nbt")

    data class AutoLoginCache(
        val machineId: UUID,
        val saltedHash: ByteArray,
        val lastIp: String
    )

    private fun generateMachineId(): UUID = UUID.randomUUID()

    fun save(saltedHash: ByteArray, lastIp: String) {
        file.parentFile?.mkdirs()
        val tag = CompoundTag().apply {
            val existing = load()
            putUUID("machineId", existing?.machineId ?: generateMachineId())
            putByteArray("saltedHash", saltedHash)
            putString("lastIp", lastIp)
        }
        NbtIo.writeCompressed(tag, file.outputStream())
    }

    fun load(): AutoLoginCache? {
        if (!file.exists()) return null
        return try {
            val tag = NbtIo.readCompressed(file.inputStream())
            AutoLoginCache(
                machineId = tag.getUUID("machineId"),
                saltedHash = tag.getByteArray("saltedHash"),
                lastIp = tag.getString("lastIp")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear() { file.delete() }
}
