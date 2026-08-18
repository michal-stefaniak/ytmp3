package com.ytmp3

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            val reducer = StreamingPeakReducer(bucketCount)
            val decoder = Pcm16LeDecoder()
            val result = FFmpegBinary.streamPcm(
                context,
                listOf(
                    "-i", filePath,
                    "-f", "s16le",
                    "-ac", "1",
                    "-ar", SAMPLE_RATE_HZ.toString(),
                    "-acodec", "pcm_s16le",
                    "pipe:1"
                )
            ) { bytes, offset, length ->
                decoder.accept(bytes, offset, length, reducer::accept)
            }
            if (result.exitCode != 0) {
                throw IllegalStateException("ffmpeg peak extraction failed: ${result.stderr}")
            }
            if (!decoder.finish()) {
                throw IllegalStateException("ffmpeg peak extraction produced a truncated PCM sample")
            }
            // Duration is derived from this same ffmpeg decode (sample count / sample rate) rather
            // than from a separate source like MediaMetadataRetriever. MediaMetadataRetriever is a
            // known source of inaccurate durations for MP3s carrying embedded thumbnail/metadata --
            // which can otherwise confuse Android's metadata readers -- and
            // WaveformView derives its ms-per-peak-bucket from this duration while SampleExporter
            // cuts the real file with ffmpeg -ss/-to using the same ms values. Two unreconciled
            // duration sources would let those diverge silently, exporting a shifted slice of audio.
            val durationMs = reducer.acceptedSampleCount * 1000L / SAMPLE_RATE_HZ
            WaveformData(reducer.finish(), durationMs)
        }
    }
}
