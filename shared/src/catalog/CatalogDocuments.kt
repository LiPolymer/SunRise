package ink.lipoly.app.sunrise.catalog

internal const val CATALOG_EXPORT_NAME = "sunrise-moondrop-bt.json"

internal interface CatalogDocuments {
    /** A cancelled picker returns null. Content is always validated by the snapshot codec. */
    suspend fun openImport(): ByteArray?
    /** False means the user cancelled, not that a partial external write succeeded. */
    suspend fun saveExport(bytes: ByteArray, suggestedName: String): Boolean
}
