package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioEncodingTest {
    @Test fun monoOpusUsesItsSupportedMaximumWithoutUpmixing() {
        val mono = AudioEncoding.arguments(File("in.wav"), File("out.opus"), AudioFormat.OPUS, AudioQuality.BEST_320K, emptyMap(), "pcm_s16le", 1)
        val stereo = AudioEncoding.arguments(File("in.wav"), File("out.opus"), AudioFormat.OPUS, AudioQuality.BEST_320K, emptyMap(), "pcm_s16le", 2)
        assertEquals("256k", mono[mono.indexOf("-b:a") + 1])
        assertEquals("510k", stereo[stereo.indexOf("-b:a") + 1])
        assertFalse(mono.contains("-ac"))
    }
    @Test fun probeReadsMonoChannelCountForEncodingPolicy() {
        assertEquals(1, AudioProbe.decode("""{"streams":[{"codec_type":"audio","codec_name":"aac","channels":1}],"format":{"duration":"6","bit_rate":"128000"}}""").channels)
    }
    @Test fun legacyQualityCannotChangeAutomaticEncoding() {
        for (format in listOf(AudioFormat.MP3, AudioFormat.M4A, AudioFormat.OPUS)) for (quality in AudioQuality.entries) {
            val args = AudioEncoding.arguments(File("in.webm"), File("out.${format.extension}"), format, quality, emptyMap())
            assertEquals(AudioEncoding.arguments(File("in.webm"), File("out.${format.extension}"), format, AudioQuality.BEST_320K, emptyMap()), args)
            if (format != AudioFormat.OPUS) assertFalse(args.contains("-b:a"))
            assertFalse(args.contains("copy"))
            assertEquals(when (format) { AudioFormat.MP3 -> "libmp3lame"; AudioFormat.M4A -> "aac"; else -> "libopus" }, args[args.indexOf("-c:a") + 1])
        }
    }
    @Test fun compatibleSourcesAreCopiedWithoutAnotherLossyEncode() {
        for ((codec, format) in listOf("mp3" to AudioFormat.MP3, "aac" to AudioFormat.M4A, "opus" to AudioFormat.OPUS)) {
            val args = AudioEncoding.arguments(File("source.webm"), File("out.${format.extension}"), format,
                AudioQuality.STANDARD_128K, emptyMap(), codec)
            assertEquals("copy", args[args.indexOf("-c:a") + 1])
            assertFalse(args.contains("-b:a")); assertFalse(args.contains("-q:a")); assertFalse(args.contains("-ar"))
        }
    }
    @Test fun originalCannotAccidentallyGoThroughEncoderPlan() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioEncoding.arguments(File("in"), File("out"), AudioFormat.ORIGINAL, AudioQuality.BEST_320K, emptyMap())
        }
    }
    @Test fun pathsAndMetadataArePassedAsSingleArgumentsWithoutShellInterpolation() {
        val title = "Song ' \" $ & | (live)"
        val file = File("folder with spaces/in.webm")
        val args = AudioEncoding.arguments(file, File("out.mp3"), AudioFormat.MP3, AudioQuality.STANDARD_128K, mapOf("title" to title))
        assertEquals(file.absolutePath, args[args.indexOf("-i") + 1])
        assertTrue(args.contains("title=$title"))
    }
    @Test fun wrongCodecIsRejectedButMeasuredVariableBitratesAreAccepted() {
        assertThrows(IllegalStateException::class.java) { AudioEncoding.validate(AudioProbe("opus", 1000, 128000), AudioFormat.MP3, AudioQuality.STANDARD_128K) }
        AudioEncoding.validate(AudioProbe("mp3", 1000, 128000), AudioFormat.MP3, AudioQuality.BEST_320K)
        AudioEncoding.validate(AudioProbe("mp3", 1000, 320000), AudioFormat.MP3, AudioQuality.BEST_320K)
    }
    @Test fun probeSelectsAudioInsteadOfAttachedPictureAndSupportsContainerBitrate() {
        val info = AudioProbe.decode("""{"streams":[{"codec_type":"video","codec_name":"mjpeg"},{"codec_type":"audio","codec_name":"opus"}],"format":{"duration":"2.5","bit_rate":"128000"}}""")
        assertEquals("opus", info.codec); assertEquals(2500L, info.durationMs); assertEquals("128k", info.quality)
    }
    @Test fun invalidAndEmptyMediaCannotBeMarkedAsVerifiedAudio() {
        assertThrows(IllegalStateException::class.java) { AudioProbe.decode("""{"streams":[],"format":{}}""") }
        assertThrows(IllegalStateException::class.java) { AudioProbe.decode("""{"streams":[{"codec_type":"audio","codec_name":"mp3"}],"format":{"duration":"0","bit_rate":"128000"}}""") }
    }
}
