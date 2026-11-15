package com.clashofmusic.app.data.media

/** Album artwork wins over broad catalog matches; missing images have independent alternatives. */
internal object ArtworkCandidates {
    fun forDownload(primary: String?, albumFallback: String?, catalog: String?, songId: String, hasAlbum: Boolean): List<String> {
        val preferred = if (hasAlbum) listOf(primary, albumFallback, catalog) else listOf(catalog, primary, albumFallback)
        val video = if (songId.matches(Regex("[a-zA-Z0-9_-]{11}")))
            listOf("https://i.ytimg.com/vi/$songId/hqdefault.jpg", "https://i.ytimg.com/vi/$songId/mqdefault.jpg")
        else emptyList()
        return (preferred + video).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    }
}
