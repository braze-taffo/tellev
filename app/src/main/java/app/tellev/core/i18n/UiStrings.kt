package app.tellev.core.i18n

import android.content.res.Resources
import java.util.Locale

/**
 * 非 Composable 代码（ViewModel、引擎、协调器等纯类）解析界面文案的统一入口。
 *
 * 生产环境：[init] 在 Application/Activity 的 attachBaseContext 里以（已按所选语言
 * 包装的）Resources 调用，[get] 等价于 `resources.getString(id, args)`；
 * 语言切换后 Activity 重建会重新 init，新消息即刻使用新语言。
 *
 * JVM 单元测试环境：没有 Resources，[get] 回退到 [S.fallbackZh] 的中文映射，
 * 因此既有断言中文消息的测试无需改动。
 *
 * key 一律用 [S] 里生成的字符串常量名（与资源 name 同名），避免手写裸字符串。
 */
object UiStrings {
    @Volatile private var resources: Resources? = null

    fun init(res: Resources) {
        resources = res
    }

    fun get(name: String, vararg args: Any?): String {
        val res = resources
        if (res != null) {
            val id = S.idByName[name]
            // 无参数时必须走 getString(id)：带参重载无条件 String.format，
            // 而含字面 % 的条目（%model%、<%= %> 这类 formatted="false"）会被当成
            // 格式转换符，抛 UnknownFormatConversionException。
            if (id != null) {
                return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
            }
        }
        val zh = S.fallbackZh[name] ?: return "[missing:$name]"
        return if (args.isEmpty()) zh else String.format(Locale.getDefault(), zh, *args)
    }
}
