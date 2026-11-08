package com.ansylab.vision

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 界面语言切换。
 *
 * 应用里只有中/英两种；用户选过的语言存在 SharedPreferences 里，
 * Activity 启动时用 createConfigurationContext 包一层，让资源按语言加载。
 */
object LocaleHelper {

    const val ZH = "zh"
    const val EN = "en"

    fun current(ctx: Context): String = Storage.language(ctx)

    fun label(ctx: Context): String =
        if (current(ctx) == EN) ctx.getString(R.string.language_en)
        else ctx.getString(R.string.language_zh)

    /** 用当前语言包一层 Context（Activity.attachBaseContext 里用）。 */
    fun wrap(base: Context): Context {
        val tag = try {
            Storage.language(base)
        } catch (t: Throwable) {
            ZH
        }
        val locale = if (tag == EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
