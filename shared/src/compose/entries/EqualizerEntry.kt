package ink.lipoly.app.sunrise.compose.entries

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ink.lipoly.app.sunrise.catalog.*
import ink.lipoly.app.sunrise.controls.peq.ParamEqEditState
import ink.lipoly.app.sunrise.controls.peq.ParamEqEditor
import ink.lipoly.app.sunrise.controls.peq.ParamEqScreen
import ink.lipoly.app.sunrise.settings.UiSettings

/** 根级 editor 的页面容器；导航与参考选择不拥有或发送设备参数。 */
@Composable
internal fun EqualizerEntry(
    editor: ParamEqEditor?,
    state: ParamEqEditState?,
    enabled: Boolean,
    snapshot: CatalogSnapshot?,
    reference: CatalogReferenceSelection,
    settings: UiSettings,
    onChooseReference: () -> Unit,
    onResetReference: () -> Unit,
    onChooseTarget: () -> Unit,
    onClearTarget: () -> Unit,
    onSettingsChange: (UiSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetProduct = settings.targetProductUuid?.let { snapshot?.productsByUuid?.get(it) }
    val targetResponse = targetProduct?.freqResponse?.let { snapshot?.responsesByPath?.get(it) }
    ParamEqScreen(
        editor = editor,
        state = state,
        enabled = enabled,
        referenceProduct = reference.product,
        referenceResponse = reference.response,
        referenceSource = snapshot?.let { current -> reference.product?.let { catalogSourceUrl(current, it) } },
        referenceRetrievedAt = snapshot?.retrievedAt,
        referenceResponseHash = reference.product?.freqResponse?.let { snapshot?.responseHashesByPath?.get(it) },
        showReferenceResponse = settings.showReferenceResponse,
        includeResponsePreGain = settings.includeResponsePreGain,
        targetProduct = targetProduct,
        targetResponse = targetResponse,
        targetProductUuid = settings.targetProductUuid,
        targetResponseHash = targetProduct?.freqResponse?.let { snapshot?.responseHashesByPath?.get(it) },
        showTargetResponse = settings.showTargetResponse,
        onChooseTarget = onChooseTarget,
        onClearTarget = onClearTarget,
        onTargetResponseChange = { onSettingsChange(settings.copy(showTargetResponse = it)) },
        onChooseReference = onChooseReference,
        onResetReference = onResetReference,
        onReferenceResponseChange = { onSettingsChange(settings.copy(showReferenceResponse = it)) },
        onResponsePreGainChange = { onSettingsChange(settings.copy(includeResponsePreGain = it)) },
        modifier = modifier,
        filterSelectionEnabled = false,
    )
}
