package com.clashofmusic.app.data.extractor

object YouTubeMusicMix {
    fun url(videoId: String): String {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        return "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId"
    }
}
