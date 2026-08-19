package com.ytmp3

import java.io.File
import java.io.InputStream

/** Replaces each runtime file through a same-directory temporary file. */
internal object AtomicRuntimeInstaller {
    const val versionMarkerName = "runtime-version"

    fun install(
        destination: File,
        version: String,
        libraries: List<String>,
        openSource: (String) -> InputStream
    ) {
        check(destination.exists() || destination.mkdirs()) {
            "Unable to create FFmpeg runtime directory: $destination"
        }
        libraries.forEach { library ->
            replaceFile(File(destination, library)) { openSource(library) }
        }
        replaceFile(File(destination, versionMarkerName)) {
            version.byteInputStream()
        }
    }

    private fun replaceFile(target: File, openSource: () -> InputStream) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.delete()
        openSource().use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        check(temporary.renameTo(target)) {
            "Unable to install FFmpeg runtime library: ${target.name}"
        }
    }
}
