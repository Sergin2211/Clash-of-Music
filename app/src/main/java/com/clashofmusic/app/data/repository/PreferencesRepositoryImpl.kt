package com.clashofmusic.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.clashofmusic.app.domain.model.AppSettings
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.AudioQuality
import com.clashofmusic.app.domain.model.ThemeMode
import com.clashofmusic.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesRepositoryImpl(context: Context, preferencesName: String = PREFS_NAME) : PreferencesRepository {

    companion object {
        private const val PREFS_NAME = "clash_of_music_prefs"
        private const val KEY_FORMAT = "key_default_format"
        private const val KEY_QUALITY = "key_default_quality"
        private const val KEY_THEME = "key_theme_mode"
        private const val KEY_AUTO_SYNC = "key_auto_sync"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val _settingsFlow = MutableStateFlow(loadSettings())

    private fun loadSettings(): AppSettings {
        val formatStr = prefs.getString(KEY_FORMAT, AudioFormat.MP3.name) ?: AudioFormat.MP3.name
        val qualityStr = prefs.getString(KEY_QUALITY, AudioQuality.BEST_320K.bitrate) ?: "320k"
        val themeStr = prefs.getString(KEY_THEME, ThemeMode.CHERRY.name) ?: ThemeMode.CHERRY.name
        val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)

        val format = try { AudioFormat.valueOf(formatStr) } catch (_: Exception) { AudioFormat.MP3 }
        val quality = AudioQuality.fromBitrate(qualityStr)
        val themeMode = ThemeMode.fromString(themeStr)

        return AppSettings(
            defaultFormat = format,
            defaultQuality = quality,
            themeMode = themeMode,
            autoStorageSync = autoSync
        )
    }

    override fun observeSettings(): Flow<AppSettings> = _settingsFlow.asStateFlow()
    override fun currentSettings(): AppSettings = _settingsFlow.value

    override suspend fun updateDefaultFormat(format: AudioFormat) {
        prefs.edit().putString(KEY_FORMAT, format.name).apply()
        _settingsFlow.value = _settingsFlow.value.copy(defaultFormat = format)
    }

    override suspend fun updateDefaultQuality(quality: AudioQuality) {
        prefs.edit().putString(KEY_QUALITY, quality.bitrate).apply()
        _settingsFlow.value = _settingsFlow.value.copy(defaultQuality = quality)
    }

    override suspend fun updateThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _settingsFlow.value = _settingsFlow.value.copy(themeMode = mode)
    }

    override suspend fun updateAutoStorageSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
        _settingsFlow.value = _settingsFlow.value.copy(autoStorageSync = enabled)
    }
}
