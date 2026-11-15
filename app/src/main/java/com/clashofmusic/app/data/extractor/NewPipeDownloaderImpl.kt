package com.clashofmusic.app.data.extractor

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NewPipeDownloaderImpl private constructor(builder: OkHttpClient.Builder) : Downloader() {

    private val client: OkHttpClient = builder
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cookies = ConcurrentHashMap<String, String>()

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private const val YOUTUBE_RESTRICTED_MODE_COOKIE_KEY = "youtube_restricted_mode_key"
        private const val YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000"
        private const val YOUTUBE_DOMAIN = "youtube.com"

        @Volatile
        private var instance: NewPipeDownloaderImpl? = null

        fun init(builder: OkHttpClient.Builder? = null): NewPipeDownloaderImpl {
            return instance ?: synchronized(this) {
                instance ?: NewPipeDownloaderImpl(builder ?: OkHttpClient.Builder()).also {
                    instance = it
                }
            }
        }

        fun getInstance(): NewPipeDownloaderImpl {
            return instance ?: init()
        }
    }

    fun getCookies(url: String): String {
        val list = mutableListOf<String>()
        if (url.contains(YOUTUBE_DOMAIN)) {
            cookies[YOUTUBE_RESTRICTED_MODE_COOKIE_KEY]?.let { list.add(it) }
        }
        return list.joinToString("; ")
    }

    fun setCookie(key: String, value: String) {
        cookies[key] = value
    }

    fun removeCookie(key: String) {
        cookies.remove(key)
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody: RequestBody? = if (dataToSend != null) {
            dataToSend.toRequestBody(null, 0, dataToSend.size)
        } else if (httpMethod.equals("POST", ignoreCase = true) || httpMethod.equals("PUT", ignoreCase = true)) {
            ByteArray(0).toRequestBody(null, 0, 0)
        } else {
            null
        }

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        val cookieHeader = getCookies(url)
        if (cookieHeader.isNotEmpty()) {
            requestBuilder.addHeader("Cookie", cookieHeader)
        }

        headers.forEach { (headerName, headerValueList) ->
            requestBuilder.removeHeader(headerName)
            headerValueList.forEach { headerValue ->
                requestBuilder.addHeader(headerName, headerValue)
            }
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested by service", url)
            }

            val responseBodyToReturn = response.body?.string() ?: ""
            val latestUrl = response.request.url.toString()

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBodyToReturn,
                latestUrl
            )
        }
    }
}
