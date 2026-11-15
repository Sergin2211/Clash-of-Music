package com.clashofmusic.app.presentation.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.clashofmusic.app.domain.model.DownloadedSong
import java.io.File

object ShareHelper {

    private const val TAG = "ShareHelper"

    /**
     * Shares a downloaded song via Android Share Sheet using FileProvider.
     * Includes the audio file and formatted metadata.
     */
    fun shareSong(context: Context, song: DownloadedSong) {
        try {
            val file = File(song.filePath)
            val publicFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "ClashOfMusic/${file.name}"
            )
            val downloadFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ClashOfMusic/${file.name}"
            )

            val targetFile = when {
                file.exists() && file.length() > 0 -> file
                publicFile.exists() && publicFile.length() > 0 -> publicFile
                downloadFile.exists() && downloadFile.length() > 0 -> downloadFile
                else -> null
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                if (targetFile != null && targetFile.exists()) {
                    val contentUri: Uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        targetFile
                    )
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "🎵 Escucha '${song.title}' de ${song.artist} en Clash of Music"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                    val text = buildString {
                        append("🎵 '${song.title}' de ${song.artist}")
                        if (song.fileUri.isNotBlank() && song.fileUri.startsWith("http")) {
                            append("\n").append(song.fileUri)
                        }
                    }
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            }

            val chooser = Intent.createChooser(shareIntent, "Compartir canción").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing song: ${e.message}", e)
            Toast.makeText(context, "No se pudo compartir la canción", Toast.LENGTH_SHORT).show()
        }
    }
}
