# Sample Chop Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a waveform-based multi-region sample chopper to `ytmp3`, reachable via a new "Sample mode" toggle, that exports sample-accurate WAV cuts from downloaded YouTube audio.

**Architecture:** A new `SampleEditorActivity` opens after a full-track download completes (auto for single-URL sample-mode submissions, on-demand via an "Edit" action for any other completed download). It decodes the file to peak data via a raw `ffmpeg` PCM dump, renders it on a custom `WaveformView`, lets the user drag out multiple regions, and exports each via a second `ffmpeg` invocation to sample-accurate WAV. `HistoryDb` gains a `kind`/`parentId` column pair so exported samples show up in the existing History screen.

**Tech Stack:** Kotlin, Android ViewBinding (no Compose — matches existing codebase), coroutines, SQLite via `SQLiteOpenHelper` (existing `HistoryDb` pattern), the `libffmpeg.so` binary already bundled by the `io.github.junkfood02.youtubedl-android:ffmpeg` dependency (invoked directly via `ProcessBuilder`, not through any library API — see Task 1 for why).

**Spec:** `docs/superpowers/specs/2026-08-16-sample-chop-editor-design.md`

## Global Constraints

- Minimum Android API 24 (`minSdk = 24` in `app/build.gradle.kts`) — don't use APIs newer than that without a `Build.VERSION.SDK_INT` guard, matching the existing pattern in `MainActivity.kt`/`DownloadForegroundService.kt`.
- Kotlin/JVM target 17, Compose is NOT used anywhere in this codebase — all new UI is plain `View`/`ViewBinding`, consistent with every existing Activity.
- No new Gradle dependencies. The `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` artifact bundled ffmpeg binary is invoked directly via `ProcessBuilder`; there's no dependency to add for waveform rendering (`WaveformView` is a custom `Canvas` view) or for playback (`android.media.MediaPlayer`, already part of the Android framework).
- WAV exports use `pcm_s16le`; MP3 stays the format for full-track downloads (`DownloadManager.kt` is unchanged in this regard).
- Minimum region length: 50ms. Regions must stay within `[0, durationMs]` and cannot cross their neighbor's handle.
- Follow existing code style: dark hardcoded hex colors (`#0D0D0D`, `#1A1A1A`, `#CC0000` accent, `#AAAAAA`/`#666666` text), `textAllCaps="false"` on buttons, singleton `object`s for stateless managers (matches `DownloadManager`, `Prefs`, `HistoryDb.get()`).

---

## File Structure

**New files:**
- `app/src/main/java/com/ytmp3/FFmpegBinary.kt` — locates and invokes the bundled ffmpeg binary directly (no library API for this exists — see Task 1).
- `app/src/main/java/com/ytmp3/PeakMath.kt` — pure Kotlin, no Android imports: PCM bytes → fixed-count min/max peak buckets. Unit-testable on the JVM.
- `app/src/main/java/com/ytmp3/WaveformExtractor.kt` — Android-dependent glue: runs `FFmpegBinary` to get PCM, calls `PeakMath`, returns peaks on a coroutine.
- `app/src/main/java/com/ytmp3/RegionMarker.kt` — data class + pure clamping/validation functions. Unit-testable.
- `app/src/main/java/com/ytmp3/WaveformView.kt` — custom `View`: renders peaks, pan/zoom, draggable region handles.
- `app/src/main/java/com/ytmp3/SampleExporter.kt` — runs `FFmpegBinary` once per region to cut WAV files, handles the SAF-or-app-storage output logic (mirrors `DownloadManager.copyToSaf`).
- `app/src/main/java/com/ytmp3/SampleEditorActivity.kt` — wires the above together: loads file, shows waveform, handles region creation, loop preview, export, and the "keep full track?" dialog.
- `app/src/main/res/layout/activity_sample_editor.xml`
- `app/src/test/java/com/ytmp3/PeakMathTest.kt`
- `app/src/test/java/com/ytmp3/RegionMarkerTest.kt`

**Modified files:**
- `app/src/main/java/com/ytmp3/Prefs.kt` — add `sampleMode: Boolean`.
- `app/src/main/java/com/ytmp3/DownloadItem.kt` — add `sampleMode: Boolean = false`.
- `app/src/main/java/com/ytmp3/DownloadManager.kt` — thread `sampleMode` through `submitUrls`/`startDownload` onto the created `DownloadItem` (no change to the yt-dlp request itself — trim fields already go unused when sample mode is on).
- `app/src/main/java/com/ytmp3/DownloadViewModel.kt` — thread `sampleMode` through `submitUrls`.
- `app/src/main/java/com/ytmp3/MainActivity.kt` — add the Sample mode toggle wiring, auto-open `SampleEditorActivity` for finished single-URL sample-mode items.
- `app/src/main/java/com/ytmp3/DownloadAdapter.kt` — add an "Edit" click target for `DONE` items with a `filePath`.
- `app/src/main/res/layout/activity_main.xml` — add the Sample mode toggle (reuses the existing `CheckBox` pattern next to "Trim audio").
- `app/src/main/res/layout/item_download.xml` — add an "Edit" button (small waveform-labeled button, same style as the existing `btn_retry`).
- `app/src/main/java/com/ytmp3/HistoryDb.kt` — schema migration to v2: add `kind`/`parentId` columns via real `ALTER TABLE` (not the existing `DROP TABLE` placeholder), add `kind`/`parentId` to `HistoryRecord`, add an `insertSample` convenience method.
- `app/src/main/java/com/ytmp3/HistoryActivity.kt` — render `SAMPLE`-kind rows with a distinguishing label.
- `app/src/main/AndroidManifest.xml` — register `SampleEditorActivity`.

---

## Task 1: FFmpeg binary runner

**Why this task exists:** `io.github.junkfood02.youtubedl-android` has no public API to run an arbitrary ffmpeg command — it only uses ffmpeg internally for yt-dlp's own `--ffmpeg-location`. We verified this by downloading and inspecting the `ffmpeg-0.18.1.aar`: `app/src/main/AndroidManifest.xml`'s `extractNativeLibs="true"` means Android extracts `<nativeLibraryDir>/libffmpeg.so` (a real, directly-executable ELF binary, ~300KB) at install time, but that binary dynamically links against `libavcodec.so.61` etc., which are NOT present standalone next to it — they're bundled inside a second file, `libffmpeg.zip.so` (~35MB zip), at `usr/lib/*.so*`. The existing `App.kt` already calls `FFmpeg.getInstance().init(this)` at startup, which unzips that archive to `<context.noBackupFilesDir>/youtubedl-android/packages/ffmpeg/usr/lib/`. `YoutubeDL.kt`'s own `init()` independently computes an `LD_LIBRARY_PATH` pointing at that exact same path (confirmed by reading its source: `packagesRoot = "packages"`, `ffmpegDirName = "ffmpeg"`, `baseName = "youtubedl-android"`) — the two library modules coordinate through this shared, undocumented-but-stable path convention. We reuse it rather than re-extracting ~35MB ourselves.

