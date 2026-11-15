package com.clashofmusic.app.presentation.components

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache stores only 24 colors, never images; bitmap generation invalidates replaced artwork. */
private object ArtworkTintCache {
    private val values = LinkedHashMap<String, Int>(24, .75f, true)
    @Synchronized fun get(key: String) = values[key]
    @Synchronized fun put(key: String, value: Int) {
        values[key] = value
        while (values.size > 24) values.remove(values.keys.first())
    }
}

@Composable
internal fun rememberArtworkTint(source: String?, bitmap: Bitmap?): State<Int?> {
    val color = remember(source) { mutableStateOf<Int?>(null) }
    LaunchedEffect(source, bitmap) {
        color.value = null
        if (source == null || bitmap == null) return@LaunchedEffect
        val key = "$source:${bitmap.generationId}"
        val cached = ArtworkTintCache.get(key)
        if (cached != null) { color.value = cached; return@LaunchedEffect }
        val extracted = withContext(Dispatchers.Default) {
            try {
                val small = Bitmap.createScaledBitmap(bitmap, 40, 40, true)
                try {
                    val pixels = IntArray(1600)
                    small.getPixels(pixels, 0, 40, 0, 0, 40, 40)
                    ArtworkColorMath.dominant(pixels)
                } finally { if (small !== bitmap) small.recycle() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        if (extracted != null) ArtworkTintCache.put(key, extracted)
        color.value = extracted
    }
    return color
}
