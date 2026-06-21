package top.tdrgame.auth.client

import net.minecraft.client.resources.language.I18n
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

@OnlyIn(Dist.CLIENT)
object ClientI18n {
    fun text(key: String, vararg args: Any): String = I18n.get(key, *args)
}
