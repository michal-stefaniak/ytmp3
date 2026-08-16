package com.ytmp3

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WaveformExtractor {

    suspend fun extract(
        context: Context,
        filePath: String,
        bucketCount: Int = 2000
    ): Result<List<PeakMath.PeakBucket>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = FFmpegBinary.run(
                context,
                listOf(
                    "-i", filePath,
                    "-f", "s16le",
                    "-ac", "1",
                    "-ar", "8000",
                    "-acodec", "pcm_s16le",
                    "pipe:1"
                )
            )
            if (result.exitCode != 0) {
                throw IllegalStateException("ffmpeg peak extraction failed: ${result.stderr}")
            }
            val pcm = bytesToShorts(result.stdout)
            PeakMath.reduceToPeaks(pcm, bucketCount)
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) shorts[i] = buffer.getShort(i * 2)
        return shorts
    }
}
