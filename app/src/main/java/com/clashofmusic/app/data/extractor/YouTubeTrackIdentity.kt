package com.clashofmusic.app.data.extractor

import com.clashofmusic.app.domain.model.GenreNames

data class YouTubeTrackIdentity(val title: String, val artist: String) {
    companion object {
        private val presentation = Regex("(?i)【(?:mv|music video|official|lyric(?:s)?|audio|hd|4k)】|\\[(?:mv|official[^]]*|lyric[^]]*|audio[^]]*)]|\\((?:official|lyrics?|audio|music video)[^)]*\\)")
        private val separators = Regex("\\s+[-–—|]\\s+")
        private val by = Regex("(?i)\\bby\\s+([^|–—]+?)(?=\\s+-\\s+|\\s*\\||$)")
        private val quoted = Regex("[\"「『]([^\"」』]+)[\"」』]")
        private fun clean(value: String) = presentation.replace(value, " ").replace(Regex("\\s+"), " ")
            .trim(' ', '|', '"', '「', '」', '『', '』').replace(Regex("\\s+[-–—]$"), "").trim()
        fun artistKey(value: String) = GenreNames.key(value.replace(Regex("(?i)\\s*[-–]\\s*Topic$|VEVO$"), "").trim())
        fun parse(raw: String, channel: String): YouTubeTrackIdentity {
            val uploader = channel.replace(Regex("(?i)\\s*[-–]\\s*Topic$|VEVO$"), "").trim()
            val value = clean(raw)
            fun result(title: String, artist: String) = YouTubeTrackIdentity(clean(title).ifBlank { value }, clean(artist).ifBlank { uploader })
            by.find(value)?.let { credit ->
                val artist = credit.groupValues[1].trim()
                val before = value.take(credit.range.first).trim()
                val quote = quoted.findAll(before).lastOrNull()?.groupValues?.get(1)
                val title = quote
                    ?: before.substringAfterLast('|').replace(Regex("(?i)^.*?\\b(?:ending|opening)\\s+(?:full\\s+)?"), "")
                if (artist.length in 1..100 && title.isNotBlank() && (artistKey(artist) == artistKey(uploader) || quote != null ||
                    Regex("(?i)\\b(ending|opening)\\b").containsMatchIn(before))) return result(title, artist)
            }
            val slash = Regex("\\s[/／]\\s").findAll(value).lastOrNull()?.range?.first ?: -1
            if (slash >= 0) {
                val left = value.take(slash).trim()
                val right = value.substring(slash).replaceFirst(Regex("^\\s*[/／]\\s*"), "").trim()
                val creditedArtist = separators.split(right).first()
                if (artistKey(creditedArtist) == artistKey(uploader)) return result(left, uploader)
            }
            val bracket = Regex("^【([^】]+)】\\s*(.+)$").matchEntire(value)
            if (bracket != null && artistKey(bracket.groupValues[1]) == artistKey(uploader)) return result(bracket.groupValues[2], uploader)
            val parts = separators.split(value)
            if (parts.size > 1) {
                if (artistKey(parts.first()) == artistKey(uploader)) return result(parts.drop(1).joinToString(" - "), uploader)
                if (artistKey(parts.last()) == artistKey(uploader)) return result(parts.dropLast(1).joinToString(" - "), uploader)
                // Explicit title credits from lyric channels are useful; anime/programme prefixes aren't artists.
                val prefix = parts.first()
                val narrative = Regex("(?i)\\b(bleach|ending|opening|part|season|episode|full|anime|trailer)\\b|:")
                if (!narrative.containsMatchIn(prefix) && prefix.length <= 70 &&
                    Regex("(?i)lyrics?|7clouds|official audio|music|songs|vevo").containsMatchIn(channel)) {
                    return result(parts.drop(1).joinToString(" - "), prefix)
                }
            }
            // Without a trustworthy artist credit, preserve the channel and the complete title.
            return result(value, uploader)
        }
    }
}
