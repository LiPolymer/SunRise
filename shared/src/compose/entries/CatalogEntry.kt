package ink.lipoly.app.sunrise.compose.entries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ink.lipoly.app.sunrise.catalog.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import ink.lipoly.app.sunrise.compose.CatalogSnapshotDetails
import ink.lipoly.app.sunrise.compose.CatalogReferencePlot
import ink.lipoly.app.sunrise.compose.ListItemCard

private fun catalogMetadata(product: CatalogProduct, key: String): String {
    val text = when (val value = product.raw[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { it.isNotBlank() }
        else -> value.toString().takeUnless { it == "[]" || it == "{}" }
    }
    return text ?: "未提供"
}

internal fun catalogResponseStatus(snapshot: CatalogSnapshot, product: CatalogProduct): String {
    if (product.freqResponse == null) return "目录未提供参考频响"
    return when (val response = snapshot.responsesByPath[product.freqResponse]) {
        is CatalogResponse.Ready -> "参考频响可用"
        is CatalogResponse.Unavailable -> "参考频响不可解析：${response.reason}"
        null -> "离线参考资产不可用"
    }
}

/** Search is deliberately separate from exact-name device matching. No model grouping or UUID deduplication. */
private fun searchCatalogProducts(products: List<CatalogProduct>, query: String): List<CatalogProduct> {
    val search = query.trim()
    if (search.isEmpty()) return products
    return products.filter { it.name.contains(search, ignoreCase = true) || it.model?.contains(search, ignoreCase = true) == true }
}

@Composable
private fun CatalogProductRow(snapshot: CatalogSnapshot, product: CatalogProduct, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(product.name, style = MaterialTheme.typography.titleMedium)
        Text("型号: ${catalogMetadata(product, "model")} · 类型: ${product.type}")
        Text("语言: ${catalogMetadata(product, "languageType")} · 芯片（目录）: ${catalogMetadata(product, "chipType")}", style = MaterialTheme.typography.bodySmall)
        Text("目录标称 EQ 段数: ${catalogMetadata(product, "eqBands")}", style = MaterialTheme.typography.bodySmall)
        Text("UUID: ${product.uuid}", style = MaterialTheme.typography.bodySmall)
        Text(catalogResponseStatus(snapshot, product), style = MaterialTheme.typography.bodySmall)
    }
}

