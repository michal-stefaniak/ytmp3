package com.ytmp3

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WaveformExtractor {

    /** Sample rate ffmpeg is asked to decode to -- also used to derive [WaveformData.durationMs]. */
    private const val SAMPLE_RATE_HZ = 8000

    data class WaveformData(val peaks: List<PeakMath.PeakBucket>, val durationMs: Long)

    suspend fun extract(
        context: Context,
        filePath: String,
        bucketCount: Int = 2000
    ): Result<WaveformData> = withContext(Dispatchers.IO) {
        runCatching {
            val result = FFmpegBinary.run(
                context,
                listOf(
                    "-i", filePath,
                    "-f", "s16le",
                    "-ac", "1",
                    "-ar", SAMPLE_RATE_HZ.toString(),
                    "-acodec", "pcm_s16le",
                    "pipe:1"
                )
            )
            if (result.exitCode != 0) {
                throw IllegalStateException("ffmpeg peak extraction failed: ${result.stderr}")
            }
            val pcm = bytesToShorts(result.stdout)
            // Duration is derived from this same ffmpeg decode (sample count / sample rate) rather
            // than from a separate source like MediaMetadataRetriever. MediaMetadataRetriever is a
            // known source of inaccurate durations for MP3s carrying embedded thumbnail/metadata --
            // which every download from this app has (--embed-thumbnail --embed-metadata) -- and
            // WaveformView derives its ms-per-peak-bucket from this duration while SampleExporter
            // cuts the real file with ffmpeg -ss/-to using the same ms values. Two unreconciled
            // duration sources would let those diverge silently, exporting a shifted slice of audio.
            val durationMs = pcm.size.toLong() * 1000L / SAMPLE_RATE_HZ
            WaveformData(PeakMath.reduceToPeaks(pcm, bucketCount), durationMs)
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) shorts[i] = buffer.getShort(i * 2)
        return shorts
    }
}
