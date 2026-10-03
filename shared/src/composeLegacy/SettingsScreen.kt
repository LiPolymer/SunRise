package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    settings: UiSettings,
    english: Boolean,
    dynamicAvailable: Boolean,
    missingPermissions: Set<String>,
    clientAvailable: Boolean,
    modifier: Modifier = Modifier,
    onChange: (UiSettings) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SettingsSectionHeading(tr(english, "外观", "Appearance")) }
        item {
            SettingsCard {
                Text(tr(english, "主题模式", "Theme mode"), style = MaterialTheme.typography.titleSmall)
                SelectionRow {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            modifier = Modifier.heightIn(min = 48.dp),
                            selected = settings.themeMode == mode,
                            onClick = { onChange(settings.copy(themeMode = mode)) },
                            label = { Text(when (mode) {
                                ThemeMode.SYSTEM -> tr(english, "跟随系统", "System")
                                ThemeMode.LIGHT -> tr(english, "浅色", "Light")
                                ThemeMode.DARK -> tr(english, "深色", "Dark")
                            }) },
                        )
                    }
                }
                HorizontalDivider()
                SettingSwitchRow(
                    title = tr(english, "动态取色", "Dynamic color"),
                    subtitle = if (dynamicAvailable)
                        tr(english, "跟随系统壁纸配色", "Use colors from your wallpaper")
                    else tr(english, "需要 Android 12 或更高版本", "Requires Android 12 or later"),
                    checked = settings.dynamicColor && dynamicAvailable,
                    enabled = dynamicAvailable,
                    onChange = { onChange(settings.copy(dynamicColor = it)) },
                )
                HorizontalDivider()
                SettingSwitchRow(
                    title = "AMOLED",
                    subtitle = tr(english, "在深色模式中使用纯黑背景", "Use a pure-black background in dark mode"),
                    checked = settings.amoled,
                    enabled = true,
                    onChange = { onChange(settings.copy(amoled = it)) },
                )
                if (!settings.dynamicColor || !dynamicAvailable) {
                    HorizontalDivider()
                    Text(tr(english, "种子颜色", "Seed color"), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        seedSwatches.forEachIndexed { index, color ->
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .selectable(
                                        selected = index == settings.seedIndex,
                                        role = Role.RadioButton,
                                        onClick = { onChange(settings.copy(seedIndex = index, dynamicColor = false)) },
                                    )
                                    .semantics { contentDescription = tr(english, "种子颜色 ${index + 1}", "Seed color ${index + 1}") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            if (index == settings.seedIndex) 3.dp else 1.dp,
                                            if (index == settings.seedIndex) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.outline,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SettingsSectionHeading(tr(english, "通用与控制", "General & controls")) }
        item {
            SettingsCard {
                Text(tr(english, "语言", "Language"), style = MaterialTheme.typography.titleSmall)
                Text(tr(english, "应用显示语言", "App display language"), style = MaterialTheme.typography.bodyMedium)
                SelectionRow {
                    UiLanguage.entries.forEach { language ->
                        FilterChip(
                            modifier = Modifier.heightIn(min = 48.dp),
                            selected = settings.language == language,
                            onClick = { onChange(settings.copy(language = language)) },
                            label = { Text(when (language) {
                                UiLanguage.SYSTEM -> tr(english, "跟随系统", "System")
                                UiLanguage.CHINESE -> "中文"
                                UiLanguage.ENGLISH -> "English"
                            }) },
                        )
                    }
                }
                HorizontalDivider()
                Text(tr(english, "控制", "Controls"), style = MaterialTheme.typography.titleSmall)
                SettingSwitchRow(
                    title = tr(english, "显示抗风噪按钮", "Show wind-noise button"),
                    subtitle = tr(
                        english,
                        "只影响概览按钮，不会改变耳机模式或映射。",
                        "Only changes the Overview button; it does not alter the headset mode or mapping.",
                    ),
                    checked = settings.showWind,
                    enabled = true,
                    onChange = { onChange(settings.copy(showWind = it)) },
                )
            }
        }

        item { SettingsSectionHeading(tr(english, "权限与诊断", "Permissions & diagnostics")) }
        item {
            SettingsCard {
                Text(tr(english, "蓝牙权限状态", "Bluetooth permission status"), style = MaterialTheme.typography.titleSmall)
                if (!clientAvailable) {
                    Text(
                        tr(english, "当前平台不支持蓝牙控制", "Bluetooth control is unavailable on this platform"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        if (missingPermissions.isEmpty())
                            tr(english, "蓝牙权限已授予", "Bluetooth permissions granted")
                        else tr(english, "缺少权限：", "Missing permissions: ") +
                            missingPermissions.joinToString { it.substringAfterLast('.') },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (clientAvailable && missingPermissions.isNotEmpty()) {
                    Button(onClick = onRequestPermissions, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(tr(english, "请求权限", "Request permissions"))
                    }
                }
                HorizontalDivider()
                Text(tr(english, "高级诊断", "Advanced diagnostics"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(
                        english,
                        "查看协议能力、最近通知及 GAIA 与 9ECA 测试控件。",
                        "View protocol capabilities, recent notifications, and GAIA and 9ECA test controls.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(tr(english, "打开高级诊断", "Open advanced diagnostics"))
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeading(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SelectionRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
