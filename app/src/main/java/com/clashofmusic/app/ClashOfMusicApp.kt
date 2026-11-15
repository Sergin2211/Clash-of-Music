package com.clashofmusic.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.clashofmusic.app.di.ServiceLocator

class ClashOfMusicApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(visibility)
        try {
            // Initialize lightweight ServiceLocator. Heavy native binaries (yt-dlp, ffmpeg)
            // are initialized lazily in background Coroutines to guarantee 0% jank and fast startup.
            ServiceLocator.init(this)
            Log.d("ClashOfMusicApp", "Clash of Music application initialized.")
        } catch (e: Exception) {
            Log.e("ClashOfMusicApp", "Error during app init: ${e.message}", e)
        }
    }
    val visibility = com.clashofmusic.app.services.AppVisibility()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("album_art_cache"))
                    .maxSizeBytes(80L * 1024 * 1024)
                    .build()
            }
            .allowHardware(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }
}
