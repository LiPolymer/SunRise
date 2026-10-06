package ink.lipoly.app.sunrise.composeLegacy

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

private fun catalogMetadata(product: CatalogProduct, key: String, english: Boolean): String {
    val text = when (val value = product.raw[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { it.isNotBlank() }
        else -> value.toString().takeUnless { it == "[]" || it == "{}" }
    }
    return text ?: tr(english, "未提供", "Not provided")
}

internal fun catalogResponseStatus(snapshot: CatalogSnapshot, product: CatalogProduct, english: Boolean): String {
    if (product.freqResponse == null) return tr(english, "目录未提供参考频响", "No reference response provided by the catalogue")
    return when (val response = snapshot.responsesByPath[product.freqResponse]) {
        is CatalogResponse.Ready -> tr(english, "参考频响可用", "Reference response available")
        is CatalogResponse.Unavailable -> tr(english, "参考频响不可解析：${response.reason}", "Reference response cannot be parsed: ${response.reason}")
        null -> tr(english, "离线参考资产不可用", "Offline reference asset unavailable")
    }
}

/** Search is deliberately separate from exact-name device matching. No model grouping or UUID deduplication. */
private fun searchCatalogProducts(products: List<CatalogProduct>, query: String): List<CatalogProduct> {
    val search = query.trim()
    if (search.isEmpty()) return products
    return products.filter { it.name.contains(search, ignoreCase = true) || it.model?.contains(search, ignoreCase = true) == true }
}

@Composable
private fun CatalogProductRow(snapshot: CatalogSnapshot, product: CatalogProduct, english: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(product.name, style = MaterialTheme.typography.titleMedium)
        Text("${tr(english, "型号", "Model")}: ${catalogMetadata(product, "model", english)} · ${tr(english, "类型", "Type")}: ${product.type}")
        Text("${tr(english, "语言", "Language")}: ${catalogMetadata(product, "languageType", english)} · ${tr(english, "芯片（目录）", "Chip (directory)")}: ${catalogMetadata(product, "chipType", english)}", style = MaterialTheme.typography.bodySmall)
        Text("${tr(english, "目录标称 EQ 段数", "Directory EQ bands")}: ${catalogMetadata(product, "eqBands", english)}", style = MaterialTheme.typography.bodySmall)
        Text("UUID: ${product.uuid}", style = MaterialTheme.typography.bodySmall)
        Text(catalogResponseStatus(snapshot, product, english), style = MaterialTheme.typography.bodySmall)
    }
}

/** All UUIDs, with the current exact-name group first. Selection itself never writes device parameters. */
@Composable
internal fun CatalogProductSelector(
    snapshot: CatalogSnapshot?,
    deviceName: String?,
    english: Boolean,
    onSelect: (CatalogProduct) -> Unit,
    onDismiss: () -> Unit,
    title: String = tr(english, "选择参考频响", "Choose a reference response"),
) {
    var query by remember { mutableStateOf("") }
    val ordered = remember(snapshot, deviceName, english) { snapshot?.let { orderedCatalogProducts(it, deviceName, english) }.orEmpty() }
    val products = remember(ordered, query) { searchCatalogProducts(ordered, query) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(tr(english, "列出所有 UUID（包括无曲线记录）；当前设备同名记录置顶。仅离线选择参考，不改变设备筛选或发送 EQ。", "All UUIDs, including records without curves; exact-name records for this device come first. Offline reference selection does not change device filtering or send EQ."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr(english, "搜索名称 / 型号", "Search name / model")) })
                Text(tr(english, "${products.size} 条记录", "${products.size} records"), style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (snapshot == null) item { Text(tr(english, "没有可用的离线数据库", "No usable offline database")) }
                    else if (products.isEmpty()) item { Text(tr(english, "没有匹配的名称或型号", "No matching names or models")) }
                    if (snapshot != null) items(products, key = { it.uuid }) { product ->
                        CatalogProductRow(snapshot, product, english) { onSelect(product); onDismiss() }
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = onDismiss) { Text(tr(english, "取消", "Cancel")) }
            }
        }
    }
}