/** All UUIDs, with the current exact-name group first. Selection itself never writes device parameters. */
@Composable
internal fun CatalogProductSelector(
    snapshot: CatalogSnapshot?,
    deviceName: String?,
    onSelect: (CatalogProduct) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择参考频响",
) {
    var query by remember { mutableStateOf("") }
    val ordered = remember(snapshot, deviceName) { snapshot?.let { orderedCatalogProducts(it, deviceName) }.orEmpty() }
    val products = remember(ordered, query) { searchCatalogProducts(ordered, query) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text("列出所有 UUID（包括无曲线记录）；当前设备同名记录置顶。仅离线选择参考，不改变设备筛选或发送 EQ。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("搜索名称 / 型号") })
                Text("${products.size} 条记录", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (snapshot == null) item { Text("没有可用的离线数据库") }
                    else if (products.isEmpty()) item { Text("没有匹配的名称或型号") }
                    if (snapshot != null) items(products, key = { it.uuid }) { product ->
                        CatalogProductRow(snapshot, product) { onSelect(product); onDismiss() }
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}

@Composable
internal fun CatalogEntry(
    snapshot: CatalogSnapshot?,
    deviceName: String?,
    deviceEqBands: Int?,
    onUseReference: (CatalogProduct) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selectedUuid by remember { mutableStateOf<String?>(null) }
    val ordered = remember(snapshot, deviceName) { snapshot?.let { orderedCatalogProducts(it, deviceName) }.orEmpty() }
    val products = remember(ordered, query) { searchCatalogProducts(ordered, query) }
    val selected = selectedUuid?.let { snapshot?.productsByUuid?.get(it) }
    LaunchedEffect(snapshot, selectedUuid) {
        if (selectedUuid != null && selected == null) selectedUuid = null
    }
    if (snapshot != null && selected != null) {
        CatalogProductDetail(snapshot, selected, deviceName, deviceEqBands, onUseReference, { selectedUuid = null }, modifier)
        return
    }
    LazyColumn(modifier) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("产品与频响目录", style = MaterialTheme.typography.titleLarge)
                Text("按完整产品与频响记录浏览。搜索仅查询 name / model，不改变精确名称设备筛选。目录资料不代表已验证的设备控制能力。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (snapshot == null) item { Text("没有可用的离线数据库；可在设置导入或显式拉取。", Modifier.padding(16.dp)) }
        else {
            item { ListItemCard { Column(Modifier.padding(16.dp)) { CatalogSnapshotDetails(snapshot) } } }
            item {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("搜索名称 / 型号") })
                    Text("${products.size} / ${snapshot.products.size} 条记录（不合并同名 UUID）")
                }
            }
            if (products.isEmpty()) item { Text("没有匹配的名称或型号", Modifier.padding(16.dp)) }
            items(products, key = { it.uuid }) { product ->
                ListItemCard { CatalogProductRow(snapshot, product) { selectedUuid = product.uuid } }
            }
        }
    }
}

private fun comparisonUnavailableReason(snapshot: CatalogSnapshot, product: CatalogProduct): String? {
    val response = product.freqResponse?.let { snapshot.responsesByPath[it] }
    if (response !is CatalogResponse.Ready) return catalogResponseStatus(snapshot, product)
    if (response.response.frequencyHz.first() > 500.0 || response.response.frequencyHz.last() < 500.0) {
        return "曲线不覆盖 500 Hz，无法进行 500 Hz 归一化形状对比"
    }
    return null
}

@Composable
private fun CatalogProductDetail(
    snapshot: CatalogSnapshot, product: CatalogProduct, deviceName: String?, deviceEqBands: Int?,
    onUseReference: (CatalogProduct) -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    var comparisonUuid by remember(product.uuid) { mutableStateOf<String?>(null) }
    var chooseComparison by remember(product.uuid) { mutableStateOf(false) }
    val response = product.freqResponse?.let { snapshot.responsesByPath[it] } as? CatalogResponse.Ready
    val sampled = remember(response) { response?.let { sampleCatalogResponse(it.response) } }
    val comparison = comparisonUuid?.let { snapshot.productsByUuid[it] }
    val comparisonResponse = comparison?.freqResponse?.let { snapshot.responsesByPath[it] } as? CatalogResponse.Ready
    val otherSampled = remember(comparisonResponse) { comparisonResponse?.let { sampleCatalogResponse(it.response) } }
    val firstReason = comparisonUnavailableReason(snapshot, product)
    val secondReason = comparison?.let {
        if (it.uuid == product.uuid) "请选择另一个 UUID 进行双曲线对比"
        else comparisonUnavailableReason(snapshot, it)
    }
    val comparisonEnabled = comparison != null && firstReason == null && secondReason == null
    val directoryBands = (product.raw["eqBands"] as? JsonPrimitive)?.intOrNull
    LazyColumn(modifier) {
        item {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = onBack) { Text("返回目录列表") }
                Text(product.name, style = MaterialTheme.typography.headlineSmall)
                Text("UUID: ${product.uuid}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ListItemCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf(
                        "model" to "型号",
                        "type" to "类型",
                        "languageType" to "语言",
                        "chipType" to "芯片（目录）",
                        "eqBands" to "目录标称 EQ 段数",
                        "gainRange" to "目录增益范围",
                        "qRange" to "目录 Q 范围",
                        "supportedFilterTypes" to "目录滤波器类型",
                    )) Text("$label: ${catalogMetadata(product, key)}")
                    if (product.uuid in snapshot.responseLibraryUuids) {
                        Text("原始标签: ${product.raw["tags"]}", style = MaterialTheme.typography.bodySmall)
                        Text("此记录来自频响库，是频响或目标记录，而非设备型号。", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Response 为本地频响库分类，其他资料保留源值；不表示设备或协议能力，也不用于推断参数限制或开放控制能力。", style = MaterialTheme.typography.bodySmall)
                    if (deviceEqBands != null) {
                        Text("当前设备读回 EQ 段数：$deviceEqBands（${deviceName ?: "未提供名称"}）")
                        if (directoryBands != null && directoryBands != deviceEqBands) Text("目录标称 $directoryBands 段 / 设备读回 $deviceEqBands 段；编辑与写入以设备读回为准。", color = MaterialTheme.colorScheme.error)
                    }
                    Text(catalogResponseStatus(snapshot, product))
                    Text("参考资产路径: ${catalogMetadata(product, "freqResponse")}", style = MaterialTheme.typography.bodySmall)
                    Text("来源 UTC: ${snapshot.retrievedAt}", style = MaterialTheme.typography.bodySmall)
                    Text("资料来源: ${catalogSourceUrl(snapshot, product)}", style = MaterialTheme.typography.bodySmall)
                    Text("CDN: ${snapshot.cdnBaseUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("来源信息来自快照文件；参考频响或目标资料并非当前设备的实测频响。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ListItemCard {
                Column(Modifier.padding(16.dp)) {
                    Button(onClick = { onUseReference(product) }) { Text("用作参考频响") }
                    Text("只选择参考 UUID，不联网、不发送蓝牙命令，也不加载 EQ 预设。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ListItemCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("参考频响", style = MaterialTheme.typography.titleMedium)
                    if (sampled == null) Text(catalogResponseStatus(snapshot, product))
                    else if (sampled.frequencyHz.isEmpty()) Text("没有可显示频段：原始资料与 20 Hz–20 kHz 无交集。")
                    else {
                        Text(if (sampled.normalizationHz == null) "原始 SPL dB · 未归一化（不覆盖 500 Hz）"
                            else "参考 dB · 500 Hz = 0（SunRise 显示归一化）", style = MaterialTheme.typography.labelMedium)
                        ReferenceLegend(product, MaterialTheme.colorScheme.onSurface, false)
                        if (comparisonEnabled) ReferenceLegend(comparison, MaterialTheme.colorScheme.primary, true)
                        CatalogReferencePlot(sampled, if (comparisonEnabled) otherSampled else null, Modifier.fillMaxWidth().height(260.dp))
                        Text("只画各曲线自身覆盖范围，不外推；测量曲线的条件未知，不代表佩戴、ANC 或音量状态一致，目标曲线并非设备实测。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            ListItemCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { chooseComparison = true }, enabled = firstReason == null) {
                        Text("选择第二条参考频响作双曲线对比")
                    }
                    firstReason?.let { Text("双曲线对比不可用：$it", style = MaterialTheme.typography.bodySmall) }
                    comparison?.let { other ->
                        Text("第二条参考频响: ${other.name}")
                        Text("语言: ${catalogMetadata(other, "languageType")} · UUID: ${other.uuid}", style = MaterialTheme.typography.bodySmall)
                        Text(catalogResponseStatus(snapshot, other), style = MaterialTheme.typography.bodySmall)
                        secondReason?.let { Text("双曲线对比不可用：$it", style = MaterialTheme.typography.bodySmall) }
                        if (comparisonEnabled) Text("双曲线共用 dB 轴；各自以 500 Hz 归一化，仅对比形状。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { comparisonUuid = null }) { Text("清除对比") }
                    }
                }
            }
        }
    }
    if (chooseComparison) CatalogProductSelector(snapshot, deviceName, { comparisonUuid = it.uuid }, { chooseComparison = false }, "选择第二个参考 UUID")
}

@Composable
private fun ReferenceLegend(product: CatalogProduct, color: Color, dashed: Boolean) {
    Text("${if (dashed) "┄" else "━"} ${product.name} · ${catalogMetadata(product, "languageType")}", color = color, style = MaterialTheme.typography.bodySmall)
    Text("UUID: ${product.uuid}", style = MaterialTheme.typography.bodySmall)
}
