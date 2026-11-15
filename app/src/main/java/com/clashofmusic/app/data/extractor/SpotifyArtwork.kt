package com.clashofmusic.app.data.extractor

import org.json.JSONObject

/** Spotify embed exposes album covers in either schema; the largest usable source wins. */
internal object SpotifyArtwork {
    fun bestUrl(entity: JSONObject): String? {
        val sources = listOfNotNull(entity.optJSONObject("coverArt")?.optJSONArray("sources"),
            entity.optJSONObject("visualIdentity")?.optJSONArray("image"))
        return sources.flatMap { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }
            .mapNotNull { item ->
                val url = item.optString("url").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: return@mapNotNull null
                val width = maxOf(item.optLong("width"), item.optLong("maxWidth")).coerceIn(0, 50000)
                val height = maxOf(item.optLong("height"), item.optLong("maxHeight")).coerceIn(0, 50000)
                url to width * height
            }.maxByOrNull { it.second }?.first
    }
}
