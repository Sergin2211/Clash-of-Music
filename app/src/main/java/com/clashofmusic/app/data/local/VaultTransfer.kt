package com.clashofmusic.app.data.local

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Document-picker input is bounded and validated before touching the catalog or database. */
internal object VaultTransfer {
    const val MAX_BYTES = 10 * 1024 * 1024

    fun read(input: InputStream): VaultData {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "El respaldo supera el límite de 10 MB" }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        return validate(VaultCodec.decode(text))
    }

    fun validate(data: VaultData): VaultData {
        require(data.songs.size <= 50000 && data.playlists.size <= 10000) { "El respaldo contiene demasiadas entradas" }
        data.songs.forEach { song ->
            require(song.fileName != "." && song.fileName != ".." &&
                song.fileName.none { it == '/' || it == '\\' || it == '\u0000' }) { "Nombre de archivo inválido en el respaldo" }
        }
        val owners = mutableMapOf<String, String>()
        data.songs.forEach { song ->
            listOf(song.id, song.songId).forEach { alias ->
                val owner = owners.putIfAbsent(alias, song.id)
                require(owner == null || owner == song.id) { "Identidades de canciones ambiguas en el respaldo" }
            }
        }
        return data
    }
}
