package com.ytmp3

import java.util.UUID

data class ProcessingRecipe(
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val normalise: Boolean = false,
    val mono: Boolean = false,
    val reverse: Boolean = false
) {
    fun validated(durationMs: Long): ProcessingRecipe {
        val safeDurationMs = durationMs.coerceAtLeast(0)
        return copy(
            fadeInMs = fadeInMs.coerceIn(0, safeDurationMs),
            fadeOutMs = fadeOutMs.coerceIn(0, safeDurationMs)
        )
    }
}

data class SampleRegion(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val label: String = "",
    val recipe: ProcessingRecipe = ProcessingRecipe()
) {
    companion object {
        /** Returns true when every region is in bounds, non-empty, and time ordered without overlap. */
        fun validateOrdered(regions: List<SampleRegion>): Boolean =
            regions.all { it.startMs >= 0 && it.endMs > it.startMs } &&
                regions.zipWithNext().all { (current, next) -> current.endMs <= next.startMs }
    }
}

data class SampleProject(
    val id: String,
    val sourceUri: String,
    val title: String,
    val importedAtMs: Long = System.currentTimeMillis(),
    val sourceFingerprint: String? = null,
    val waveformCache: String? = null,
    val durationMs: Long = 0,
    val bpmEstimate: Float? = null,
    val bpmOverride: Float? = null,
    val keyEstimate: String? = null,
    val regions: List<SampleRegion> = emptyList()
)

data class SampleRecord(
    val id: String,
    val projectId: String,
    val startMs: Long,
    val endMs: Long,
    val outputUri: String,
    val durationMs: Long,
    val format: String,
    val label: String = "",
    val tags: List<String> = emptyList(),
    val favourite: Boolean = false,
    val regionId: String? = null
)

data class SamplePack(
    val id: String,
    val name: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val sampleIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val destinationUri: String? = null,
    val format: String = "WAV",
    val sampleRateHz: Int = 44_100,
    val bitDepth: Int = 16,
    val zip: Boolean = false
)
