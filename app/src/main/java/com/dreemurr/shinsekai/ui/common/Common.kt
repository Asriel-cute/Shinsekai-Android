package com.dreemurr.shinsekai.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.character.Character
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun spriteInfoText(character: Character): String =
    if (character.sprites.isEmpty()) {
        stringResource(R.string.character_no_sprites)
    } else {
        stringResource(
            R.string.character_info_format,
            character.sprites.size,
            character.sprites.count { it.voiceFile != null },
        )
    }

fun formatImportedAt(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

// CSS 颜色解析：# 开头交给系统 parseColor，其余试 rgb()/rgba()；无法识别返回 null，由调用方回落主题色。
fun parseCssColor(raw: String?): Color? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return try {
        if (value.startsWith("#")) Color(value.toColorInt()) else parseFunctionalColor(value)
    } catch (_: Exception) {
        null
    }
}

// 只认完整的 rgb(...) / rgba(...)，大小写不敏感。
private val FUNCTIONAL_COLOR_REGEX = Regex("(?i)rgba?\\(([^)]*)\\)")

// 分量分隔符：逗号、空白或 CSS Color 4 的「/」。
private val COLOR_COMPONENT_SEPARATOR = Regex("[,/\\s]+")

// 接受 3 或 4 个分量。
private fun parseFunctionalColor(value: String): Color? {
    val body = FUNCTIONAL_COLOR_REGEX.matchEntire(value)?.groupValues?.get(1) ?: return null
    val parts = body.split(COLOR_COMPONENT_SEPARATOR).filter { it.isNotBlank() }
    if (parts.size != 3 && parts.size != 4) return null
    val red = parseColorComponent(parts[0]) ?: return null
    val green = parseColorComponent(parts[1]) ?: return null
    val blue = parseColorComponent(parts[2]) ?: return null
    val alpha = if (parts.size == 4) parseAlphaComponent(parts[3]) ?: return null else 1f
    return Color(red / 255f, green / 255f, blue / 255f, alpha)
}

// r/g/b 分量：0-255 数值或百分比（50% → 128），越界钳制。
private fun parseColorComponent(text: String): Int? {
    val raw = text.trim()
    val value = if (raw.endsWith("%")) {
        val percent = raw.dropLast(1).trim().toFloatOrNull() ?: return null
        percent / 100f * 255f
    } else {
        raw.toFloatOrNull() ?: return null
    }
    return value.coerceIn(0f, 255f).roundToInt()
}

// alpha：0-1 浮点、百分比，或 >1 时按 0-255 处理（兼容非标准写法），钳制到 0-1。
private fun parseAlphaComponent(text: String): Float? {
    val raw = text.trim()
    if (raw.endsWith("%")) {
        val percent = raw.dropLast(1).trim().toFloatOrNull() ?: return null
        return (percent / 100f).coerceIn(0f, 1f)
    }
    val value = raw.toFloatOrNull() ?: return null
    return if (value > 1f) (value / 255f).coerceIn(0f, 1f) else value.coerceIn(0f, 1f)
}

// 角色标识色圆点；colorHex 接受十六进制或 CSS rgb()/rgba()，无法解析时回落主题色。
@Composable
fun ColorDot(colorHex: String?, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(
        modifier
            .size(size)
            .background(
                remember(colorHex) { parseCssColor(colorHex) } ?: MaterialTheme.colorScheme.primary,
                CircleShape,
            ),
    )
}

@Composable
fun DeleteConfirmationDialog(
    titleRes: Int,
    messageRes: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
