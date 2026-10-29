package com.oneasmr.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Building blocks of the grouped-card settings page: every section is a
 * stack of tonal "segments" separated by a 2dp gap, with large outer corners
 * and tight inner corners (Android 16 Settings style). Each row leads with a
 * tinted icon badge so sections can be scanned by color/shape before
 * reading any text.
 */

private val OuterCorner = 24.dp
private val InnerCorner = 6.dp

/** Corner shape for the [index]-th of [count] segments inside one settings group. */
internal fun settingsSegmentShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(OuterCorner)
    index == 0 -> RoundedCornerShape(
        topStart = OuterCorner,
        topEnd = OuterCorner,
        bottomStart = InnerCorner,
        bottomEnd = InnerCorner,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = InnerCorner,
        topEnd = InnerCorner,
        bottomStart = OuterCorner,
        bottomEnd = OuterCorner,
    )
    else -> RoundedCornerShape(InnerCorner)
}

/** Tonal role for a row's leading icon badge. */
internal enum class SettingsAccent { Primary, Secondary, Tertiary, Error }

@Composable
private fun SettingsAccent.containerColor(): Color = when (this) {
    SettingsAccent.Primary -> MaterialTheme.colorScheme.primaryContainer
    SettingsAccent.Secondary -> MaterialTheme.colorScheme.secondaryContainer
    SettingsAccent.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer
    SettingsAccent.Error -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun SettingsAccent.contentColor(): Color = when (this) {
    SettingsAccent.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
    SettingsAccent.Secondary -> MaterialTheme.colorScheme.onSecondaryContainer
    SettingsAccent.Tertiary -> MaterialTheme.colorScheme.onTertiaryContainer
    SettingsAccent.Error -> MaterialTheme.colorScheme.onErrorContainer
}

@Composable
private fun SettingsIconBadge(icon: ImageVector, accent: SettingsAccent) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(accent.containerColor(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent.contentColor(),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * One settings segment: icon badge + title/subtitle + optional trailing
 * control, with an optional full-width [below] slot for controls that need
 * the whole row (segmented buttons, progress bars, lists).
 */
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    accent: SettingsAccent = SettingsAccent.Primary,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Column(
            Modifier
                .alpha(if (enabled) 1f else 0.38f)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconBadge(icon, accent)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor.takeOrElse { MaterialTheme.colorScheme.onSurface },
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = subtitleColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    trailing()
                }
            }
            if (below != null) {
                Spacer(Modifier.height(12.dp))
                below()
            }
        }
    }
    val color = MaterialTheme.colorScheme.surfaceContainer
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = color,
            modifier = modifier.fillMaxWidth(),
            content = body,
        )
    } else {
        Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth(), content = body)
    }
}

/** Row whose whole surface toggles [checked]; the [Switch] is display-only. */
@Composable
internal fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    accent: SettingsAccent = SettingsAccent.Primary,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsItem(
        icon = icon,
        title = title,
        shape = shape,
        accent = accent,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** Trailing "navigates somewhere / opens a dialog" affordance with an optional value. */
@Composable
internal fun SettingsChevron(value: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!value.isNullOrEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Full-width single-choice segmented row for the [SettingsItem.below] slot. */
@Composable
internal fun <T> SettingsSegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@Composable
internal fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** Pill search field matching the 搜索 tab styling. */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("搜索设置项") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清空")
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
        ),
    )
}

/**
 * 分类筛选条：索引 0 是「全部」（不过滤），其余与 [categories] 顺序对应。
 *
 * 用 [FilterChip] 而不是 AssistChip，是为了让"当前选中哪一类"成为真正的
 * 可选状态（selected 语义，TalkBack 也会读出来）——点击即切换下方内容的
 * 展示范围，不再是"滚到某一节"的跳转。
 */
@Composable
internal fun SettingsCategoryChips(
    categories: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelect: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEachIndexed { index, (label, icon) ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label) },
                leadingIcon = {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                shape = CircleShape,
            )
        }
    }
}

/** Prominent call-to-action card (e.g. an update is ready). */
@Composable
internal fun SettingsBanner(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(OuterCorner),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Shown when the search query matches nothing. */
@Composable
internal fun SettingsEmptySearch(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "没有找到与「$query」相关的设置",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
