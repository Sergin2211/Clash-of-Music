package com.clashofmusic.app.data.local

import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Supplemental app metadata: Room schema and audio files remain untouched; older APKs can still open the library. */
class GenreProfileStore(private val file: File) {
    private val lock = Any()
    private val mutableProfiles = MutableStateFlow<Map<String, GenreProfile>>(emptyMap())
    val profiles = mutableProfiles.asStateFlow()
    @Volatile private var loaded = false
    fun load() = synchronized(lock) {
        if (loaded) return@synchronized
        val backup = File(file.path + ".bak")
        val candidates = listOf(file, backup).filter { it.exists() }
        if (candidates.isNotEmpty()) {
            mutableProfiles.value = candidates.firstNotNullOfOrNull { candidate -> runCatching {
                require(candidate.length() <= 20 * 1024 * 1024)
                val json = JSONObject(candidate.readText()); require(json.getInt("version") == 1)
                val rows = json.getJSONObject("profiles")
                rows.keys().asSequence().associateWith { GenreProfileCodec.decode(rows.getString(it)) }
            }.getOrNull() } ?: error("No se pudieron leer los géneros guardados. Se conservaron sus archivos.")
        }
        loaded = true
    }
    fun get(id: String): GenreProfile? = synchronized(lock) { mutableProfiles.value[id] }
    fun put(id: String, profile: GenreProfile?) = synchronized(lock) {
        load()
        require(id.isNotBlank() && id.length <= 500)
        val valid = profile?.let { GenreProfileCodec.decode(GenreProfileCodec.encode(it)) }
        val next = if (valid == null) mutableProfiles.value - id else mutableProfiles.value + (id to valid)
        fun encoded(values: Map<String, GenreProfile>) = JSONObject().put("version", 1).put("profiles", JSONObject().apply {
            values.forEach { (key, value) -> put(key, GenreProfileCodec.encode(value)) }
        }).toString()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { out -> out.write(encoded(next).toByteArray(Charsets.UTF_8)); out.fd.sync() }
        if (file.exists()) {
            // Use validated memory, so recovering from .bak never replaces it with a damaged primary file.
            val backupTemp = File(file.path + ".bak.tmp")
            backupTemp.outputStream().use { out -> out.write(encoded(mutableProfiles.value).toByteArray(Charsets.UTF_8)); out.fd.sync() }
            Files.move(backupTemp.toPath(), File(file.path + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        mutableProfiles.value = next
    }
    fun restoreMissing(data: VaultData) = synchronized(lock) {
        load()
        data.songs.forEach { song ->
            if (get(song.songId) == null) song.genreProfileJson?.let { put(song.songId, GenreProfileCodec.decode(it)) }
        }
    }
}
