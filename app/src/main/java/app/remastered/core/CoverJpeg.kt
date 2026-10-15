package app.remastered.core

/** Fitting a playlist cover under Spotify's upload limit ([Catalog.MAX_COVER_BASE64]). */
object CoverJpeg {
    /** Cover edge in pixels: Spotify shows covers at up to 640. */
    const val SIZE_PX = 640

    /** Length of [bytes] bytes once base64-encoded (padded). */
    fun base64Size(bytes: Int) = (bytes + 2) / 3 * 4

    /**
     * Encodes at the best of [qualities] whose result fits in [maxBase64] base64 bytes; null when
     * even the lowest doesn't. [encode] turns a JPEG quality (0–100) into the file's bytes.
     */
    fun fit(maxBase64: Int = Catalog.MAX_COVER_BASE64, qualities: List<Int> = listOf(90, 80, 70, 60, 45, 30), encode: (Int) -> ByteArray): ByteArray? =
        qualities.asSequence().map(encode).firstOrNull { base64Size(it.size) <= maxBase64 }
}
