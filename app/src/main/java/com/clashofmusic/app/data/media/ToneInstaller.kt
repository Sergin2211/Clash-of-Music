package com.clashofmusic.app.data.media

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.clashofmusic.app.domain.model.ToneTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

interface ToneSettings {
    fun canWrite(): Boolean
    fun current(target: ToneTarget): Uri?
    fun set(target: ToneTarget, uri: Uri?)
    fun matches(target: ToneTarget, uri: Uri): Boolean = current(target) == uri
}

class AndroidToneSettings(private val context: Context) : ToneSettings {
    private fun type(target: ToneTarget) = if (target == ToneTarget.RINGTONE) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM
    override fun canWrite() = Settings.System.canWrite(context)
    override fun current(target: ToneTarget): Uri? = RingtoneManager.getActualDefaultRingtoneUri(context, type(target))
    override fun set(target: ToneTarget, uri: Uri?) = RingtoneManager.setActualDefaultRingtoneUri(context, type(target), uri)
    override fun matches(target: ToneTarget, uri: Uri): Boolean {
        val actual = current(target) ?: return false
        fun canonical(value: Uri) = runCatching { context.contentResolver.canonicalize(value) }.getOrNull() ?: value
        return actual == uri || canonical(actual) == canonical(uri)
    }
}

/** Publishes a unique standalone tone; never deletes a user's previous ringtone. */
class ToneInstaller(private val context: Context, private val settings: ToneSettings = AndroidToneSettings(context)) {
    fun canWrite() = settings.canWrite()

    suspend fun install(file: File, title: String, artist: String, target: ToneTarget): Uri = withContext(NonCancellable + Dispatchers.IO) {
        check(settings.canWrite()) { "Permite modificar ajustes del sistema para establecer el tono." }
        val previous = settings.current(target)
        val uri = publish(file, title, artist, target)
        try {
            settings.set(target, uri)
            check(settings.matches(target, uri)) { "Android no confirmó el cambio de tono." }
            uri
        } catch (error: Exception) {
            if (runCatching { settings.matches(target, uri) }.getOrDefault(false)) {
                runCatching { settings.set(target, previous) }
            }
            // If verification/rollback is unavailable, keep the valid file rather than leave a broken default.
            if (!runCatching { settings.matches(target, uri) }.getOrDefault(true)) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            throw error
        }
    }

    private fun publish(file: File, title: String, artist: String, target: ToneTarget): Uri {
        val resolver = context.contentResolver
        val directory = if (target == ToneTarget.RINGTONE) Environment.DIRECTORY_RINGTONES else Environment.DIRECTORY_ALARMS
        val name = "Clash_${target.name.lowercase()}_${UUID.randomUUID()}.mp3"
        var legacy: File? = null
        var inserted: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.IS_MUSIC, false)
                put(MediaStore.Audio.Media.IS_RINGTONE, target == ToneTarget.RINGTONE)
                put(MediaStore.Audio.Media.IS_ALARM, target == ToneTarget.ALARM)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "$directory/ClashOfMusic/")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val folder = File(Environment.getExternalStoragePublicDirectory(directory), "ClashOfMusic")
                    check(folder.isDirectory || folder.mkdirs())
                    legacy = File(folder, name)
                    @Suppress("DEPRECATION")
                    put(MediaStore.Audio.Media.DATA, legacy!!.absolutePath)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: error("No se pudo guardar el tono.")
            inserted = uri
            (resolver.openOutputStream(uri, "w") ?: error("No se pudo escribir el tono.")).use { output -> file.inputStream().use { it.copyTo(output) } }
            fun digest(input: java.io.InputStream): ByteArray = input.use { stream ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                hash.digest()
            }
            check(digest(file.inputStream()).contentEquals(digest(resolver.openInputStream(uri) ?: error("No se pudo verificar el tono."))))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
            }
            return uri
        } catch (error: Exception) {
            inserted?.let { runCatching { resolver.delete(it, null, null) } }
            legacy?.delete()
            throw error
        }
    }
}
