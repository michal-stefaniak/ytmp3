package com.ytmp3

/** Generates a filesystem-safe name without replacing a file already in the destination. */
object OutputNames {
    fun unique(requested: String, existing: Set<String>): String {
        if (requested !in existing) return requested
        val extensionIndex = requested.lastIndexOf('.')
        val stem = if (extensionIndex > 0) requested.substring(0, extensionIndex) else requested
        val extension = if (extensionIndex > 0) requested.substring(extensionIndex) else ""
        var sequence = 1
        while (true) {
            val candidate = "${stem}_%02d".format(sequence) + extension
            if (candidate !in existing) return candidate
            sequence++
        }
    }
}
