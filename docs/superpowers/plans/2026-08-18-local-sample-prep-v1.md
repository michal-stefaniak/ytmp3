# Local Sample Prep V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the offline, local-audio sample-prep workflow defined in the V1 design.

**Architecture:** Persist non-destructive projects, sample records, and packs in SQLite; retain only user-selected source URIs. Feed decoded PCM into bounded streaming analysis and pure smart-chop math. The editor owns project-region state, while library and pack export consume persisted records.

**Tech Stack:** Kotlin, Android ViewBinding, SQLiteOpenHelper, coroutines, WorkManager, Android Storage Access Framework, bundled ffmpeg.

**Spec:** `docs/superpowers/specs/2026-08-18-local-sample-prep-v1-design.md`

## Global Constraints

- Min SDK 24, Kotlin/JVM 17, and ViewBinding; no Compose.
- All media analysis and processing is on-device; no account, analytics, network API, upload, or download feature.
- Use SAF URI grants only; do not request broad storage access.
- Preserve the user source and apply recipes only to previews/exports.
- WAV and FLAC are the only V1 export formats; collisions receive a numeric suffix.
- Every task is test-first and is committed separately after its focused checks pass.

---

## File structure

- `ProjectModels.kt`: stable data classes and recipe validation.
- `ProjectDb.kt`: schema v1 for projects, regions, samples, packs and pack membership.
- `AudioAnalysis.kt`: streaming peak folding and pure analysis helpers.
- `SmartChop.kt`: pure transient, silence and grid region generation.
- `ProjectImportActivity.kt`/`MainActivity.kt`: import and Share routing.
- `SampleEditorActivity.kt`/`WaveformView.kt`: persistent edit state and smart chop controls.
- `LibraryActivity.kt`/`PackBuilderActivity.kt`: local browse and pack curation.
- `PackExportWorker.kt`/`SampleExporter.kt`: persistent folder/ZIP export.

### Task 1: Persistent project domain

**Files:**
- Create: `app/src/main/java/com/ytmp3/ProjectModels.kt`
- Create: `app/src/main/java/com/ytmp3/ProjectDb.kt`
- Create: `app/src/test/java/com/ytmp3/ProjectModelsTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces `data class SampleProject`, `SampleRegion`, `SampleRecord`, `SamplePack`, and `ProcessingRecipe`.
- Produces `ProjectDb.upsertProject(project)`, `saveRegions(projectId, regions)`, `getProject(id)`, `listProjects()`, `upsertSample(sample)`, and `listSamples()`.

- [ ] **Step 1: Write failing domain tests**

```kotlin
@Test fun `recipe clamps fades to its region duration`() {
    assertEquals(250L, ProcessingRecipe(fadeInMs = 999).validated(250).fadeInMs)
}

@Test fun `region validation rejects overlap`() {
    assertFalse(SampleRegion.validateOrdered(listOf(SampleRegion("a", 0, 100), SampleRegion("b", 99, 200))))
}
```

- [ ] **Step 2: Run the test to verify failure**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest --tests com.ytmp3.ProjectModelsTest`

- [ ] **Step 3: Implement the pure models and database**

```kotlin
data class ProcessingRecipe(val fadeInMs: Long = 0, val fadeOutMs: Long = 0,
    val normalise: Boolean = false, val mono: Boolean = false, val reverse: Boolean = false) {
    fun validated(durationMs: Long) = copy(
        fadeInMs = fadeInMs.coerceIn(0, durationMs),
        fadeOutMs = fadeOutMs.coerceIn(0, durationMs)
    )
}

data class SampleRegion(val id: String, val startMs: Long, val endMs: Long,
    val label: String = "", val recipe: ProcessingRecipe = ProcessingRecipe()) {
    companion object {
        fun validateOrdered(regions: List<SampleRegion>) = regions.zipWithNext()
            .all { (a, b) -> a.startMs >= 0 && a.endMs > a.startMs && a.endMs <= b.startMs }
    }
}
```

Use a `SQLiteOpenHelper` named `projects.db`, version 1. Define explicit tables `projects`, `regions`, `samples`, `packs`, and `pack_samples`; use transactions for a project with its regions.

- [ ] **Step 4: Run focused tests**

Run the Task 1 command and `./gradlew :app:assembleDebug` with the same environment.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/ytmp3/ProjectModels.kt app/src/main/java/com/ytmp3/ProjectDb.kt app/src/test/java/com/ytmp3/ProjectModelsTest.kt
git commit -m "Add persistent sample project models"
```

### Task 2: Local intake and project creation

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/ytmp3/MainActivity.kt`
- Modify: `app/src/main/java/com/ytmp3/SampleEditorActivity.kt`

**Interfaces:**
- Consumes `ProjectDb.upsertProject` and `SampleProject(id, sourceUri, title, ...)`.
- Produces `MainActivity.openProject(projectId)` and editor intent extra `projectId`.

