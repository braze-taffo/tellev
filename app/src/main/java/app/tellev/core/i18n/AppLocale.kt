package app.tellev.core.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList

/**
 * 应用内界面语言。选择以 BCP-47 标签存在 SharedPreferences 里（空串 = 跟随系统），
 * 通过包装 Application/Activity 的 baseContext 生效，因此所有经
 * Context/LocalContext 解析的资源（含 Compose 的 stringResource）都会遵循所选语言。
 *
 * 语言切换由设置页写入偏好后 recreate() 当前 Activity 完成视觉刷新；Application 的
 * baseContext 在进程重启后同样会带上新语言，非 Compose 侧的 getString 也能解析正确。
 */
object AppLocale {
    /** 跟随系统语言。 */
    const val SYSTEM = ""

    /** 语言选项列表（BCP-47 标签 → 该语言自书写的名称，按惯例不翻译）。 */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "zh-CN" to "简体中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
    )

    /** 把给定 context 包装成当前所选语言的等价 context；跟随系统时原样返回。 */
    fun wrap(base: Context): Context {
        val tag = storedTag(base)
        if (tag == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    /**
     * 直接读传入的 context。不能用 `base.applicationContext`：本方法在
     * Application.attachBaseContext 阶段就会被调用，而 LoadedApk 是在
     * `instrumentation.newApplication()`（即 attachBaseContext 执行点）返回之后才给
     * mApplication 赋值，此时 getApplicationContext() 返回 null，取它的
     * getSharedPreferences 会直接 NPE。
     * base 与 applicationContext 指向同一个包，shared_prefs 文件相同。
     */
    private fun storedTag(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_LANGUAGE_TAG, SYSTEM) ?: SYSTEM

    /** 与 AppPreferences 的默认 SharedPreferences 保持一致，避免 attach 阶段引入依赖。 */
    private const val PREFS_NAME = "tellev_prefs"
    private const val PREF_KEY_LANGUAGE_TAG = "language_tag"
}