@Composable
internal fun ProductCatalogScreen(
    snapshot: CatalogSnapshot?,
    english: Boolean,
    deviceName: String?,
    deviceEqBands: Int?,
    onUseReference: (CatalogProduct) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selectedUuid by remember { mutableStateOf<String?>(null) }
    val ordered = remember(snapshot, deviceName, english) { snapshot?.let { orderedCatalogProducts(it, deviceName, english) }.orEmpty() }
    val products = remember(ordered, query) { searchCatalogProducts(ordered, query) }
    val selected = selectedUuid?.let { snapshot?.productsByUuid?.get(it) }
    if (snapshot != null && selected != null) {
        CatalogProductDetail(snapshot, selected, english, deviceName, deviceEqBands, onUseReference, { selectedUuid = null }, modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(tr(english, "产品与频响目录", "Product and response catalog"), style = MaterialTheme.typography.titleLarge)
            Text(tr(english, "按完整产品与频响记录浏览。搜索仅查询 name / model，不改变精确名称设备筛选。目录资料不代表已验证的设备控制能力。", "Browse complete product and response records. Search uses name / model only and does not change exact-name device filtering. Directory metadata is not proof of device-control capabilities."), style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot == null) item { Text(tr(english, "没有可用的离线数据库；可在设置导入或显式拉取。", "No usable offline database. Import or explicitly pull in Settings.")) }
        else {
            item { CatalogSnapshotDetails(snapshot, english) }
            item {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr(english, "搜索名称 / 型号", "Search name / model")) })
                Text(tr(english, "${products.size} / ${snapshot.products.size} 条记录（不合并同名 UUID）", "${products.size} / ${snapshot.products.size} records (same-name UUIDs stay separate)"))
            }
            if (products.isEmpty()) item { Text(tr(english, "没有匹配的名称或型号", "No matching names or models")) }
            items(products, key = { it.uuid }) { product ->
                Card(Modifier.fillMaxWidth()) { CatalogProductRow(snapshot, product, english) { selectedUuid = product.uuid } }
            }
        }
    }
}

private fun comparisonUnavailableReason(snapshot: CatalogSnapshot, product: CatalogProduct, english: Boolean): String? {
    val response = product.freqResponse?.let { snapshot.responsesByPath[it] }
    if (response !is CatalogResponse.Ready) return catalogResponseStatus(snapshot, product, english)
    if (response.response.frequencyHz.first() > 500.0 || response.response.frequencyHz.last() < 500.0) {
        return tr(english, "曲线不覆盖 500 Hz，无法进行 500 Hz 归一化形状对比", "The curve does not cover 500 Hz; a 500 Hz-normalized shape comparison is unavailable")
    }
    return null
}

