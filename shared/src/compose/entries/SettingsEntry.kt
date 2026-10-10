package ink.lipoly.app.sunrise.compose.entries

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.catalog.CatalogCdn
import ink.lipoly.app.sunrise.catalog.CatalogOrigin
import ink.lipoly.app.sunrise.catalog.CatalogResponse
import ink.lipoly.app.sunrise.catalog.CatalogSnapshot
import ink.lipoly.app.sunrise.catalog.CatalogState
import ink.lipoly.app.sunrise.settings.ThemeMode
import ink.lipoly.app.sunrise.settings.UiSettings
import ink.lipoly.app.sunrise.compose.ListItemCard
import ink.lipoly.app.sunrise.compose.seedSwatches

@Composable
internal fun SettingsEntry(
    settings: UiSettings,
    dynamicAvailable: Boolean,
    catalogState: CatalogState,
    canCancelPull: Boolean,
    onPull: (CatalogCdn) -> Unit,
    onCancelPull: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOpenCatalog: () -> Unit,
    missingPermissions: Set<String>,
    clientAvailable: Boolean,
    modifier: Modifier = Modifier,
    onChange: (UiSettings) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    LazyColumn(modifier = modifier) {
        item {
            var cdn by remember { mutableStateOf(CatalogCdn.CHINA) }
            val inspectionMode = LocalInspectionMode.current
            val idle = !catalogState.loading && !catalogState.busy && !inspectionMode
            ListItemCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("产品与频响目录", style = MaterialTheme.typography.titleLarge)
                    Text(when {
                        catalogState.loading -> "正在加载本地数据库"
                        catalogState.origin == CatalogOrigin.BUNDLED -> "来源：内置快照"
                        catalogState.origin == CatalogOrigin.LOCAL -> "来源：本地快照"
                        catalogState.origin == CatalogOrigin.PULL -> "来源：本次拉取"
                        catalogState.origin == CatalogOrigin.IMPORT -> "来源：本次导入（来源信息由文件提供）"
                        else -> "没有可用数据库；可导入或显式拉取，也可显示全部设备"
                    })
                    catalogState.snapshot?.let { CatalogSnapshotDetails(it) }
                    Text("完整离线快照；应用不会自动联网。仅“拉取”访问官方服务。", style = MaterialTheme.typography.bodySmall)
                    catalogState.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    catalogState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (catalogState.busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("进行中：${catalogState.completedFiles}/${catalogState.totalFiles} 个频响文件")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in CatalogCdn.entries) FilterChip(
                            selected = option == cdn, onClick = { cdn = option }, enabled = idle,
                            label = { Text(if (option == CatalogCdn.CHINA) "中国 CDN" else "海外 CDN") },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPull(cdn) }, enabled = idle) { Text("拉取") }
                        OutlinedButton(onClick = onImport, enabled = idle) { Text("导入") }
                        OutlinedButton(onClick = onExport, enabled = idle && catalogState.snapshot != null) { Text("导出") }
                        if (canCancelPull) TextButton(onClick = onCancelPull) { Text("取消") }
                    }
                    OutlinedButton(onClick = onOpenCatalog, enabled = catalogState.snapshot != null) { Text("浏览目录") }
                }
            }
        }
        item { SettingsSectionHeading("外观") }
        item {
            ListItemCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("主题模式", style = MaterialTheme.typography.titleSmall)
                    SelectionRow {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                modifier = Modifier.heightIn(min = 48.dp),
                                selected = settings.themeMode == mode,
                                onClick = { onChange(settings.copy(themeMode = mode)) },
                                label = { Text(when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                }) },
                            )
                        }
                    }
                    HorizontalDivider()
                    SettingSwitchRow(
                        title = "动态取色",
                        subtitle = if (dynamicAvailable)
                            "跟随系统壁纸配色"
                        else "需要 Android 12 或更高版本",
                        checked = settings.dynamicColor && dynamicAvailable,
                        enabled = dynamicAvailable,
                        onChange = { onChange(settings.copy(dynamicColor = it)) },
                    )
                    HorizontalDivider()
                    SettingSwitchRow(
                        title = "AMOLED",
                        subtitle = "在深色模式中使用纯黑背景",
                        checked = settings.amoled,
                        enabled = true,
                        onChange = { onChange(settings.copy(amoled = it)) },
                    )
                    if (!settings.dynamicColor || !dynamicAvailable) {
                        HorizontalDivider()
                        Text("种子颜色", style = MaterialTheme.typography.titleSmall)
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
                                        .semantics { contentDescription = "种子颜色 ${index + 1}" },
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
        }

        item { SettingsSectionHeading("通用与控制") }
        item {
            ListItemCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("控制", style = MaterialTheme.typography.titleSmall)
                    SettingSwitchRow(
                        title = "显示抗风噪按钮",
                        subtitle = "只影响概览按钮，不会改变耳机模式或映射。",
                        checked = settings.showWind,
                        enabled = true,
                        onChange = { onChange(settings.copy(showWind = it)) },
                    )
                }
            }
        }
        item {
            ListItemCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingSwitchRow(
                        "仅显示目录设备",
                        "仅按完整产品名称过滤；目录匹配不代表协议支持。显示全部可选择改名或未收录设备。",
                        settings.catalogOnlyDevices, true,
                    ) { onChange(settings.copy(catalogOnlyDevices = it)) }
                    SettingSwitchRow(
                        "显示参考频响",
                        "产品目录与频响库资料，非当前耳机实测",
                        settings.showReferenceResponse, true,
                    ) { onChange(settings.copy(showReferenceResponse = it)) }
                    SettingSwitchRow(
                        "预测包含自动前置增益",
                        "只改变图形预测，不改变耳机参数或发送方式",
                        settings.includeResponsePreGain, true,
                    ) { onChange(settings.copy(includeResponsePreGain = it)) }
                }
            }
        }

        item { SettingsSectionHeading("权限与诊断") }
        item {
            ListItemCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("蓝牙权限状态", style = MaterialTheme.typography.titleSmall)
                    if (!clientAvailable) {
                        Text(
                            "当前平台不支持蓝牙控制",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            if (missingPermissions.isEmpty())
                                "蓝牙权限已授予"
                            else "缺少权限：" +
                                missingPermissions.joinToString { it.substringAfterLast('.') },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (clientAvailable && missingPermissions.isNotEmpty()) {
                        Button(onClick = onRequestPermissions, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("请求权限")
                        }
                    }
                    HorizontalDivider()
                    Text("高级诊断", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "查看协议能力、最近通知及 GAIA 与 9ECA 测试控件。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("打开高级诊断")
                    }
                }
            }
        }
        item {
            ListItemCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("关于与许可证", style = MaterialTheme.typography.titleSmall)
                    Text("PolyForm Noncommercial License 1.0.0")
                    TextButton(
                        onClick = { uriHandler.openUri("https://polyformproject.org/licenses/noncommercial/1.0.0") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("查看许可证原文")
                    }
                    Text(
                        text = buildAnnotatedString {
                            append("Required Notice: Copyright © 2026 ")
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "LiPolymer",
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                    ),
                                    linkInteractionListener = { uriHandler.openUri("https://github.com/LiPolymer") },
                                ),
                            ) {
                                append("LiPolymer")
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeading(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

@Composable
internal fun CatalogSnapshotDetails(snapshot: CatalogSnapshot) {
    Text("快照时间: ${snapshot.retrievedAt}")
    Text("${snapshot.products.size} 条目录记录 · ${snapshot.productsByNormalizedName.size} 个不同名称")
    val ready = snapshot.responsesByPath.values.count { it is CatalogResponse.Ready }
    Text("下载频响 ${snapshot.responsesByPath.size} 份 · 可解析 $ready 份")
    Text("产品目录来源: ${snapshot.catalogueUrl}", style = MaterialTheme.typography.bodySmall)
    Text("频响库来源: ${snapshot.responseLibraryUrl}", style = MaterialTheme.typography.bodySmall)
    Text("CDN: ${snapshot.cdnBaseUrl}", style = MaterialTheme.typography.bodySmall)
}
