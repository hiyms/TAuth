package top.tdrgame.auth.server

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 玩家物品栏备份的持久化存储。
 *
 * 未认证玩家的物品栏会被清空（避免被看到/操作），认证通过后再恢复。
 * 仅存内存的备份在服务端崩溃时会丢失，因此落盘到 NBT 文件。
 *
 * 文件路径：config/tauth/backups/<playername>.nbt
 * 内容为玩家主物品栏（含盔甲栏）序列化后的 ListTag，包裹在 CompoundTag 中。
 */
object InventoryBackupStore {

    private val dir = File("config/tauth/backups")

    /** 保存物品栏 NBT；仅当当前不存在备份时写入，避免空背包覆盖真实备份。 */
    fun saveIfAbsent(playerName: String, tag: net.minecraft.nbt.ListTag): Boolean {
        dir.mkdirs()
        val file = fileFor(playerName)
        if (file.exists()) return false
        writeAtomically(file, tag)
        return true
    }

    /** 兼容旧调用：不覆盖已有备份。 */
    fun save(playerName: String, tag: net.minecraft.nbt.ListTag) {
        saveIfAbsent(playerName, tag)
    }

    /** 读取物品栏 NBT；不删除备份，调用方成功恢复后应调用 [consume]。 */
    fun load(playerName: String): net.minecraft.nbt.ListTag? {
        val file = fileFor(playerName)
        if (!file.exists()) return null
        return try {
            file.inputStream().use { input ->
                val root = NbtIo.readCompressed(input)
                root.getList("Inventory", Tag.TAG_COMPOUND.toInt())
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 读取并移除物品栏 NBT；保留给旧调用，优先使用 [load] + [consume]。 */
    fun loadAndConsume(playerName: String): net.minecraft.nbt.ListTag? {
        val tag = load(playerName) ?: return null
        consume(playerName)
        return tag
    }

    /** 删除某玩家的备份（仅在成功恢复/明确丢弃后调用）。 */
    fun consume(playerName: String) {
        fileFor(playerName).delete()
    }

    /** 丢弃某玩家的备份（例如管理员确认无需恢复）。 */
    fun discard(playerName: String) {
        consume(playerName)
    }

    fun exists(playerName: String): Boolean = fileFor(playerName).exists()

    private fun writeAtomically(file: File, tag: net.minecraft.nbt.ListTag) {
        val root = CompoundTag()
        root.put("Inventory", tag)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { output -> NbtIo.writeCompressed(root, output) }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileFor(playerName: String): File =
        File(dir, "${playerName.lowercase()}.nbt")
}
