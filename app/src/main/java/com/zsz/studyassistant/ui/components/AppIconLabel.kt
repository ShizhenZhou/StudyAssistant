package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// Only used for app-owned control labels, never AI answers, user text or release history.
// Keep the localized wording intact; emoji prefixes in existing translations are metadata here.
internal fun appLabelIcon(text: String): Pair<AppIcon?, String> {
    val match = labelIcons.firstOrNull { text.startsWith(it.first) } ?: return null to text
    return match.second to text.removePrefix(match.first).trimStart('\uFE0F', ' ', '\u00A0')
}

@Composable
internal fun AppIconLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: AppIcon? = null,
    iconSize: Dp = 20.dp,
    iconSpacing: Dp = 6.dp,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    softWrap: Boolean = true,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    onPrimary: Boolean = false,
    iconBackground: Color? = null
) {
    val (prefixIcon, label) = if (icon == null) appLabelIcon(text) else null to text
    val symbol = icon ?: prefixIcon
    if (symbol == null) {
        Text(text, modifier, color = color, fontSize = fontSize, style = style,
            maxLines = maxLines, overflow = overflow, textAlign = textAlign, fontWeight = fontWeight, softWrap = softWrap, textDecoration = textDecoration)
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(iconSpacing)) {
            AppSymbol(symbol, size = iconSize, onPrimary = onPrimary, backgroundColor = iconBackground)
            Text(label, Modifier.weight(1f, fill = false), color = color, fontSize = fontSize,
                style = style, maxLines = maxLines, overflow = overflow, textAlign = textAlign, fontWeight = fontWeight, softWrap = softWrap, textDecoration = textDecoration)
        }
    }
}

private val labelIcons = listOf(
    "📊" to AppIcon.Chart, "🗓" to AppIcon.Calendar, "⏰" to AppIcon.Alarm,
    "⇅" to AppIcon.Sort, "↩" to AppIcon.Restore, "🔍" to AppIcon.Search,
    "📷" to AppIcon.Camera, "✏" to AppIcon.Edit, "✍" to AppIcon.Edit,
    "📚" to AppIcon.Notebook, "📖" to AppIcon.Book, "🕘" to AppIcon.History,
    "🤖" to AppIcon.Ai, "🧠" to AppIcon.Brain, "🔔" to AppIcon.Bell,
    "🎨" to AppIcon.Theme, "🌐" to AppIcon.Language, "💾" to AppIcon.Data,
    "⬆" to AppIcon.Update, "ℹ" to AppIcon.Info, "ⓘ" to AppIcon.Info,
    "🔑" to AppIcon.Key, "✂" to AppIcon.Crop, "⏱" to AppIcon.Timer,
    "🗑" to AppIcon.Trash, "🗂" to AppIcon.Folder, "📁" to AppIcon.Folder,
    "🛠" to AppIcon.Tools, "🐞" to AppIcon.Bug, "🗄" to AppIcon.Backup,
    "✋" to AppIcon.Backup, "📋" to AppIcon.List, "🔒" to AppIcon.Shield,
    "🖼" to AppIcon.Gallery, "🔄" to AppIcon.Refresh, "⏸" to AppIcon.Pause,
    "⚠" to AppIcon.Warning, "🌱" to AppIcon.Leaf, "🌤" to AppIcon.Cloud,
    "🔥" to AppIcon.Flame, "➕" to AppIcon.Plus, "👁" to AppIcon.Eye,
    "🙈" to AppIcon.EyeOff, "✅" to AppIcon.Check, "🏷" to AppIcon.Tag
)