@Composable
private fun CatalogProductDetail(
    snapshot: CatalogSnapshot, product: CatalogProduct, english: Boolean, deviceName: String?, deviceEqBands: Int?,
    onUseReference: (CatalogProduct) -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    var comparisonUuid by remember(product.uuid) { mutableStateOf<String?>(null) }
    var chooseComparison by remember(product.uuid) { mutableStateOf(false) }
    val response = product.freqResponse?.let { snapshot.responsesByPath[it] } as? CatalogResponse.Ready
    val sampled = remember(response) { response?.let { sampleCatalogResponse(it.response) } }
    val comparison = comparisonUuid?.let { snapshot.productsByUuid[it] }
    val comparisonResponse = comparison?.freqResponse?.let { snapshot.responsesByPath[it] } as? CatalogResponse.Ready
    val otherSampled = remember(comparisonResponse) { comparisonResponse?.let { sampleCatalogResponse(it.response) } }
    val firstReason = comparisonUnavailableReason(snapshot, product, english)
    val secondReason = comparison?.let {
        if (it.uuid == product.uuid) tr(english, "请选择另一个 UUID 进行双曲线对比", "Choose a different UUID for a two-curve comparison")
        else comparisonUnavailableReason(snapshot, it, english)
    }
    val comparisonEnabled = comparison != null && firstReason == null && secondReason == null
    val directoryBands = (product.raw["eqBands"] as? JsonPrimitive)?.intOrNull
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text(tr(english, "返回目录列表", "Back to catalog list")) }
            Text(product.name, style = MaterialTheme.typography.headlineSmall)
            Text("UUID: ${product.uuid}", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf(
                        "model" to tr(english, "型号", "Model"),
                        "type" to tr(english, "类型", "Type"),
                        "languageType" to tr(english, "语言", "Language"),
                        "chipType" to tr(english, "芯片（目录）", "Chip (directory)"),
                        "eqBands" to tr(english, "目录标称 EQ 段数", "Directory EQ bands"),
                        "gainRange" to tr(english, "目录增益范围", "Directory gain range"),
                        "qRange" to tr(english, "目录 Q 范围", "Directory Q range"),
                        "supportedFilterTypes" to tr(english, "目录滤波器类型", "Directory filter types"),
                    )) Text("$label: ${catalogMetadata(product, key, english)}")
                    if (product.uuid in snapshot.responseLibraryUuids) {
                        Text("${tr(english, "原始标签", "Original tags")}: ${product.raw["tags"]}", style = MaterialTheme.typography.bodySmall)
                        Text(tr(english, "此记录来自频响库，是频响或目标记录，而非设备型号。", "This library entry is a response or target record, not a device model."), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr(english, "Response 为本地频响库分类，其他资料保留源值；不表示设备或协议能力，也不用于推断参数限制或开放控制能力。", "Response is a local response-library classification; other metadata retains source values. It does not indicate device or protocol capabilities, parameter limits, or enabled controls."), style = MaterialTheme.typography.bodySmall)
                    if (deviceEqBands != null) {
                        Text(tr(english, "当前设备读回 EQ 段数：$deviceEqBands（${deviceName ?: "未提供名称"}）", "Connected device EQ bands read back: $deviceEqBands (${deviceName ?: "name not provided"})"))
                        if (directoryBands != null && directoryBands != deviceEqBands) Text(tr(english,
                            "目录标称 $directoryBands 段 / 设备读回 $deviceEqBands 段；编辑与写入以设备读回为准。",
                            "Directory: $directoryBands bands / device readback: $deviceEqBands bands. Editing and writes use device readback."), color = MaterialTheme.colorScheme.error)
                    }
                    Text(catalogResponseStatus(snapshot, product, english))
                    Text("${tr(english, "参考资产路径", "Reference asset path")}: ${catalogMetadata(product, "freqResponse", english)}", style = MaterialTheme.typography.bodySmall)
                    Text("${tr(english, "来源 UTC", "Source UTC")}: ${snapshot.retrievedAt}", style = MaterialTheme.typography.bodySmall)
                    Text("${tr(english, "资料来源", "Data source")}: ${catalogSourceUrl(snapshot, product)}", style = MaterialTheme.typography.bodySmall)
                    Text("CDN: ${snapshot.cdnBaseUrl}", style = MaterialTheme.typography.bodySmall)
                    Text(tr(english, "来源信息来自快照文件；参考频响或目标资料并非当前设备的实测频响。", "Provenance is supplied by the snapshot file; reference responses or targets are not measurements of the connected device."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Button(onClick = { onUseReference(product) }) { Text(tr(english, "用作参考频响", "Use as reference response")) }
            Text(tr(english, "只选择参考 UUID，不联网、不发送蓝牙命令，也不加载 EQ 预设。", "Selects a reference UUID only: no network, Bluetooth commands, or EQ preset loading."), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text(tr(english, "参考频响", "Reference response"), style = MaterialTheme.typography.titleMedium)
            if (sampled == null) Text(catalogResponseStatus(snapshot, product, english))
            else if (sampled.frequencyHz.isEmpty()) Text(tr(english, "没有可显示频段：原始资料与 20 Hz–20 kHz 无交集。", "No displayable frequency range: the source does not intersect 20 Hz–20 kHz."))
            else {
                Text(if (sampled.normalizationHz == null) tr(english, "原始 SPL dB · 未归一化（不覆盖 500 Hz）", "Original SPL dB · not normalized (no 500 Hz coverage)")
                    else tr(english, "参考 dB · 500 Hz = 0（SunRise 显示归一化）", "Reference dB · 500 Hz = 0 (SunRise display normalization)"), style = MaterialTheme.typography.labelMedium)
                ReferenceLegend(product, english, MaterialTheme.colorScheme.onSurface, false)
                if (comparisonEnabled) ReferenceLegend(comparison, english, MaterialTheme.colorScheme.primary, true)
                CatalogReferencePlot(sampled, if (comparisonEnabled) otherSampled else null, english, Modifier.fillMaxWidth().height(260.dp))
                Text(tr(english, "只画各曲线自身覆盖范围，不外推；测量曲线的条件未知，不代表佩戴、ANC 或音量状态一致，目标曲线并非设备实测。", "Each curve uses only its own coverage, without extrapolation. Measurement conditions are unknown; fit, ANC and volume states may differ. Target curves are not device measurements."), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedButton(onClick = { chooseComparison = true }, enabled = firstReason == null) {
                Text(tr(english, "选择第二条参考频响作双曲线对比", "Choose a second reference response for two-curve comparison"))
            }
            firstReason?.let { Text(tr(english, "双曲线对比不可用：$it", "Two-curve comparison unavailable: $it"), style = MaterialTheme.typography.bodySmall) }
            comparison?.let { other ->
                Text("${tr(english, "第二条参考频响", "Second reference response")}: ${other.name}")
                Text("${tr(english, "语言", "Language")}: ${catalogMetadata(other, "languageType", english)} · UUID: ${other.uuid}", style = MaterialTheme.typography.bodySmall)
                Text(catalogResponseStatus(snapshot, other, english), style = MaterialTheme.typography.bodySmall)
                secondReason?.let { Text(tr(english, "双曲线对比不可用：$it", "Two-curve comparison unavailable: $it"), style = MaterialTheme.typography.bodySmall) }
                if (comparisonEnabled) Text(tr(english, "双曲线共用 dB 轴；各自以 500 Hz 归一化，仅对比形状。", "Two curves share the dB axis, each normalized at 500 Hz; shape comparison only."), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { comparisonUuid = null }) { Text(tr(english, "清除对比", "Clear comparison")) }
            }
        }
    }
    if (chooseComparison) CatalogProductSelector(snapshot, deviceName, english, { comparisonUuid = it.uuid }, { chooseComparison = false }, tr(english, "选择第二个参考 UUID", "Choose the second reference UUID"))
}

@Composable
private fun ReferenceLegend(product: CatalogProduct, english: Boolean, color: Color, dashed: Boolean) {
    Text("${if (dashed) "┄" else "━"} ${product.name} · ${catalogMetadata(product, "languageType", english)}", color = color, style = MaterialTheme.typography.bodySmall)
    Text("UUID: ${product.uuid}", style = MaterialTheme.typography.bodySmall)
}
