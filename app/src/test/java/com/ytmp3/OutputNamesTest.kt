package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputNamesTest {
    @Test fun `collision appends sequence suffix`() {
        assertEquals("Kick_02.wav", OutputNames.unique("Kick.wav", setOf("Kick.wav", "Kick_01.wav")))
    }

    @Test fun `FLAC render keeps selected region recipe and bit depth`() {
        val args = SampleExporter.packRenderArgs(
            inputPath = "input.wav",
            outputPath = "output.flac",
            startMs = 500,
            endMs = 2500,
            recipe = ProcessingRecipe(fadeInMs = 100, fadeOutMs = 200, normalise = true, mono = true, reverse = true),
            format = "FLAC",
            sampleRateHz = 48_000,
            bitDepth = 24
        )

        assertEquals("0.5", args[args.indexOf("-ss") + 1])
        assertEquals("2.0", args[args.indexOf("-t") + 1])
        assertEquals("flac", args[args.indexOf("-c:a") + 1])
        assertEquals("24", args[args.indexOf("-bits_per_raw_sample") + 1])
        assertEquals("s32", args[args.indexOf("-sample_fmt") + 1])
        assertEquals("1", args[args.indexOf("-ac") + 1])
        val filter = args[args.indexOf("-af") + 1]
        org.junit.Assert.assertTrue(filter.contains("areverse"))
        org.junit.Assert.assertTrue(filter.contains("loudnorm"))
        org.junit.Assert.assertTrue(filter.contains("afade=t=in"))
        org.junit.Assert.assertTrue(filter.contains("afade=t=out"))
    }
}