- [ ] **Step 1: Add failing intent-routing tests**

```kotlin
@Test fun `audio share intent yields every stream uri`() {
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uriA, uriB))
    assertEquals(listOf(uriA, uriB), ImportUris.fromIntent(intent))
}
```

- [ ] **Step 2: Run the new test and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.ytmp3.ImportUrisTest`

- [ ] **Step 3: Implement `ImportUris` and route import/share**

```kotlin
object ImportUris {
    fun fromIntent(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtraCompat(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtraCompat(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else -> emptyList()
    }
}
```

Add `audio/*` SEND and SEND_MULTIPLE intent filters. Persist the read grant when offered, create one project per URI, and open the first project. Keep ordinary YouTube URL handling out of the Play-product entry flow.

- [ ] **Step 4: Verify routing and build**

Run Task 2 tests and `./gradlew :app:assembleDebug`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/ytmp3/MainActivity.kt app/src/main/java/com/ytmp3/SampleEditorActivity.kt app/src/main/java/com/ytmp3/ImportUris.kt app/src/test/java/com/ytmp3/ImportUrisTest.kt
git commit -m "Create projects from imported audio"
```

### Task 3: Bounded analysis and smart chop math

**Files:**
- Create: `app/src/main/java/com/ytmp3/AudioAnalysis.kt`
- Create: `app/src/main/java/com/ytmp3/SmartChop.kt`
- Create: `app/src/test/java/com/ytmp3/SmartChopTest.kt`
- Modify: `app/src/main/java/com/ytmp3/WaveformExtractor.kt`

**Interfaces:**
- Produces `StreamingPeakReducer(bucketCount).accept(sample)` and `.finish(): List<PeakBucket>`.
- Produces `SmartChop.bySilence(envelope, threshold, minGapMs)`, `byTransients(envelope, sensitivity, minSpacingMs)`, and `byGrid(durationMs, bpm, subdivision)`.

- [ ] **Step 1: Write failing smart-chop tests**

```kotlin
@Test fun `silence creates regions around quiet gaps`() {
    assertEquals(listOf(0L to 100L, 200L to 300L), SmartChop.bySilence(listOf(1f, 0f, 1f), .1f, 50, 100))
}

@Test fun `grid at 120 bpm has 500ms quarter notes`() {
    assertEquals(listOf(0L to 500L, 500L to 1000L), SmartChop.byGrid(1000, 120f, 1))
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.ytmp3.SmartChopTest`

- [ ] **Step 3: Implement pure helpers and stream ffmpeg output**

```kotlin
object SmartChop {
    fun byGrid(durationMs: Long, bpm: Float, subdivision: Int): List<Pair<Long, Long>> {
        val step = (60_000f / bpm / subdivision).toLong().coerceAtLeast(50)
        return generateSequence(0L) { it + step }.takeWhile { it < durationMs }
            .map { it to minOf(it + step, durationMs) }.toList()
    }
}
```

Refactor `FFmpegBinary`/`WaveformExtractor` so stdout is consumed in chunks and reduced during read, not retained as a whole byte array. Preserve cancellation and concurrent stderr draining.

- [ ] **Step 4: Run tests and debug build**

Run Task 3 tests, existing peak tests, and `./gradlew :app:assembleDebug`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ytmp3/AudioAnalysis.kt app/src/main/java/com/ytmp3/SmartChop.kt app/src/main/java/com/ytmp3/WaveformExtractor.kt app/src/main/java/com/ytmp3/FFmpegBinary.kt app/src/test/java/com/ytmp3/SmartChopTest.kt
git commit -m "Add streaming waveform analysis and smart chop math"
```

### Task 4: Persistent editor controls

**Files:**
- Modify: `app/src/main/java/com/ytmp3/SampleEditorActivity.kt`
- Modify: `app/src/main/java/com/ytmp3/WaveformView.kt`
- Modify: `app/src/main/res/layout/activity_sample_editor.xml`

**Interfaces:**
- Consumes `SampleProject`, `SampleRegion`, `ProjectDb.saveRegions`, and `SmartChop`.
- Produces saved edits, Smart Chop mode selection, undo/redo, and a beat-grid rendering input.

- [ ] **Step 1: Add failing region-history unit test**

```kotlin
@Test fun `undo restores prior regions`() {
    val history = RegionHistory(listOf(regionA)); history.push(listOf(regionB));
    assertEquals(listOf(regionA), history.undo())
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.ytmp3.RegionHistoryTest`

- [ ] **Step 3: Implement controls and persistence**

```kotlin
class RegionHistory(initial: List<SampleRegion>) {
    private val undo = ArrayDeque<List<SampleRegion>>(); private var current = initial
    fun push(next: List<SampleRegion>) { undo.addLast(current); current = next }
    fun undo(): List<SampleRegion> = undo.removeLastOrNull()?.also { current = it } ?: current
}
```

Add Smart Chop, Undo, Redo and beat-grid controls. Every committed region edit calls `ProjectDb.saveRegions`; restore them in `onCreate`. Keep all suggestions editable and never overwrite the source.

- [ ] **Step 4: Verify**

Run Task 4 test, all unit tests, and assemble debug.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ytmp3/SampleEditorActivity.kt app/src/main/java/com/ytmp3/WaveformView.kt app/src/main/res/layout/activity_sample_editor.xml app/src/main/java/com/ytmp3/RegionHistory.kt app/src/test/java/com/ytmp3/RegionHistoryTest.kt
git commit -m "Persist editable smart chop projects"
```

### Task 5: Library and pack model

**Files:**
- Create: `app/src/main/java/com/ytmp3/LibraryActivity.kt`
- Create: `app/src/main/java/com/ytmp3/PackBuilderActivity.kt`
- Create: `app/src/main/res/layout/activity_library.xml`
- Create: `app/src/main/res/layout/activity_pack_builder.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/ytmp3/ProjectDb.kt`

**Interfaces:**
- Consumes `ProjectDb.listProjects`, `listSamples`, `upsertSample`, and `SamplePack`.
- Produces a pack with ordered sample IDs and `PackExportRequest`.

- [ ] **Step 1: Add failing filename-template tests**

```kotlin
@Test fun `template substitutes sequence and BPM`() {
    assertEquals("Kick_01_128.wav", FilenameTemplate("{label}_{n}_{bpm}").render("Kick", 1, 128f, null))
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.ytmp3.FilenameTemplateTest`

- [ ] **Step 3: Implement local browsing and curation**

```kotlin
class FilenameTemplate(private val raw: String) {
    fun render(label: String, number: Int, bpm: Float?, key: String?) = raw
        .replace("{label}", label).replace("{n}", "%02d".format(number))
        .replace("{bpm}", bpm?.toInt()?.toString() ?: "").replace("{key}", key ?: "")
}
```

Expose project/sample search, favourite/tag filters, source-project navigation, and a pack-builder list with reordering. Do not add account or network code.

- [ ] **Step 4: Verify**

Run Task 5 tests and `./gradlew :app:assembleDebug`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ytmp3/LibraryActivity.kt app/src/main/java/com/ytmp3/PackBuilderActivity.kt app/src/main/java/com/ytmp3/FilenameTemplate.kt app/src/main/java/com/ytmp3/ProjectDb.kt app/src/main/res/layout/activity_library.xml app/src/main/res/layout/activity_pack_builder.xml app/src/main/AndroidManifest.xml app/src/test/java/com/ytmp3/FilenameTemplateTest.kt
git commit -m "Add local sample library and pack builder"
```

### Task 6: Pack export

**Files:**
- Create: `app/src/main/java/com/ytmp3/PackExportWorker.kt`
- Modify: `app/src/main/java/com/ytmp3/SampleExporter.kt`
- Modify: `app/src/main/java/com/ytmp3/PackBuilderActivity.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes `PackExportRequest(packId, destinationUri, format, sampleRate, bitDepth)`.
- Produces WorkManager progress and an output folder/ZIP URI.

- [ ] **Step 1: Add failing output-name tests**

```kotlin
@Test fun `collision appends sequence suffix`() {
    assertEquals("Kick_02.wav", OutputNames.unique("Kick.wav", setOf("Kick.wav", "Kick_01.wav")))
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.ytmp3.OutputNamesTest`

- [ ] **Step 3: Implement export worker**

```kotlin
class PackExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching {
        setProgress(workDataOf("completed" to 0)); /* render each region, then ZipOutputStream */
        Result.success()
    }.getOrElse { Result.failure(workDataOf("error" to (it.message ?: "Export failed"))) }
}
```

Use SAF output streams or app-scoped output, generate a unique name rather than overwrite, render WAV/FLAC through bundled ffmpeg, write an optional ZIP with `ZipOutputStream`, and expose Android Share after a successful worker result. Add WorkManager KTX and run long work in the foreground with a notification.

- [ ] **Step 4: Verify**

Run Task 6 tests, all unit tests and `./gradlew :app:assembleDebug`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/ytmp3/PackExportWorker.kt app/src/main/java/com/ytmp3/SampleExporter.kt app/src/main/java/com/ytmp3/PackBuilderActivity.kt app/src/main/java/com/ytmp3/OutputNames.kt app/src/test/java/com/ytmp3/OutputNamesTest.kt
git commit -m "Export local sample packs in the background"
```

## Final verification

- [ ] Run `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/mnt/Storage1/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Install on a device and verify import, Share audio, source relink, all Smart Chop modes, project restore, library search, WAV/FLAC folder export, ZIP export, and Android Share with networking disabled.
- [ ] Verify no INTERNET permission or networking dependency is present in the Play-product manifest/build graph.
