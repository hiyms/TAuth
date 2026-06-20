package top.tdrgame.auth.server

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import java.io.File

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

    /** 保存物品栏 NBT。 */
    fun save(playerName: String, tag: net.minecraft.nbt.ListTag) {
        dir.mkdirs()
        val file = fileFor(playerName)
        val root = CompoundTag()
        root.put("Inventory", tag)
        NbtIo.writeCompressed(root, file.outputStream())
    }

    /** 读取并移除物品栏 NBT；读取后即删除备份文件。 */
    fun loadAndConsume(playerName: String): net.minecraft.nbt.ListTag? {
        val file = fileFor(playerName)
        if (!file.exists()) return null
        return try {
            val root = NbtIo.readCompressed(file.inputStream())
            val tag = root.getList("Inventory", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())
            file.delete()
            tag
        } catch (_: Throwable) {
            null
        }
    }

    /** 丢弃某玩家的备份（例如其下线时仍未认证）。 */
    fun discard(playerName: String) {
        fileFor(playerName).delete()
    }

    private fun fileFor(playerName: String): File =
        File(dir, "${playerName.lowercase()}.nbt")
}
