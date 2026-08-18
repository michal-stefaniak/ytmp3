package com.ytmp3

/** Keeps pack filenames predictable without changing the original sample metadata. */
class FilenameTemplate(private val raw: String) {
    fun render(label: String, number: Int, bpm: Float?, key: String?): String = raw
        .replace("{label}", label)
        .replace("{n}", "%02d".format(number))
        .replace("{bpm}", bpm?.toInt()?.toString() ?: "")
        .replace("{key}", key ?: "")
}

/** Input for the export worker. The export implementation belongs to Task 6. */
data class PackExportRequest(
    val pack: SamplePack,
    val samples: List<SampleRecord>,
    val filenameTemplate: FilenameTemplate = FilenameTemplate("{label}_{n}")
)
