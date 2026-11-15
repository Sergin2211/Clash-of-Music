package com.clashofmusic.app.data.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.database.StandaloneDatabaseProvider
import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import com.clashofmusic.app.domain.model.AudioFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Only the custom radio scheme goes through this cache. Local songs bypass it entirely. */
@androidx.annotation.OptIn(UnstableApi::class)
class RadioAudioCache(
    context: Context,
    private val extractor: YouTubeExtractorDataSource,
    cacheDirectory: File? = null,
    private val maxBytes: Long = RadioWindow.MAX_BYTES,
    private val resolve: suspend (String) -> com.clashofmusic.app.data.extractor.ResolvedAudioStream? = { extractor.getBestAudioStream(it, AudioFormat.ORIGINAL) },
    private val upstream: DataSource.Factory? = null
) {
    private val appContext = context.applicationContext
    val directory = cacheDirectory ?: File(appContext.cacheDir, "radio_audio")
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var allowed = emptySet<String>()
    private val urls = ConcurrentHashMap<String, Pair<Long, String>>()
    private val representations = ConcurrentHashMap<String, String>()
    private val playbackPermit = java.util.concurrent.Semaphore(1)
    private var prefetch: Job? = null
    @Volatile private var writer: CacheWriter? = null
    private val usage = MutableStateFlow(0L)
    val bytes = usage.asStateFlow()
    // Reserve two 512 KiB in-flight files (one playback loader, one serial prefetch writer).
    // Committed audio + these temporary writes stays within the advertised audio budget.
    private val lru = LeastRecentlyUsedCacheEvictor(maxBytes - 1024 * 1024L)
    private val evictor = object : CacheEvictor {
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() = lru.onCacheInitialized()
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (key !in allowed) throw Cache.CacheException("La sesión de radio terminó o la pista salió del búfer")
            lru.onStartFile(cache, key, position, length)
        }
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            lru.onSpanAdded(cache, span)
            if (span.key !in allowed) cache.removeSpan(span)
            usage.value = cache.cacheSpace
        }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { lru.onSpanRemoved(cache, span); usage.value = cache.cacheSpace }
        override fun onSpanTouched(cache: Cache, old: CacheSpan, new: CacheSpan) = lru.onSpanTouched(cache, old, new)
    }
    // Lazy: index loading and stale-session deletion happen on a media/prefetch IO thread.
    private val cacheDelegate = lazy {
        val db = StandaloneDatabaseProvider(appContext)
        SimpleCache.delete(directory, db)
        SimpleCache(directory, evictor, db)
    }
    private val cache get() = cacheDelegate.value
    private val initialization = io.launch {
        // Retire only the previous implementation's known temporary preview files.
        val temporaryName = Regex("[A-Za-z0-9_-]{11}_temp\\.(webm|m4a|opus|aac|mp3|ogg)")
        File(appContext.cacheDir, "previews").listFiles().orEmpty()
            .filter { it.isFile && temporaryName.matches(it.name) }.forEach { it.delete() }
        try { cache } catch (_: Exception) { /* Expose initialization errors when playback opens. */ }
    }
    private fun cacheFactory(): CacheDataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0")
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val resolving = ResolvingDataSource.Factory(upstream ?: http, ResolvingDataSource.Resolver { spec ->
            val id = spec.key ?: spec.uri.lastPathSegment ?: throw IOException("Pista sin identificador")
            if (id !in allowed) throw IOException("Sesión terminada")
            val now = System.currentTimeMillis()
            val url = urls[id]?.takeIf { now - it.first < 5 * 60_000 }?.second ?: runBlocking(Dispatchers.IO) {
                val stream = resolve(id) ?: throw IOException("No se pudo obtener el audio. Reintenta con conexión a Internet.")
                val representation = "${stream.extension}:${stream.bitrate}:${Uri.parse(stream.url).getQueryParameter("itag")}"
                val prior = representations.putIfAbsent(id, representation)
                if (prior != null && prior != representation) throw IOException("La fuente de audio cambió. Inicia otra sesión de radio.")
                if (id !in allowed) throw IOException("Sesión terminada")
                stream.url
            }.also { urls[id] = now to it }
            spec.withUri(Uri.parse(url))
        })
        return CacheDataSource.Factory().setCache(cache).setCacheKeyFactory { it.key ?: it.uri.lastPathSegment.orEmpty() }
            .setUpstreamDataSourceFactory(resolving)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(512 * 1024L))
    }

    val dataSourceFactory = DataSource.Factory {
        // DefaultDataSource opens file/content URIs without caching user-owned audio.
        object : DataSource {
            private var delegate: DataSource? = null
            private var hasPermit = false
            private val listeners = mutableListOf<TransferListener>()
            override fun addTransferListener(listener: TransferListener) { listeners.add(listener); delegate?.addTransferListener(listener) }
            override fun open(dataSpec: DataSpec): Long {
                if (dataSpec.uri.scheme == "clashradio") {
                    try { playbackPermit.acquire(); hasPermit = true }
                    catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw java.io.InterruptedIOException() }
                }
                try {
                val selected = if (dataSpec.uri.scheme == "clashradio") cacheFactory().createDataSource()
                    else DefaultDataSource.Factory(appContext).createDataSource()
                delegate = selected
                listeners.forEach(selected::addTransferListener)
                return selected.open(if (dataSpec.uri.scheme == "clashradio") dataSpec.buildUpon()
                    .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build() else dataSpec)
                } catch (e: Exception) { close(); throw e }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            override fun getUri(): Uri? = delegate?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
            override fun close() { try { delegate?.close() } finally { delegate = null; if (hasPermit) { hasPermit = false; playbackPermit.release() } } }
        }
    }

    fun updateWindow(ids: List<String>, ahead: List<String>) {
        synchronized(lock) {
            val next = ids.take(RadioWindow.MAX_TRACKS).toSet()
            if (next == allowed && prefetch?.isActive == true) return
            allowed = next
            writer?.cancel()
            val previous = prefetch
            previous?.cancel()
            prefetch = io.launch {
                previous?.join()
                if (allowed != next) return@launch
                if (next.isEmpty() && !cacheDelegate.isInitialized()) return@launch
                cache.keys.filterNot { it in next }.forEach { cache.removeResource(it); urls.remove(it) }
                urls.keys.filterNot { it in next }.forEach { urls.remove(it); representations.remove(it) }
                delay(1500) // Current audio gets the connection before speculative buffering starts.
                for (id in ahead.take(RadioWindow.AHEAD)) {
                    ensureActive()
                    if (id !in allowed) continue
                    // At most the first 2 MiB per upcoming track; playback fetches remaining blocks on demand.
                    val spec = DataSpec.Builder().setUri("clashradio://track/$id").setKey(id)
                        .setLength(2 * 1024 * 1024L).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
                    val current = CacheWriter(cacheFactory().createDataSource(), spec, null, null)
                    writer = current
                    try { ensureActive(); current.cache() } catch (e: CancellationException) { throw e }
                    catch (_: IOException) { /* A short source or offline connection must not stop the current song. */ }
                    finally { if (writer === current) writer = null }
                }
                usage.value = cache.cacheSpace
            }
        }
    }

    fun clear() { urls.clear(); representations.clear(); updateWindow(emptyList(), emptyList()) }
    fun refreshSource(songId: String) { urls.remove(songId) }
    suspend fun awaitIdle() { prefetch?.join() }
    fun cachedKeys(): Set<String> = if (cacheDelegate.isInitialized()) cache.keys else emptySet()
    suspend fun release() {
        initialization.join()
        clear(); awaitIdle()
        if (cacheDelegate.isInitialized()) cache.release()
        io.cancel()
    }
}