**Files:**
- Create: `app/src/main/java/com/ytmp3/FFmpegBinary.kt`
- Test: `app/src/test/java/com/ytmp3/FFmpegBinaryTest.kt`

**Interfaces:**
- Produces: `FFmpegBinary.run(context: Context, args: List<String>): FFmpegResult` where `FFmpegResult(exitCode: Int, stdout: ByteArray, stderr: String)`. Used by `WaveformExtractor` (Task 2) and `SampleExporter` (Task 5).
- Produces: `FFmpegBinary.binaryPath(context: Context): String` and `FFmpegBinary.ldLibraryPath(context: Context): String` (exposed separately so tests can assert on path construction without running a real process).

- [ ] **Step 1: Write the failing unit test for path construction**

```kotlin
package com.ytmp3

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FFmpegBinaryTest {
    @Test
    fun `binaryPath points at libffmpeg dot so in the native library dir`() {
        val ctx = mockk<Context>()
        val appInfo = mockk<android.content.pm.ApplicationInfo>()
        appInfo.nativeLibraryDir = "/data/app/com.ytmp3/lib/arm64"
        every { ctx.applicationInfo } returns appInfo

        assertEquals(
            "/data/app/com.ytmp3/lib/arm64/libffmpeg.so",
            FFmpegBinary.binaryPath(ctx)
        )
    }

    @Test
    fun `ldLibraryPath points at the ffmpeg module's extracted usr-lib dir`() {
        val ctx = mockk<Context>()
        every { ctx.noBackupFilesDir } returns File("/data/data/com.ytmp3/no_backup")

        assertEquals(
            "/data/data/com.ytmp3/no_backup/youtubedl-android/packages/ffmpeg/usr/lib",
            FFmpegBinary.ldLibraryPath(ctx)
        )
    }
}
```

Add `testImplementation("io.mockk:mockk:1.13.11")` to `app/build.gradle.kts`'s `dependencies` block — it's a test-only dependency, doesn't affect the shipped APK.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest --tests "com.ytmp3.FFmpegBinaryTest"`
Expected: FAIL — `FFmpegBinary` doesn't exist yet (compilation error).

- [ ] **Step 3: Implement `FFmpegBinary`**

```kotlin
package com.ytmp3

import android.content.Context
import java.io.File

data class FFmpegResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

object FFmpegBinary {

    fun binaryPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    /**
     * The ffmpeg module (io.github.junkfood02.youtubedl-android:ffmpeg) extracts its
     * bundled shared libs here at app startup (see App.kt's FFmpeg.getInstance().init()
     * call). YoutubeDL.kt's own init() independently derives the same path for yt-dlp's
     * internal ffmpeg calls, so this location is a stable, if undocumented, contract
     * between the two library modules rather than a version-specific implementation detail.
     */
    fun ldLibraryPath(context: Context): String =
        File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath

    fun run(context: Context, args: List<String>): FFmpegResult {
        val command = mutableListOf(binaryPath(context)).apply { addAll(args) }
        val process = ProcessBuilder(command)
            .apply { environment()["LD_LIBRARY_PATH"] = ldLibraryPath(context) }
            .start()

        // Stdout can carry large binary output (raw PCM via pipe:1 in Task 3) while ffmpeg
        // concurrently logs progress to stderr. Reading either stream to completion before
        // touching the other risks a classic ProcessBuilder pipe deadlock once stderr fills
        // its ~64KB buffer while we're still blocked draining stdout — so both are drained
        // on separate threads at once.
        var stderrText = ""
        val stderrThread = Thread {
            stderrText = process.errorStream.bufferedReader().readText()
        }.apply { start() }

        val stdout = process.inputStream.readBytes()
        stderrThread.join()
        val exitCode = process.waitFor()
        return FFmpegResult(exitCode, stdout, stderrText)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest --tests "com.ytmp3.FFmpegBinaryTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/ytmp3/FFmpegBinary.kt app/src/test/java/com/ytmp3/FFmpegBinaryTest.kt
git commit -m "Add FFmpegBinary: direct invocation of the bundled ffmpeg executable"
```

---

## Task 2: Peak extraction math (pure, unit-tested)

**Files:**
- Create: `app/src/main/java/com/ytmp3/PeakMath.kt`
- Test: `app/src/test/java/com/ytmp3/PeakMathTest.kt`

