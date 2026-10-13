package app.tellev.ui.dsh

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.provider.ModelReasoningProfile

/**
 * bre 档位阶梯：与 dsh-better-reasoning-effort 的 THINKING_LEVELS 同序
 * （`['off','minimal','low','medium','high','xhigh','max']`）。
 * Auto 不在此列——它是「不发字段」的透传，不是一档可声明的强度。
 */
internal val DSH_THINKING_LEVELS: List<ReasoningEffort> = listOf(
    ReasoningEffort.Off,
    ReasoningEffort.Minimal,
    ReasoningEffort.Low,
    ReasoningEffort.Medium,
    ReasoningEffort.High,
    ReasoningEffort.XHigh,
    ReasoningEffort.Max,
)

/** 草稿里一格的状态：是否勾选 + 线上拼写。 */
internal data class DraftLevel(
    val on: Boolean = false,
    val wire: String = "",
)

/**
 * 从已存声明构造草稿——bre `draftFrom` 的逐行移植。
 *
 *  - 无声明（null）：全部不勾选；保存即 unset；
 *  - `false` 声明（推理关闭）：只有 off 勾选；保存写 false；
 *  - 字典声明：wire 有值的档位勾选，值为 null 的档位拼写留空（= 线上不发字段）。
 */
internal fun profileDraftFrom(profile: ModelReasoningProfile?): Map<ReasoningEffort, DraftLevel> {
    val out = mutableMapOf<ReasoningEffort, DraftLevel>()
    if (profile == null) {
        DSH_THINKING_LEVELS.forEach { out[it] = DraftLevel() }
        return out
    }
    DSH_THINKING_LEVELS.forEach { level ->
        val key = level.name.lowercase()
        if (!profile.efforts.containsKey(key)) {
            out[level] = DraftLevel()
            return@forEach
        }
        val wire = profile.efforts[key]
        out[level] = DraftLevel(on = true, wire = wire ?: "")
    }
    return out
}

/** `false` 声明（reasoning 关闭）在档案里的表示：只有 off 一键。 */
internal val DISABLED_REASONING_DRAFT: Map<ReasoningEffort, DraftLevel> =
    DSH_THINKING_LEVELS.associateWith { level ->
        if (level == ReasoningEffort.Off) DraftLevel(on = true) else DraftLevel()
    }

/**
 * 草稿 → 要写的声明——bre `buildIntent` 的逐行移植。
 *
 *  - 没有任何档位勾选 → null（unset，让端点默认生效）；
 *  - 只勾了 off → [DISABLED]（推理关闭）；
 *  - 至少一个思考档位 → 档位 → 拼写的映射。
 *
 * off 的拼写有意义：空 = 该档不发任何字段；非空 = 线上拿来关闭思考的那个值。
 * 普通档位拼写留空时默认用自己的档位名（OpenAI 兼容端点的通行约定）。
 */
internal const val DISABLED = "__disabled__"

internal fun buildProfileIntent(
    draft: Map<ReasoningEffort, DraftLevel>,
): Pair<Map<String, String?>, String?>? {
    var off = false
    var thinking = false
    val out = mutableMapOf<String, String?>()
    DSH_THINKING_LEVELS.forEach { level ->
        val cell = draft[level] ?: return@forEach
        if (!cell.on) return@forEach
        if (level == ReasoningEffort.Off) {
            off = true
            val wire = cell.wire.trim()
            out[level.name.lowercase()] = wire.ifEmpty { null }
            return@forEach
        }
        thinking = true
        val wire = cell.wire.trim()
        out[level.name.lowercase()] = wire.ifEmpty { level.name.lowercase() }
    }
    return when {
        thinking -> out to null
        off -> emptyMap<String, String?>() to DISABLED
        else -> null
    }
}

/** 草稿与已存声明是否一致（bre sameEfforts 的语义等价）。 */
internal fun draftMatchesStored(
    draft: Map<ReasoningEffort, DraftLevel>,
    profile: ModelReasoningProfile?,
): Boolean {
    val built = buildProfileIntent(draft)
    if (built == null && profile == null) return true
    if (built == null || profile == null) return false
    val (efforts, disabled) = built
    if (disabled == DISABLED) {
        // 关闭声明：档案里应只有 off 一键。
        return profile.efforts.keys == setOf("off")
    }
    val storedKeys = profile.efforts.keys
    if (storedKeys != efforts.keys) return false
    return efforts.all { (k, v) -> profile.efforts[k] == v }
}

/**
 * bre EffortEditor 的 Compose 复刻：逐档「勾选 + 线上拼写」两列网格，
 * 加默认档位下拉、图片输入声明、兼容字段与参考容量展示。
 *
 * 与上游一致的关键语义：
 *  - 每个勾选的档位都带一个拼写输入框，off 也算——非空的 off 拼写正是某些
 *    协议用来关闭思考的值（OpenAI 的 `none`，或 deepseek/zai 的显式 disabled）；
 *  - 拼写留空的普通档位默认用自己的档位名；
 *  - 参考上下文/最大输出只作展示，绝不自动写进容量输入。
 */
