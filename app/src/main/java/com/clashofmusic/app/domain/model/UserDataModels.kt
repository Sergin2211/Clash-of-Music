package com.clashofmusic.app.domain.model

data class FavoriteSong(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val url: String,
    val savedAt: Long = System.currentTimeMillis()
)

data class SearchHistoryItem(
    val id: Long = 0,
    val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ThemeMode(val displayName: String, val subtitle: String) {
    ELIXIR("Elixir", "Carbón y amatista"),
    CHERRY("Cherry", "Carbón y rosa");

    companion object {
        fun fromString(value: String): ThemeMode {
            return when (value.uppercase()) {
                "CHERRY" -> CHERRY
                "ELIXIR" -> ELIXIR
                else -> CHERRY
            }
        }
    }
}

data class AppSettings(
    val defaultFormat: AudioFormat = AudioFormat.MP3,
    val defaultQuality: AudioQuality = AudioQuality.BEST_320K,
    val themeMode: ThemeMode = ThemeMode.CHERRY,
    val musicFolder: String = "/Music/ClashOfMusic",
    val autoStorageSync: Boolean = true,
    val embedThumbnails: Boolean = true,
    val embedMetadata: Boolean = true
)
