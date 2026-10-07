package app.tellev.ui.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellev.R
import app.tellev.feature.creation.CharacterCompletion
import app.tellev.feature.creation.CharacterDraft
import coil.compose.AsyncImage
import java.io.File

/**
 * 角色卡预览卡（PRD §前端 UI 要求 5）：头像 / 名称 / 简介 / 标签 / 性格关键词 /
 * 开场白 / 创建状态 / 是否可用于聊天。dsh 聊天皮肤病，折叠态一行摘要，
 * 展开给完整字段 + 完成度明细。空草稿渲染空态卡片而不是空白。
 */
@Composable
internal fun DshCharacterCardPreview(
    card: CharacterDraft,
    coverFile: File?,
    modifier: Modifier = Modifier,
) {
    val report = remember(card) { CharacterCompletion.evaluate(card) }
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Dsh.RADIUS_LG.dp),
        color = Dsh.menuSurface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 头像：封面 PNG 或首字母回退。
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                        .background(Dsh.hover),
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverFile != null) {
                        AsyncImage(
                            model = coverFile,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(44.dp),
                        )
                    } else {
                        Text(
                            card.name.trim().take(1).ifBlank { "?" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Dsh.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.name.ifBlank { stringResource(R.string.dsh_card_unnamed) },
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = Dsh.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (report.blockers.isEmpty()) {
                            stringResource(R.string.dsh_card_ready)
                        } else {
                            stringResource(R.string.dsh_card_draft, report.blockers.joinToString("、"))
                        },
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (report.blockers.isEmpty()) Dsh.statusGreen else Dsh.statusAmber,
                    )
                }
                // 完成度圆环数字。
                Text(
                    "${report.score}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (report.score >= 80) Dsh.statusGreen else if (report.score >= 40) Dsh.statusAmber else Dsh.textTertiary,
                )
            }
            if (card.description.isNotBlank()) {
                Text(
                    card.description.trim().take(120),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Dsh.textSecondary,
                    maxLines = if (expanded) 8 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 标签 chips。
            if (card.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    card.tags.take(4).forEach { tag ->
                        Text(
                            tag,
                            fontSize = 10.sp,
                            color = Dsh.blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Dsh.blue.copy(alpha = 0.1f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            // 展开：完整字段摘要 + 缺失项 + 建议。
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CardFieldRow(stringResource(R.string.dsh_card_field_personality), card.personality)
                    CardFieldRow(stringResource(R.string.dsh_card_field_scenario), card.scenario)
                    CardFieldRow(stringResource(R.string.dsh_card_field_first_message), card.firstMessage)
                    CardFieldRow(stringResource(R.string.dsh_card_field_examples), card.exampleMessages)
                    if (report.missing.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Dsh.statusAmber,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.dsh_card_missing, report.missing.joinToString("、")),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Dsh.textTertiary,
                            )
                        }
                    }
                    report.suggestions.forEach { suggestion ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Dsh.blue,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                suggestion,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Dsh.textSecondary,
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(if (expanded) R.string.dsh_card_collapse else R.string.dsh_card_expand),
                fontSize = 11.sp,
                color = Dsh.blue,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { expanded = !expanded }
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun CardFieldRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 11.sp,
            color = Dsh.textTertiary,
            modifier = Modifier.width(56.dp),
        )
        Text(
            value.trim().take(160),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
