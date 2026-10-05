package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.catalog.*

@Composable
internal fun CatalogSnapshotDetails(snapshot: CatalogSnapshot, english: Boolean) {
    Text("${tr(english, "快照时间", "Snapshot time")}: ${snapshot.retrievedAt}")
    Text(tr(english,
        "${snapshot.products.size} 条目录记录 · ${snapshot.productsByNormalizedName.size} 个不同名称",
        "${snapshot.products.size} catalog records · ${snapshot.productsByNormalizedName.size} distinct names"))
    val ready = snapshot.responsesByPath.values.count { it is CatalogResponse.Ready }
    Text(tr(english,
        "下载频响 ${snapshot.responsesByPath.size} 份 · 可解析 $ready 份",
        "${snapshot.responsesByPath.size} downloaded response assets · $ready parseable curves"))
    Text("${tr(english, "产品目录来源", "Product catalog source")}: ${snapshot.catalogueUrl}", style = MaterialTheme.typography.bodySmall)
    Text("${tr(english, "频响库来源", "Response library source")}: ${snapshot.responseLibraryUrl}", style = MaterialTheme.typography.bodySmall)
    Text("CDN: ${snapshot.cdnBaseUrl}", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun CatalogDatabaseCard(
    state: CatalogState, english: Boolean, canCancelPull: Boolean, onPull: (CatalogCdn) -> Unit,
    onCancelPull: () -> Unit, onImport: () -> Unit, onExport: () -> Unit, onOpenCatalog: () -> Unit,
) {
    var cdn by remember { mutableStateOf(CatalogCdn.CHINA) }
    val inspectionMode = LocalInspectionMode.current
    val idle = !state.loading && !state.busy && !inspectionMode
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(english, "产品与频响目录", "Product and response catalog"), style = MaterialTheme.typography.titleLarge)
            Text(when {
                state.loading -> tr(english, "正在加载本地数据库", "Loading local database")
                state.origin == CatalogOrigin.BUNDLED -> tr(english, "来源：内置快照", "Source: bundled snapshot")
                state.origin == CatalogOrigin.LOCAL -> tr(english, "来源：本地快照", "Source: local snapshot")
                state.origin == CatalogOrigin.PULL -> tr(english, "来源：本次拉取", "Source: current pull")
                state.origin == CatalogOrigin.IMPORT -> tr(english, "来源：本次导入（来源信息由文件提供）", "Source: current import (provenance supplied by file)")
                else -> tr(english, "没有可用数据库；可导入或显式拉取，也可显示全部设备", "No usable database. Import, explicitly pull, or show all devices.")
            })
            state.snapshot?.let { CatalogSnapshotDetails(it, english) }
            Text(tr(english, "完整离线快照；应用不会自动联网。仅“拉取”访问官方服务。", "Complete offline snapshot. The app never refreshes automatically; only Pull accesses the official service."), style = MaterialTheme.typography.bodySmall)
            state.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(tr(english, "进行中：${state.completedFiles}/${state.totalFiles} 个频响文件", "Working: ${state.completedFiles}/${state.totalFiles} response files"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in CatalogCdn.entries) FilterChip(
                    selected = option == cdn, onClick = { cdn = option }, enabled = idle,
                    label = { Text(if (option == CatalogCdn.CHINA) tr(english, "中国 CDN", "China CDN") else tr(english, "海外 CDN", "Overseas CDN")) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPull(cdn) }, enabled = idle) { Text(tr(english, "拉取", "Pull")) }
                OutlinedButton(onClick = onImport, enabled = idle) { Text(tr(english, "导入", "Import")) }
                OutlinedButton(onClick = onExport, enabled = idle && state.snapshot != null) { Text(tr(english, "导出", "Export")) }
                if (canCancelPull) TextButton(onClick = onCancelPull) { Text(tr(english, "取消", "Cancel")) }
            }
            OutlinedButton(onClick = onOpenCatalog, enabled = state.snapshot != null) { Text(tr(english, "浏览目录", "Browse catalog")) }
        }
    }
}