**Interfaces:**
- Consumes: nothing (pure function, no dependency on Task 1).
- Produces: `PeakMath.reduceToPeaks(pcm: ShortArray, bucketCount: Int): List<PeakBucket>` where `data class PeakBucket(val min: Short, val max: Short)`. Used by `WaveformExtractor` (Task 3) and `WaveformView` (Task 4, which renders `List<PeakBucket>`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class PeakMathTest {
    @Test
    fun `splits samples evenly into the requested bucket count`() {
        // 8 samples, 4 buckets -> 2 samples per bucket
        val pcm = shortArrayOf(0, 10, -5, 20, 100, -100, 3, 3)
        val peaks = PeakMath.reduceToPeaks(pcm, bucketCount = 4)

        assertEquals(4, peaks.size)
        assertEquals(PeakMath.PeakBucket(min = 0, max = 10), peaks[0])
        assertEquals(PeakMath.PeakBucket(min = -5, max = 20), peaks[1])
        assertEquals(PeakMath.PeakBucket(min = -100, max = 100), peaks[2])
        assertEquals(PeakMath.PeakBucket(min = 3, max = 3), peaks[3])
    }

    @Test
    fun `handles sample counts that dont divide evenly by folding the remainder into the last bucket`() {
        // 10 samples, 3 buckets -> 3,3,4
        val pcm = ShortArray(10) { it.toShort() }
        val peaks = PeakMath.reduceToPeaks(pcm, bucketCount = 3)

        assertEquals(3, peaks.size)
        assertEquals(PeakMath.PeakBucket(min = 0, max = 2), peaks[0])
        assertEquals(PeakMath.PeakBucket(min = 3, max = 5), peaks[1])
        assertEquals(PeakMath.PeakBucket(min = 6, max = 9), peaks[2])
    }

    @Test
    fun `returns one bucket per sample when there are fewer samples than requested buckets`() {
        val pcm = shortArrayOf(5, -5)
        val peaks = PeakMath.reduceToPeaks(pcm, bucketCount = 10)

        assertEquals(2, peaks.size)
        assertEquals(PeakMath.PeakBucket(min = 5, max = 5), peaks[0])
        assertEquals(PeakMath.PeakBucket(min = -5, max = -5), peaks[1])
    }

    @Test
    fun `empty input produces no buckets`() {
        assertEquals(emptyList<PeakMath.PeakBucket>(), PeakMath.reduceToPeaks(ShortArray(0), bucketCount = 100))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest --tests "com.ytmp3.PeakMathTest"`
Expected: FAIL — `PeakMath` doesn't exist yet.

- [ ] **Step 3: Implement `PeakMath`**

```kotlin
package com.ytmp3

object PeakMath {
    data class PeakBucket(val min: Short, val max: Short)

    fun reduceToPeaks(pcm: ShortArray, bucketCount: Int): List<PeakBucket> {
        if (pcm.isEmpty()) return emptyList()
        val effectiveBuckets = bucketCount.coerceAtMost(pcm.size)
        val baseSize = pcm.size / effectiveBuckets
        val remainder = pcm.size % effectiveBuckets

        val result = ArrayList<PeakBucket>(effectiveBuckets)
        var start = 0
        for (bucketIndex in 0 until effectiveBuckets) {
            val extra = if (bucketIndex == effectiveBuckets - 1) remainder else 0
            val end = start + baseSize + extra
            var min = pcm[start]
            var max = pcm[start]
            for (i in start until end) {
                if (pcm[i] < min) min = pcm[i]
                if (pcm[i] > max) max = pcm[i]
            }
            result.add(PeakBucket(min, max))
            start = end
        }
        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest --tests "com.ytmp3.PeakMathTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ytmp3/PeakMath.kt app/src/test/java/com/ytmp3/PeakMathTest.kt
git commit -m "Add PeakMath: pure min/max peak-bucket reduction for waveform rendering"
```

---

## Task 3: Waveform extraction (ffmpeg PCM dump + peaks)

**Files:**
- Create: `app/src/main/java/com/ytmp3/WaveformExtractor.kt`

**Interfaces:**
- Consumes: `FFmpegBinary.run(context, args): FFmpegResult` (Task 1), `PeakMath.reduceToPeaks(pcm, bucketCount): List<PeakMath.PeakBucket>` (Task 2).
- Produces: `suspend fun WaveformExtractor.extract(context: Context, filePath: String, bucketCount: Int = 2000): Result<List<PeakMath.PeakBucket>>`. Used by `SampleEditorActivity` (Task 6).

- [ ] **Step 1: Implement `WaveformExtractor`**

No unit test here — this function's only logic beyond Tasks 1/2 (already tested) is gluing a `ProcessBuilder` result to `PeakMath`, which requires a real ffmpeg binary to verify meaningfully. It's covered by the manual on-emulator QA pass in Task 9.

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/ytmp3/WaveformExtractor.kt
git commit -m "Add WaveformExtractor: ffmpeg PCM dump wired to PeakMath"
```

---

## Task 4: RegionMarker (data + clamping, unit-tested)

**Files:**
- Create: `app/src/main/java/com/ytmp3/RegionMarker.kt`
- Test: `app/src/test/java/com/ytmp3/RegionMarkerTest.kt`

**Interfaces:**
- Produces: `data class RegionMarker(val id: String, val startMs: Long, val endMs: Long, val label: String)`, `RegionMarker.clamp(startMs: Long, endMs: Long, trackDurationMs: Long, minLengthMs: Long = 50): Pair<Long, Long>`. Used by `WaveformView` (Task 5, for live handle dragging) and `SampleEditorActivity` (Task 6, for region creation).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionMarkerTest {
    @Test
    fun `clamps start below zero to zero`() {
        val (start, end) = RegionMarker.clamp(startMs = -500, endMs = 1000, trackDurationMs = 60_000)
        assertEquals(0L, start)
        assertEquals(1000L, end)
    }

    @Test
    fun `clamps end beyond track duration to track duration`() {
        val (start, end) = RegionMarker.clamp(startMs = 1000, endMs = 999_999, trackDurationMs = 60_000)
        assertEquals(1000L, start)
        assertEquals(60_000L, end)
    }

    @Test
    fun `enforces minimum region length by pushing end forward`() {
        val (start, end) = RegionMarker.clamp(startMs = 1000, endMs = 1010, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(1000L, start)
        assertEquals(1050L, end)
    }

    @Test
    fun `minimum length push respects track duration ceiling`() {
        val (start, end) = RegionMarker.clamp(startMs = 59_980, endMs = 59_990, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(59_950L, start)
        assertEquals(60_000L, end)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest --tests "com.ytmp3.RegionMarkerTest"`
Expected: FAIL — `RegionMarker` doesn't exist yet.

- [ ] **Step 3: Implement `RegionMarker`**

```kotlin
package com.ytmp3

import java.util.UUID

data class RegionMarker(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val label: String = ""
) {
    companion object {
        fun clamp(
            startMs: Long,
            endMs: Long,
            trackDurationMs: Long,
            minLengthMs: Long = 50
        ): Pair<Long, Long> {
            var s = startMs.coerceIn(0, trackDurationMs)
            var e = endMs.coerceIn(0, trackDurationMs)
            if (e - s < minLengthMs) {
                e = (s + minLengthMs).coerceAtMost(trackDurationMs)
                s = (e - minLengthMs).coerceAtLeast(0)
            }
            return s to e
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest --tests "com.ytmp3.RegionMarkerTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ytmp3/RegionMarker.kt app/src/test/java/com/ytmp3/RegionMarkerTest.kt
git commit -m "Add RegionMarker with bounds/minimum-length clamping"
```

---

## Task 5: WaveformView (custom view, pan/zoom, draggable regions)

**Files:**
- Create: `app/src/main/java/com/ytmp3/WaveformView.kt`

**Interfaces:**
- Consumes: `List<PeakMath.PeakBucket>` (Task 2), `RegionMarker` + `RegionMarker.clamp` (Task 4).
- Produces:
  - `WaveformView.setPeaks(peaks: List<PeakMath.PeakBucket>, trackDurationMs: Long)`
  - `WaveformView.setRegions(regions: List<RegionMarker>)`
  - `WaveformView.onRegionsChanged: ((List<RegionMarker>) -> Unit)?` (callback property, fired after a drag ends)
  - `WaveformView.onRegionTapped: ((RegionMarker) -> Unit)?` (callback property, fired on a tap inside an existing region, for loop-preview in Task 6)
  - Used by `activity_sample_editor.xml` (declared as `<com.ytmp3.WaveformView>`) and driven from `SampleEditorActivity` (Task 6).

- [ ] **Step 1: Implement `WaveformView`**

No unit test — touch-gesture behavior needs a real device/emulator to verify meaningfully (this is the manual-QA-covered risk area called out in the design doc). Verification happens in Task 9's on-emulator pass, specifically checking: pan, pinch-zoom, region creation via long-press-drag, handle dragging, and that dragging one handle can't cross its neighbor.

```kotlin
package com.ytmp3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onRegionsChanged: ((List<RegionMarker>) -> Unit)? = null
    var onRegionTapped: ((RegionMarker) -> Unit)? = null

    private var peaks: List<PeakMath.PeakBucket> = emptyList()
    private var trackDurationMs: Long = 0
    private var regions: MutableList<RegionMarker> = mutableListOf()

    // msPerPx * viewWidth = visible window; scrollOffsetMs = left edge of the visible window
    private var msPerPx: Float = 1f
    private var scrollOffsetMs: Float = 0f
    private var draggingHandle: Pair<RegionMarker, Boolean>? = null // region, isStartHandle
    private var pendingRegionStartMs: Long? = null
    private var pendingRegionEndMs: Long? = null

    private val wavePaint = Paint().apply { color = Color.parseColor("#CC0000"); strokeWidth = 2f }
    private val regionPaint = Paint().apply { color = Color.parseColor("#55CC0000") }
    private val handlePaint = Paint().apply { color = Color.parseColor("#FFFFFF"); strokeWidth = 6f }
    private val bgPaint = Paint().apply { color = Color.parseColor("#111111") }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // maxMsPerPx() is 0 before setPeaks runs (trackDurationMs == 0) and can fall below
            // minMsPerPx() on tracks shorter than the view's pixel width — coerceIn(lo, hi) throws
            // if hi < lo, so hi is floored at lo to keep the range valid in both cases.
            val hi = maxMsPerPx().coerceAtLeast(minMsPerPx())
            msPerPx = (msPerPx / detector.scaleFactor).coerceIn(minMsPerPx(), hi)
            scrollOffsetMs = scrollOffsetMs.coerceIn(0f, max(0f, trackDurationMs - width * msPerPx))
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (draggingHandle != null || pendingRegionStartMs != null) return false
            scrollOffsetMs = (scrollOffsetMs + dx * msPerPx).coerceIn(0f, max(0f, trackDurationMs - width * msPerPx))
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tapMs = xToMs(e.x)
            regions.firstOrNull { tapMs in it.startMs..it.endMs }?.let { onRegionTapped?.invoke(it) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (draggingHandle != null) return
            pendingRegionStartMs = xToMs(e.x)
        }
    })

    fun setPeaks(peaks: List<PeakMath.PeakBucket>, trackDurationMs: Long) {
        this.peaks = peaks
        this.trackDurationMs = trackDurationMs
        msPerPx = trackDurationMs / max(1f, width.toFloat())
        invalidate()
    }

    fun setRegions(regions: List<RegionMarker>) {
        this.regions = regions.toMutableList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // If setPeaks ran before the first layout pass, msPerPx was derived from width=0 and needs
        // re-deriving now that the real width is known.
        if (trackDurationMs > 0) {
            msPerPx = msPerPx.coerceIn(minMsPerPx(), maxMsPerPx().coerceAtLeast(minMsPerPx()))
            scrollOffsetMs = scrollOffsetMs.coerceIn(0f, max(0f, trackDurationMs - w * msPerPx))
        }
    }

    private fun minMsPerPx() = 1f // 1ms/px = max zoom in
    private fun maxMsPerPx() = trackDurationMs / max(1f, width.toFloat())

    private fun xToMs(x: Float): Long = (scrollOffsetMs + x * msPerPx).toLong().coerceIn(0, trackDurationMs)
    private fun msToX(ms: Long): Float = (ms - scrollOffsetMs) / msPerPx

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchMs = xToMs(event.x)
                draggingHandle = findHandleNear(touchMs)
            }
            MotionEvent.ACTION_MOVE -> {
                // No early return here: letting every MOVE reach gestureDetector below is what
                // lets its internal long-press timer see the movement and cancel itself once slop
                // is crossed. An early return here starves it of MOVE events, so a stale long-press
                // fires mid-drag and creates a phantom region on release (see ACTION_UP) — the
                // onScroll guard above is what keeps this safe from also triggering a pan.
                draggingHandle?.let { (region, isStart) ->
                    val newMs = xToMs(event.x)
                    val idx = regions.indexOfFirst { it.id == region.id }
                    if (idx == -1) return@let
                    val current = regions[idx]
                    val (clampedStart, clampedEnd) = if (isStart) {
                        RegionMarker.clamp(newMs, current.endMs, trackDurationMs)
                    } else {
                        RegionMarker.clamp(current.startMs, newMs, trackDurationMs)
                    }
                    regions[idx] = current.copy(startMs = clampedStart, endMs = clampedEnd)
                    invalidate()
                }
                pendingRegionStartMs?.let {
                    pendingRegionEndMs = xToMs(event.x)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingHandle != null) {
                    draggingHandle = null
                    onRegionsChanged?.invoke(regions.toList())
                }
                pendingRegionStartMs?.let { startMs ->
                    val endMs = pendingRegionEndMs ?: startMs
                    val (s, e) = RegionMarker.clamp(min(startMs, endMs), max(startMs, endMs), trackDurationMs)
                    regions.add(RegionMarker(startMs = s, endMs = e))
                    onRegionsChanged?.invoke(regions.toList())
                }
                pendingRegionStartMs = null
                pendingRegionEndMs = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                // CANCEL means the gesture was aborted (e.g. a parent view intercepted it) — discard
                // in-progress state rather than committing a region the user never actually released.
                draggingHandle = null
                pendingRegionStartMs = null
                pendingRegionEndMs = null
                invalidate()
            }
        }

        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun findHandleNear(touchMs: Long, toleranceMs: Long = 200): Pair<RegionMarker, Boolean>? {
        for (region in regions) {
            if (kotlin.math.abs(region.startMs - touchMs) <= toleranceMs) return region to true
            if (kotlin.math.abs(region.endMs - touchMs) <= toleranceMs) return region to false
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (peaks.isEmpty()) return

        val msPerBucket = trackDurationMs.toFloat() / peaks.size
        val midY = height / 2f
        val scaleY = height / 2f / Short.MAX_VALUE.toFloat()

        val firstVisibleBucket = (scrollOffsetMs / msPerBucket).toInt().coerceIn(0, peaks.size - 1)
        val lastVisibleBucket = ((scrollOffsetMs + width * msPerPx) / msPerBucket).toInt().coerceIn(0, peaks.size - 1)

        for (i in firstVisibleBucket..lastVisibleBucket) {
            val bucket = peaks[i]
            val x = msToX((i * msPerBucket).toLong())
            canvas.drawLine(x, midY - bucket.max * scaleY, x, midY - bucket.min * scaleY, wavePaint)
        }

        for (region in regions) {
            val left = msToX(region.startMs)
            val right = msToX(region.endMs)
            canvas.drawRect(left, 0f, right, height.toFloat(), regionPaint)
            canvas.drawLine(left, 0f, left, height.toFloat(), handlePaint)
            canvas.drawLine(right, 0f, right, height.toFloat(), handlePaint)
        }

        val pendingStart = pendingRegionStartMs
        if (pendingStart != null) {
            val pendingEnd = pendingRegionEndMs ?: pendingStart
            val left = msToX(min(pendingStart, pendingEnd))
            val right = msToX(max(pendingStart, pendingEnd))
            canvas.drawRect(left, 0f, right, height.toFloat(), regionPaint)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/ytmp3/WaveformView.kt
git commit -m "Add WaveformView: peak rendering, pan/zoom, draggable multi-region selection"
```

---

## Task 6: SampleExporter (ffmpeg WAV cuts + SAF output)

**Files:**
- Create: `app/src/main/java/com/ytmp3/SampleExporter.kt`

**Interfaces:**
- Consumes: `FFmpegBinary.run` (Task 1), `RegionMarker` (Task 4), `Prefs.downloadDirUri` (existing).
- Produces: `suspend fun SampleExporter.export(context: Context, sourceFilePath: String, sourceTitle: String, regions: List<RegionMarker>): List<SampleExporter.ExportedSample>` where `data class ExportedSample(val region: RegionMarker, val filePath: String)`. Used by `SampleEditorActivity` (Task 7).

- [ ] **Step 1: Implement `SampleExporter`**

Mirrors `DownloadManager.copyToSaf`'s existing SAF-vs-app-storage branching so output-location behavior is consistent with full-track downloads, just under a `Samples` subfolder instead of `Music`.

```kotlin
package com.ytmp3

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SampleExporter {

    data class ExportedSample(val region: RegionMarker, val filePath: String)

    suspend fun export(
        context: Context,
        sourceFilePath: String,
        sourceTitle: String,
        regions: List<RegionMarker>
    ): List<ExportedSample> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "sample_export_${System.currentTimeMillis()}").also { it.mkdirs() }
        try {
            regions.mapIndexedNotNull { index, region ->
                // Each region's whole pipeline (ffmpeg cut + output copy) is independently
                // resilient: an exception anywhere here (e.g. disk-full during the output copy)
                // skips just this region rather than losing every already-exported region ahead
                // of it in the batch.
                try {
                    val safeTitle = sourceTitle.take(40).replace(Regex("[^A-Za-z0-9 _-]"), "_")
                    val fileName = "${safeTitle}_${index + 1}.wav"
                    val tempOut = File(tempDir, fileName)

                    val result = FFmpegBinary.run(
                        context,
                        listOf(
                            "-i", sourceFilePath,
                            "-ss", (region.startMs / 1000.0).toString(),
                            "-to", (region.endMs / 1000.0).toString(),
                            "-c:a", "pcm_s16le",
                            tempOut.absolutePath
                        )
                    )
                    if (result.exitCode != 0 || !tempOut.exists()) return@mapIndexedNotNull null

                    val finalPath = moveToOutput(context, tempOut)
                    finalPath?.let { ExportedSample(region, it) }
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun moveToOutput(context: Context, file: File): String? {
        val dirUriStr = Prefs.downloadDirUri
        return if (dirUriStr != null) {
            copyToSamplesTree(context, file, Uri.parse(dirUriStr))
        } else {
            val destDir = File(context.getExternalFilesDir(null), "Samples").also { it.mkdirs() }
            file.copyTo(File(destDir, file.name), overwrite = true).absolutePath
        }
    }

    private fun copyToSamplesTree(context: Context, file: File, treeUri: Uri): String? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val samplesDir = root.findFile("Samples") ?: root.createDirectory("Samples") ?: return null
        val dest = samplesDir.createFile("audio/wav", file.nameWithoutExtension) ?: return null
        // openOutputStream can return null for some DocumentsProvider implementations; without this
        // check, a write that silently didn't happen would still report success to the caller.
        val written = context.contentResolver.openOutputStream(dest.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
            true
        } ?: false
        return if (written) dest.uri.toString() else null
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/ytmp3/SampleExporter.kt
git commit -m "Add SampleExporter: per-region sample-accurate WAV cuts via ffmpeg"
```

---

## Task 7: HistoryDb schema migration (kind/parentId)

**Files:**
- Modify: `app/src/main/java/com/ytmp3/HistoryDb.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `HistoryRecord` gains `kind: String = "FULL_TRACK"` and `parentId: String? = null`. New method `HistoryDb.insertSample(id: String, url: String, title: String, parentId: String, filePath: String)`. New method `HistoryDb.markSampled(id: String)` (sets an existing `FULL_TRACK` row's status to `"SAMPLED"`, used when the full track is deleted after export). Used by `SampleEditorActivity` (Task 8) and `HistoryActivity` (Task 9).

**Note on migration approach:** the existing `onUpgrade` does `DROP TABLE IF EXISTS history; onCreate(db)` — acceptable when the schema never shipped to real users, but this is the first migration where preserving existing history rows genuinely matters (a user's download history is data they'd notice disappearing). This task replaces that placeholder with a real `ALTER TABLE`.

- [ ] **Step 1: Modify `HistoryDb.kt`**

```kotlin
package com.ytmp3

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryRecord(
    val id: String,
    val url: String,
    val title: String,
    val timestamp: Long,
    val status: String,
    val kind: String = "FULL_TRACK",
    val parentId: String? = null
)

class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 2) {

    companion object {
        @Volatile private var inst: HistoryDb? = null
        fun get(ctx: Context) = inst ?: synchronized(this) {
            inst ?: HistoryDb(ctx).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id TEXT PRIMARY KEY, url TEXT, title TEXT, timestamp INTEGER, status TEXT, " +
                "kind TEXT NOT NULL DEFAULT 'FULL_TRACK', parentId TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN kind TEXT NOT NULL DEFAULT 'FULL_TRACK'")
            db.execSQL("ALTER TABLE history ADD COLUMN parentId TEXT")
        }
    }

    fun insert(r: HistoryRecord) {
        writableDatabase.insertWithOnConflict(
            "history", null,
            ContentValues().apply {
                put("id", r.id)
                put("url", r.url)
                put("title", r.title)
                put("timestamp", r.timestamp)
                put("status", r.status)
                put("kind", r.kind)
                put("parentId", r.parentId)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun insertSample(id: String, url: String, title: String, parentId: String, filePath: String) {
        insert(
            HistoryRecord(
                id = id, url = url, title = title, timestamp = System.currentTimeMillis(),
                status = "DONE", kind = "SAMPLE", parentId = parentId
            )
        )
    }

    fun markSampled(id: String) {
        writableDatabase.execSQL("UPDATE history SET status = 'SAMPLED' WHERE id = ?", arrayOf(id))
    }

    fun getAll(): List<HistoryRecord> {
        val list = mutableListOf<HistoryRecord>()
        readableDatabase.rawQuery(
            "SELECT id, url, title, timestamp, status, kind, parentId FROM history ORDER BY timestamp DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                list += HistoryRecord(
                    id = c.getString(0),
                    url = c.getString(1),
                    title = c.getString(2),
                    timestamp = c.getLong(3),
                    status = c.getString(4),
                    kind = c.getString(5),
                    parentId = c.getString(6)
                )
            }
        }
        return list
    }

    fun clearAll() {
        writableDatabase.delete("history", null, null)
    }

    fun findByUrl(url: String): HistoryRecord? {
        readableDatabase.rawQuery(
            "SELECT id,url,title,timestamp,status,kind,parentId FROM history WHERE url=? AND status='DONE' LIMIT 1",
            arrayOf(url)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return HistoryRecord(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6))
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/ytmp3/HistoryDb.kt
git commit -m "Migrate HistoryDb to v2: add kind/parentId columns for sample rows"
```

---

## Task 8: SampleEditorActivity + layout + manifest registration

**Files:**
- Create: `app/src/main/java/com/ytmp3/SampleEditorActivity.kt`
- Create: `app/src/main/res/layout/activity_sample_editor.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `WaveformExtractor.extract` (Task 3), `WaveformView` (Task 5), `SampleExporter.export` (Task 6), `HistoryDb.insertSample`/`markSampled` (Task 7), `RegionMarker` (Task 4), `Prefs.storageWarn` (existing).
- Produces: launched via `Intent(this, SampleEditorActivity::class.java).putExtra("filePath", path).putExtra("title", title).putExtra("historyId", id)` — this exact extras contract is what Task 9 (MainActivity/DownloadAdapter wiring) must match.

- [ ] **Step 1: Create the layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#0D0D0D"
    android:fitsSystemWindows="true">

    <TextView
        android:id="@+id/tv_editor_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp"
        android:textColor="#FFFFFF"
        android:textSize="15sp"
        android:textStyle="bold"
        android:maxLines="1"
        android:ellipsize="end" />

    <ProgressBar
        android:id="@+id/progress_extracting"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center" />

    <com.ytmp3.WaveformView
        android:id="@+id/waveform"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:visibility="gone" />

    <TextView
        android:id="@+id/tv_region_count"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="12dp"
        android:textColor="#AAAAAA"
        android:textSize="12sp"
        android:text="Long-press and drag on the waveform to create a region. Tap a region to preview it." />

    <Button
        android:id="@+id/btn_export"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:layout_margin="16dp"
        android:backgroundTint="#CC0000"
        android:text="Export"
        android:textAllCaps="false"
        android:textColor="#FFFFFF"
        android:enabled="false" />

</LinearLayout>
```

- [ ] **Step 2: Register the activity in the manifest**

In `app/src/main/AndroidManifest.xml`, add next to the existing `HistoryActivity` entry:

```xml
        <activity
            android:name=".SampleEditorActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|keyboardHidden" />
```

`configChanges` here means Android does not destroy/recreate the activity across a rotation — the in-memory region list, waveform peaks, and any playing preview all survive untouched. Without it, rotating mid-edit silently loses every region the user drew and re-runs the whole ffmpeg decode; this is the smallest fix that closes the gap without needing region/peaks state restoration code.

- [ ] **Step 3: Implement `SampleEditorActivity`**

```kotlin
package com.ytmp3

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.StatFs
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ytmp3.databinding.ActivitySampleEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SampleEditorActivity : AppCompatActivity() {

    private lateinit var b: ActivitySampleEditorBinding
    private lateinit var filePath: String
    private lateinit var title: String
    private var historyId: String? = null
    private var trackDurationMs: Long = 0
    private var previewJob: Job? = null
    private var previewPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySampleEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        filePath = intent.getStringExtra("filePath") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: filePath.substringAfterLast('/')
        historyId = intent.getStringExtra("historyId")
        b.tvEditorTitle.text = title

        b.waveform.onRegionsChanged = { regions -> b.btnExport.isEnabled = regions.isNotEmpty() }
        b.waveform.onRegionTapped = { region -> previewRegion(region) }
        b.btnExport.setOnClickListener { exportRegions() }

        loadWaveform()
    }

    private fun loadWaveform() {
        b.progressExtracting.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            // Reading duration via MediaPlayer.prepare() would block the UI thread parsing the
            // container (real risk on long/podcast-length tracks without a fast seek index) --
            // MediaMetadataRetriever does the same job without decoding, and running it on IO
            // keeps onCreate's first frame unblocked. release() (not close(), API 29+) is used
            // since minSdk is 24.
            val durationResult = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(filePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }
                }
            }
            val duration = durationResult.getOrNull()
            if (duration == null || duration <= 0) {
                b.progressExtracting.visibility = android.view.View.GONE
                showErrorDialog(durationResult.exceptionOrNull()?.message ?: "Couldn't read audio duration")
                return@launch
            }
            trackDurationMs = duration

            val result = WaveformExtractor.extract(this@SampleEditorActivity, filePath)
            b.progressExtracting.visibility = android.view.View.GONE
            result.fold(
                onSuccess = { peaks ->
                    b.waveform.visibility = android.view.View.VISIBLE
                    b.waveform.setPeaks(peaks, trackDurationMs)
                },
                onFailure = { showErrorDialog(it.message ?: "Failed to read audio") }
            )
        }
    }

    private fun previewRegion(region: RegionMarker) {
        stopPreview()
        val player = try {
            MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                seekTo(region.startMs.toInt())
                start()
            }
        } catch (e: Exception) {
            showErrorDialog(e.message ?: "Couldn't play preview")
            return
        }
        previewPlayer = player
        previewJob = lifecycleScope.launch {
            // Loops the region continuously (per the design spec) until stopPreview() cancels
            // this job -- delay() is a cancellable suspension point, so cancellation unwinds the
            // loop cleanly without needing the player to be touched after release().
            while (true) {
                while (player.currentPosition < region.endMs) delay(100)
                player.seekTo(region.startMs.toInt())
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun exportRegions() {
        val regions = b.waveform.currentRegions()
        if (regions.isEmpty()) return

        if (Prefs.storageWarn) {
            val free = StatFs(cacheDir.absolutePath).availableBytes
            val estimatedBytes = regions.sumOf { (it.endMs - it.startMs) * 176L } // ~176 bytes/ms for 16-bit stereo 44.1kHz WAV
            if (free < estimatedBytes + 50L * 1024 * 1024) {
                showErrorDialog("Low storage: need ~${estimatedBytes / 1024 / 1024}MB free for WAV export")
                return
            }
        }

        b.btnExport.isEnabled = false
        lifecycleScope.launch {
            val exported = SampleExporter.export(this@SampleEditorActivity, filePath, title, regions)
            b.btnExport.isEnabled = true
            if (exported.isEmpty()) {
                showErrorDialog("Export failed for all regions")
                return@launch
            }
            val parentId = historyId ?: UUID.randomUUID().toString()
            exported.forEach { sample ->
                HistoryDb.get(this@SampleEditorActivity).insertSample(
                    id = UUID.randomUUID().toString(),
                    url = "",
                    title = "sample of $title",
                    parentId = parentId,
                    filePath = sample.filePath
                )
            }
            askKeepFullTrack()
        }
    }

    private fun askKeepFullTrack() {
        AlertDialog.Builder(this)
            .setTitle("Keep full track too?")
            .setMessage("The full downloaded track can be deleted now that your samples are exported, or kept alongside them.")
            .setPositiveButton("Keep") { _, _ -> finish() }
            .setNegativeButton("Delete") { _, _ ->
                File(filePath).delete()
                historyId?.let { HistoryDb.get(this).markSampled(it) }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStop() {
        // Preview audio must not keep playing once the activity leaves the foreground -- onDestroy
        // alone isn't enough since the system can leave a backgrounded activity alive indefinitely.
        stopPreview()
        super.onStop()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }
}
```

- [ ] **Step 4: Add `currentRegions()` to `WaveformView`**

`SampleEditorActivity` needs to read the view's authoritative region list at export time, not just react to the change callback. Add this to `WaveformView.kt` (from Task 5):

```kotlin
    fun currentRegions(): List<RegionMarker> = regions.toList()
```

- [ ] **Step 5: Build to verify it compiles**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ytmp3/SampleEditorActivity.kt app/src/main/res/layout/activity_sample_editor.xml app/src/main/AndroidManifest.xml app/src/main/java/com/ytmp3/WaveformView.kt
git commit -m "Add SampleEditorActivity: waveform screen wiring extraction, preview, and export together"
```

---

## Task 9: Wire sample mode into MainActivity, DownloadManager, Prefs, DownloadAdapter, HistoryActivity

**Files:**
- Modify: `app/src/main/java/com/ytmp3/Prefs.kt`
- Modify: `app/src/main/java/com/ytmp3/DownloadItem.kt`
- Modify: `app/src/main/java/com/ytmp3/DownloadManager.kt`
- Modify: `app/src/main/java/com/ytmp3/DownloadViewModel.kt`
- Modify: `app/src/main/java/com/ytmp3/MainActivity.kt`
- Modify: `app/src/main/java/com/ytmp3/DownloadAdapter.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/layout/item_download.xml`
- Modify: `app/src/main/java/com/ytmp3/HistoryActivity.kt`

**Interfaces:**
- Consumes: `SampleEditorActivity`'s extras contract (`filePath`, `title`, `historyId`) from Task 8.
- Produces: nothing further downstream — this is the last task.

- [ ] **Step 1: Add `Prefs.sampleMode`**

In `Prefs.kt`, add alongside the other `Boolean` prefs:

```kotlin
    var sampleMode: Boolean
        get() = getBool("sampleMode", false)
        set(v) { setBool("sampleMode", v) }
```

- [ ] **Step 2: Add `sampleMode` to `DownloadItem`**

In `DownloadItem.kt`:

```kotlin
data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = url,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val errorMsg: String? = null,
    val filePath: String? = null,
    val speedKbps: Int = 0,
    val etaSeconds: Long = 0,
    val sampleMode: Boolean = false
)
```

- [ ] **Step 3: Thread `sampleMode` through `DownloadManager.submitUrls`**

In `DownloadManager.kt`, change:

```kotlin
    fun submitUrls(urls: List<String>, trimStart: String? = null, trimEnd: String? = null) {
        val items = urls.map { DownloadItem(url = it) }
        _downloads.value = _downloads.value + items
        startService()
        items.forEach { startDownload(it, trimStart, trimEnd) }
    }
```

to:

```kotlin
    fun submitUrls(urls: List<String>, trimStart: String? = null, trimEnd: String? = null, sampleMode: Boolean = false) {
        val items = urls.map { DownloadItem(url = it, sampleMode = sampleMode) }
        _downloads.value = _downloads.value + items
        startService()
        items.forEach { startDownload(it, trimStart, trimEnd) }
    }
```

No other changes needed in `DownloadManager.kt` — `startDownload` already only adds `--download-sections` when `trimStart != null`, and `MainActivity`'s trim fields are already hidden when sample mode is on (Step 6 below), so full tracks are fetched naturally.

- [ ] **Step 4: Thread `sampleMode` through `DownloadViewModel`**

In `DownloadViewModel.kt`:

```kotlin
    fun submitUrls(urls: List<String>, trimStart: String? = null, trimEnd: String? = null, sampleMode: Boolean = false) =
        DownloadManager.submitUrls(urls, trimStart, trimEnd, sampleMode)
```

- [ ] **Step 5: Add the Sample mode toggle to `activity_main.xml`**

Add directly below the existing `cb_trim` `CheckBox` in `activity_main.xml` (inside the same vertical `LinearLayout`):

```xml
        <CheckBox
            android:id="@+id/cb_sample_mode"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Sample mode (waveform chop editor)"
            android:textColor="#AAAAAA"
            android:textSize="13sp"
            android:buttonTint="#CC0000" />
```

- [ ] **Step 6: Wire the toggle and auto-open behavior in `MainActivity.kt`**

Add to `onCreate`, near the existing `b.cbTrim.setOnCheckedChangeListener` block:

```kotlin
        b.cbSampleMode.isChecked = Prefs.sampleMode
        b.cbSampleMode.setOnCheckedChangeListener { _, checked ->
            Prefs.sampleMode = checked
            if (checked) {
                b.cbTrim.isChecked = false
                b.cbTrim.isEnabled = false
                b.llTrimFields.visibility = View.GONE
            } else {
                b.cbTrim.isEnabled = true
            }
        }
```

Change `doSubmit` from:

```kotlin
    private fun doSubmit(urls: List<String>) {
        val start = b.etTrimStart.text.toString().takeIf { b.cbTrim.isChecked && it.isNotBlank() }
        val end = b.etTrimEnd.text.toString().takeIf { b.cbTrim.isChecked && it.isNotBlank() }
        vm.submitUrls(urls, start, end)
    }
```

to:

```kotlin
    private fun doSubmit(urls: List<String>) {
        val start = b.etTrimStart.text.toString().takeIf { b.cbTrim.isChecked && it.isNotBlank() }
        val end = b.etTrimEnd.text.toString().takeIf { b.cbTrim.isChecked && it.isNotBlank() }
        vm.submitUrls(urls, start, end, sampleMode = Prefs.sampleMode)
    }
```

This only fires from the single-URL submit path in `btnDownload`'s click listener (`if (singles.isNotEmpty()) submitWithDupeCheck(singles)` → `doSubmit`), not from `PlaylistPreviewActivity` (which calls `DownloadManager.submitUrls(selected)` directly with `sampleMode` defaulting to `false`) — matching the design doc's "no auto-chaining editors across a playlist" decision without needing any playlist-specific code.

Add auto-open logic to the existing `vm.downloads.collect` block:

```kotlin
        val autoOpenedIds = mutableSetOf<String>()
        lifecycleScope.launch {
            vm.downloads.collect { items ->
                adapter.submitList(items.toList())
                items.forEach { item ->
                    if (item.sampleMode && item.status == DownloadStatus.DONE &&
                        item.filePath != null && item.id !in autoOpenedIds
                    ) {
                        autoOpenedIds += item.id
                        openSampleEditor(item.filePath, item.title, item.id)
                    }
                }
            }
        }
```

(This replaces the existing simpler `lifecycleScope.launch { vm.downloads.collect { adapter.submitList(it.toList()) } }` block.)

Add the shared launch helper (also used by the manual Edit button in Step 8):

```kotlin
    private fun openSampleEditor(filePath: String, title: String, historyId: String?) {
        startActivity(
            Intent(this, SampleEditorActivity::class.java)
                .putExtra("filePath", filePath)
                .putExtra("title", title)
                .putExtra("historyId", historyId)
        )
    }
```

- [ ] **Step 7: Add the Edit button to `item_download.xml`**

Add next to the existing `btn_retry` button in `item_download.xml`:

```xml
    <Button
        android:id="@+id/btn_edit_sample"
        android:layout_width="wrap_content"
        android:layout_height="32dp"
        android:layout_marginTop="6dp"
        android:backgroundTint="#2A2A2A"
        android:text="Edit"
        android:textColor="#CC0000"
        android:textSize="12sp"
        android:textAllCaps="false"
        android:visibility="gone" />
```

- [ ] **Step 8: Wire the Edit button in `DownloadAdapter.kt`**

Change the `DownloadAdapter` constructor to accept a new callback:

```kotlin
class DownloadAdapter(
    private val onRetry: (String) -> Unit,
    private val onErrorClick: (String) -> Unit,
    private val onPlayClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit,
    private val onEditClick: (DownloadItem) -> Unit
) : ListAdapter<DownloadItem, DownloadAdapter.VH>(DIFF) {
```

In `VH.bind`, add:

```kotlin
            b.btnEditSample.visibility = if (item.status == DownloadStatus.DONE && item.filePath != null) View.VISIBLE else View.GONE
            b.btnEditSample.setOnClickListener { onEditClick(item) }
```

In `MainActivity.kt`, update the adapter construction:

```kotlin
    private val adapter = DownloadAdapter(
        onRetry      = { id  -> vm.retry(id) },
        onErrorClick = { msg -> showErrorDialog(msg) },
        onPlayClick  = { fp  -> playFile(fp) },
        onLongClick  = { url -> copyUrlToClipboard(url) },
        onEditClick  = { item -> item.filePath?.let { openSampleEditor(it, item.title, item.id) } }
    )
```

- [ ] **Step 9: Render `SAMPLE`-kind rows distinctly in `HistoryActivity.kt`**

In `HistoryAdapter.VH.bind`, change:

```kotlin
            fun bind(r: HistoryRecord) {
                b.tvHistoryTitle.text = r.title.ifBlank { r.url }
                b.tvHistoryMeta.text = "${fmt.format(Date(r.timestamp))} • ${r.status.lowercase()}"
```

to:

```kotlin
            fun bind(r: HistoryRecord) {
                b.tvHistoryTitle.text = r.title.ifBlank { r.url }
                val kindLabel = if (r.kind == "SAMPLE") "sample • " else ""
                b.tvHistoryMeta.text = "$kindLabel${fmt.format(Date(r.timestamp))} • ${r.status.lowercase()}"
```

- [ ] **Step 10: Build to verify everything compiles together**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, produces `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/ytmp3/Prefs.kt app/src/main/java/com/ytmp3/DownloadItem.kt app/src/main/java/com/ytmp3/DownloadManager.kt app/src/main/java/com/ytmp3/DownloadViewModel.kt app/src/main/java/com/ytmp3/MainActivity.kt app/src/main/java/com/ytmp3/DownloadAdapter.kt app/src/main/java/com/ytmp3/HistoryActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/layout/item_download.xml
git commit -m "Wire sample mode into MainActivity, DownloadManager, DownloadAdapter, and History"
```

---

## Task 10: On-emulator manual QA pass

Not a code task — this is the verification pass called for in the design doc, run against the AVD already provisioned (`ytmp3_test`, Pixel 6 profile, Android 35 Google Play x86_64 image with ARM translation for the app's bundled `arm64-v8a`/`armeabi-v7a` native libs).

- [ ] **Step 1: Start the emulator**

Run: `ANDROID_HOME=/mnt/Storage1/android-sdk ANDROID_AVD_HOME=/mnt/Storage1/android-avd /mnt/Storage1/android-sdk/emulator/emulator -avd ytmp3_test -no-snapshot &`

- [ ] **Step 2: Install and launch**

Run: `ANDROID_HOME=/mnt/Storage1/android-sdk adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.ytmp3/.MainActivity`

- [ ] **Step 3: Walk through the QA checklist from the design doc**

For each item below, use `adb shell input` for taps/swipes or `adb exec-out screencap -p > /tmp/screen.png` to capture and inspect state:

- Short track (~3 min) sample-mode download → editor auto-opens → waveform renders → create 2 regions → export → both WAV files appear under the Samples output location.
- Long/podcast-length track (~60+ min) → peak extraction completes in reasonable time without OOM (watch `adb logcat` for `OutOfMemoryError`).
- Playlist run with sample mode on → confirm editor does NOT auto-open per track, but the Edit button appears on each finished item and opens the editor correctly when tapped.
- Waveform accuracy: pick a track with an obvious transient (e.g. a drum hit or vocal onset) and confirm the rendered waveform's visual peak lines up with what's audible at that timestamp during preview playback.
- Sample-accurate cut points: export a region, play back the resulting WAV, confirm no audible pre-roll silence or truncation at the boundaries.
- Storage-warning threshold: fill emulator storage close to the ~50MB WAV-export floor (`adb shell dd if=/dev/zero of=/sdcard/filler bs=1M count=<N>`) and confirm the export button correctly blocks with the low-storage dialog instead of silently failing.
- "Keep full track" dialog: verify both branches — Keep leaves the MP3 in place and playable from the downloads list; Delete removes the file and the History row shows `SAMPLED` instead of `DONE`.
- `Prefs.sampleMode` persistence: toggle it on, force-stop the app (`adb shell am force-stop com.ytmp3`), relaunch, confirm the checkbox is still checked.

- [ ] **Step 4: Fix any issues found, following systematic-debugging for each**

Not pre-scriptable — apply `superpowers:systematic-debugging` to whatever surfaces here rather than guessing at fixes.

- [ ] **Step 5: Final commit once QA passes clean**

```bash
git add -A
git commit -m "Phase 1 QA pass: fixes from on-emulator verification" --allow-empty
```
