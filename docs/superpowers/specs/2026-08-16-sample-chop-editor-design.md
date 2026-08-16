# Sample Chop Editor — Design (Phase 1 of the sample-maker pivot)

## Context

`ytmp3` is currently a general-purpose YouTube→MP3 downloader (yt-dlp + ffmpeg via
`youtubedl-android`). The mich.lol downloads page already advertises it as a tool
that "extracts audio samples for music production from YouTube videos and local
files" — a promise the app doesn't fulfill yet. Today's only trim mechanism is two
plain-text `HH:MM:SS` fields feeding yt-dlp's `--download-sections`, downloading
only the pre-selected range with no visual feedback.

This is phase 1 of a 4-phase pivot toward a real sample-making tool:

1. **Waveform-based visual chopping** (this doc)
2. Local file import
3. BPM/key detection + beat-snapping
4. Sample library & organization

Phases 2-4 are out of scope here and deliberately not designed yet — each gets its
own spec once the prior phase has shipped.

## Goals

- Replace blind timestamp trimming with a waveform the user can see and drag on.
- Let a user cut multiple sample regions out of one downloaded track in one pass.
- Export cuts as sample-accurate WAV, suitable for dropping straight into a DAW/sampler.
- Don't force every download through the new flow — it's an opt-in mode alongside
  today's normal download behavior.

## Non-goals (deferred to later phases)

- No local file import (phase 2).
- No BPM/key detection or beat-snapping (phase 3) — regions are freehand drags in phase 1.
- No sample library/tagging/browsing UI (phase 4) — exported samples are discoverable
  via the existing History screen and the filesystem, nothing fancier.

## UX flow

`MainActivity` gains a **"Sample mode"** toggle (`Prefs.sampleMode`, persisted),
shown next to today's "Trim audio" checkbox. The two are mutually exclusive — trim
fields hide when sample mode is on.

- **Single URL, sample mode on:** submits through the existing `DownloadManager`
  pipeline unchanged, except with no `--download-sections` (always fetches the full
  track). On `DownloadStatus.DONE`, `SampleEditorActivity` opens automatically for
  that file.
- **Playlist URL, sample mode on:** unchanged batch-download flow via
  `PlaylistPreviewActivity`. No auto-chaining editors across a whole playlist —
  each finished item in the downloads list gets a new waveform-icon "Edit" action
  (next to the existing play-on-tap behavior) that opens `SampleEditorActivity`
  for that file on demand.
- **Sample mode off:** behaves exactly as today (including the old trim checkbox).

## Components

### `SampleEditorActivity` (new)

Opens with a file path. Responsibilities:

1. Decode the audio to peak data on `Dispatchers.IO` (see Waveform extraction below),
   showing a progress spinner while extracting.
2. Render peaks via a new `WaveformView` (custom `View`, `Canvas`-based):
   horizontal pan/zoom, fixed peak-bucket count independent of track length (so a
   90-minute file costs the same render/memory budget as a 3-minute one).
3. Let the user create multiple regions by drag: each is a
   `RegionMarker(id: String, startMs: Long, endMs: Long, label: String)` held in
   the activity's view-model-less state (a simple `mutableListOf` is enough here —
   no persistence needed until export). Regions render as highlighted bands with
   draggable start/end handles; handles are clamped to track bounds and to a
   minimum region length (50ms) to prevent zero-length or inverted exports.
4. Tapping a region loop-previews just that slice (bounded-range playback via
   `MediaPlayer.seekTo` + a coroutine that loops back to `startMs` on reaching
   `endMs`).
5. "Export" button: for each region, shells out to the bundled ffmpeg binary to cut
   a sample-accurate WAV (see Export below), then shows a "Keep full track too?"
   dialog (Yes/No) once, after all regions are exported.

### Waveform extraction

Reuses the ffmpeg binary already bundled via
`io.github.junkfood02.youtubedl-android:ffmpeg` (no new dependency). Runs:

```
ffmpeg -i <input> -f s16le -ac 1 -ar 8000 -acodec pcm_s16le pipe:1
```

