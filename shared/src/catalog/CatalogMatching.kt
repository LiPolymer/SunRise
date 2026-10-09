package ink.lipoly.app.sunrise.catalog

internal fun catalogSourceUrl(snapshot: CatalogSnapshot, product: CatalogProduct): String =
    if (product.uuid in snapshot.responseLibraryUuids) snapshot.responseLibraryUrl else snapshot.catalogueUrl

internal fun matchesCatalogDevice(snapshot: CatalogSnapshot?, name: String?): Boolean {
    val normalizedName = normalizeCatalogName(name) ?: return false
    return snapshot?.productsByNormalizedName?.get(normalizedName)?.any { it.type == "BT" } == true
}

internal data class CatalogReferenceSelection(
    val product: CatalogProduct?,
    val response: CatalogResponse?,
    val invalidManualBinding: Boolean,
)

internal fun resolveCatalogReference(
    snapshot: CatalogSnapshot?,
    deviceName: String?,
    manualUuid: String?,
): CatalogReferenceSelection {
    val manual = manualUuid?.let { snapshot?.productsByUuid?.get(it) }
    val product = manual ?: snapshot?.let { catalogue ->
        normalizeCatalogName(deviceName)?.let { name ->
            catalogue.productsByNormalizedName[name]?.asSequence()?.filter { it.type == "BT" }?.minWithOrNull(
                compareBy<CatalogProduct> { if (catalogue.responseFor(it) is CatalogResponse.Ready) 0 else 1 }
                    .thenBy { catalogLanguagePriority(it.languageType) }
                    .thenBy { it.uuid },
            )
        }
    }
    return CatalogReferenceSelection(
        product = product,
        response = product?.let { snapshot?.responseFor(it) },
        invalidManualBinding = snapshot != null && manualUuid != null && manual == null,
    )
}

internal fun orderedCatalogProducts(
    snapshot: CatalogSnapshot,
    deviceName: String?,
): List<CatalogProduct> {
    val selectedName = normalizeCatalogName(deviceName)
    return snapshot.products.sortedWith(
        compareBy<CatalogProduct> { if (normalizeCatalogName(it.name) == selectedName) 0 else 1 }
            .thenBy { normalizeCatalogName(it.name) }
            .thenBy { catalogLanguagePriority(it.languageType) }
            .thenBy { it.uuid },
    )
}

private fun catalogLanguagePriority(language: String?): Int = when (language) {
    "zh-CN" -> 0
    null, "" -> 1
    else -> 2
}

private fun CatalogSnapshot.responseFor(product: CatalogProduct): CatalogResponse? =
    product.freqResponse?.let(responsesByPath::get)
