package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.catalog.*

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

@Composable
internal fun CatalogDatabaseCard(
    state: CatalogState, canCancelPull: Boolean, onPull: (CatalogCdn) -> Unit,
    onCancelPull: () -> Unit, onImport: () -> Unit, onExport: () -> Unit, onOpenCatalog: () -> Unit,
) {
    var cdn by remember { mutableStateOf(CatalogCdn.CHINA) }
    val inspectionMode = LocalInspectionMode.current
    val idle = !state.loading && !state.busy && !inspectionMode
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("产品与频响目录", style = MaterialTheme.typography.titleLarge)
            Text(when {
                state.loading -> "正在加载本地数据库"
                state.origin == CatalogOrigin.BUNDLED -> "来源：内置快照"
                state.origin == CatalogOrigin.LOCAL -> "来源：本地快照"
                state.origin == CatalogOrigin.PULL -> "来源：本次拉取"
                state.origin == CatalogOrigin.IMPORT -> "来源：本次导入（来源信息由文件提供）"
                else -> "没有可用数据库；可导入或显式拉取，也可显示全部设备"
            })
            state.snapshot?.let { CatalogSnapshotDetails(it) }
            Text("完整离线快照；应用不会自动联网。仅“拉取”访问官方服务。", style = MaterialTheme.typography.bodySmall)
            state.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("进行中：${state.completedFiles}/${state.totalFiles} 个频响文件")
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
                OutlinedButton(onClick = onExport, enabled = idle && state.snapshot != null) { Text("导出") }
                if (canCancelPull) TextButton(onClick = onCancelPull) { Text("取消") }
            }
            OutlinedButton(onClick = onOpenCatalog, enabled = state.snapshot != null) { Text("浏览目录") }
        }
    }
}
