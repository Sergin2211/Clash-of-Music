package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import java.io.File

/** Online metadata only: never queues audio or changes the library. */
class PlaylistReadCheck(private val instrumentation: Instrumentation, private val url: String) {
    fun run() {
        val report = JSONObject()
        var success = false
        try {
            val source = YouTubeExtractorDataSource(playlistRecovery = {
                com.clashofmusic.app.data.extractor.YtDlpPlaylistReader(instrumentation.targetContext).read(it)
            })
            val extractor = ServiceList.YouTube.getPlaylistExtractor(source.normalizePlaylistUrl(url))
            extractor.fetchPage()
            val info = PlaylistInfo.getInfo(extractor)
            report.put("title", info.name).put("declaredCount", info.streamCount)
            val pages = JSONArray()
            pages.put(JSONObject().put("items", info.relatedItems.size).put("errors", JSONArray(info.errors.map { it.toString() })))
            var next = info.nextPage
            var count = info.relatedItems.size
            while (Page.isValid(next)) {
                check(pages.length() < 30)
                val page = extractor.getPage(next)
                count += page.items.size
                pages.put(JSONObject().put("items", page.items.size).put("errors", JSONArray(page.errors.map { android.util.Log.getStackTraceString(it) })))
                next = page.nextPage
            }
            report.put("pages", pages).put("readableCount", count)
            val parsed = runBlocking { source.extractPlaylist(url).getOrThrow() }
            check(parsed.trackCount >= count)
            report.put("appCount", parsed.trackCount)
            report.put("warning", parsed.readWarning)
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        report.put("success", success)
        File(instrumentation.targetContext.filesDir, "playlist_read_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