— downsampled mono 8kHz PCM is plenty for a peak overview and keeps the decode
fast and memory-light regardless of source quality. The raw PCM stream is read in
Kotlin and reduced to a fixed number of min/max peak pairs (e.g. 2000 buckets,
enough resolution at any zoom level `WaveformView` will realistically render at).

### Export

Also via ffmpeg, one invocation per region:

```
ffmpeg -i <input> -ss <startMs/1000> -to <endMs/1000> -c:a pcm_s16le <output>.wav
```

WAV, not MP3 — avoids MP3 encoder pre-roll/padding at cut points, which matters
for loops that need to sit cleanly on a DAW grid. The existing full-track MP3
download is untouched by this; WAV only applies to exported sample regions.

Output location: a new `Samples` subfolder alongside the existing `Music` folder,
using the same SAF-tree-or-app-storage logic `DownloadManager.copyToSaf` already
implements today (no new storage-permission model needed).

### "Keep full track?" dialog

Shown once per editor session, after export completes:

- **Yes:** no-op — the full track stays exactly where `DownloadManager` already
  put it (today's behavior, already visible in History as a `DONE` entry).
- **No:** deletes the full-track file from disk. The History row for it is
  **not** deleted (it stays as a record that the URL was processed) but its
  `status` moves to a new terminal value `SAMPLED` so `HistoryAdapter` can render
  it distinctly from `DONE` (which implies the full file still exists and is
  playable) instead of leaving a dead "tap to play" row pointing at a deleted file.

### History integration

`HistoryDb` schema gains one column: `kind TEXT NOT NULL DEFAULT 'FULL_TRACK'`
(`FULL_TRACK` | `SAMPLE`), plus nullable `parentId TEXT` for `SAMPLE` rows to
reference the track they were cut from. This is a genuine schema migration
(`onUpgrade`, bump version to 2) rather than the existing `DROP TABLE`
placeholder — history is data the user cares about, and this feature is the
first time upgrading it matters.

Each exported region gets its own `HistoryRecord` (`kind = SAMPLE`, `status =
DONE`, `filePath` = the WAV). `HistoryActivity`'s list keeps working unmodified
for `FULL_TRACK` rows; `SAMPLE` rows render with a small distinguishing label
(e.g. "sample of <parent title>") — this is the full extent of phase 1's history
UI change. No browsing/filtering/tagging (that's phase 4).

## Error handling

- ffmpeg failures (extraction or export) reuse `MainActivity`'s existing
  `showErrorDialog` pattern — same "OK / Copy error" shape the rest of the app
  already uses, so no new error-UI vocabulary.
- Extends `Prefs.storageWarn`'s existing low-storage check to run before export
  specifically, not just before download: uncompressed WAV is roughly 4x an
  equivalent MP3 (~40MB vs ~10MB for a 4-minute cut), so the existing 200MB
  threshold check needs to run again at this second point, not just at download
  start.
- Region handle dragging is clamped in `WaveformView` itself (can't cross the
  opposite handle, can't leave track bounds) so invalid regions are structurally
  unreachable rather than caught after the fact.

## Testing

No emulator-verifiable automated UI test suite exists in this repo today (plain
`ViewBinding` + manual wiring, no existing instrumented tests to extend). Phase 1
adds:

- Unit tests (JVM, no device needed) for the peak-reduction math (PCM bytes → N
  peak buckets) and for `RegionMarker` clamping logic — both are pure functions,
  cheap to test without Android.
- Manual on-emulator QA pass covering: short track, long/podcast-length track
  (peak-extraction performance), a playlist run, waveform accuracy against known
  transients, sample-accurate WAV cut points, the storage-warning threshold
  actually firing, both branches of the "keep full track" dialog, and that
  `Prefs.sampleMode` persists across app restart.

## Open implementation questions for the plan

- Exact zoom/pan gesture mapping in `WaveformView` (pinch-zoom vs. a separate
  zoom control) — left for the implementation plan, not a product decision.
- Whether region creation is "tap-to-place-then-drag-handles" or "long-press-drag
  to draw a region directly" — left for the implementation plan as a UI-feel
  detail, not a product decision.
