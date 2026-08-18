# Local Sample Prep V1 — Design

## Product

The Play Store edition of ytmp3 is an offline Android companion for FL Studio
Mobile. It turns audio the user already owns or is licensed to use into clean,
organised sample packs. It does not download, stream, host, synchronise, or
upload media.

The existing YouTube downloader is out of scope for this product and must not
be presented as part of the Play Store edition.

## Goals

- Import one or more local audio files, including Android Share targets.
- Suggest useful chops automatically, then let the user refine every suggestion.
- Preserve source files and edits non-destructively.
- Make packs easy to organise, export as WAV/FLAC, zip, and hand off to FL
  Studio Mobile or another Android app.
- Keep all processing and metadata on-device, with no account, advertising,
  subscription, or cloud service.

## Non-goals

- Online media downloading, online source lookup, recording, server backup,
  social/community features, user submissions, sample marketplace, pads,
  sequencing, MIDI, and an effects rack.
- Guaranteed music-key detection. Key is a best-effort hint, not a label of
  fact.
- Replacing a DAW or sampler.

## Main flow

```
Import / Android Share
  -> project and local source reference
  -> streaming on-device audio analysis
  -> smart-chop suggestions and waveform editing
  -> saved local project and sample library
  -> pack builder
  -> WAV/FLAC folder and ZIP export
  -> Android Share / open destination
```

## Intake

`MainActivity` becomes the local library home. It accepts `audio/*` using
`ACTION_OPEN_DOCUMENT` and `ACTION_SEND`/`ACTION_SEND_MULTIPLE`. Persistent
URI grants are retained where the document provider supports them. Files are
never copied permanently by import; a temporary cache staging copy is only used
when native ffmpeg needs a filesystem path.

Multiple imports create independent projects. Importing is allowed without an
account or network access.

## Projects and samples

Projects are persistent, non-destructive edit documents. Each contains:

- an ID, title, source URI/path, import time, and optional source fingerprint;
- waveform analysis cache and source duration;
- BPM estimate plus a user override, and a best-effort key estimate;
- ordered regions, labels, tags, favourite state, and processing recipe;
- edit history sufficient for undo/redo during an editing session.

Exported samples retain their project ID, time range, local output URI/path,
duration, format, tags, and favourite state. A pack is a named ordered set of
sample IDs plus export settings. Projects, samples and packs reside in a SQLite
database; source audio remains at its user-selected location.

## Analysis and smart chop

Audio decoding remains ffmpeg-based, but PCM is folded into fixed waveform
buckets while it is read rather than accumulated as one giant byte array. This
keeps memory bounded for long sources.

Analysis runs entirely on-device and produces:

- min/max waveform peaks;
- silence spans using an adjustable RMS threshold and minimum-gap duration;
- transient candidates from an onset/energy-delta envelope with adjustable
  sensitivity and minimum spacing;
- a BPM candidate based on onset autocorrelation, with confidence and manual
  override; and
- a best-effort chroma-based key candidate, clearly labelled as an estimate.

The Smart Chop sheet offers three modes: **Transients**, **Silence**, and
**Grid**. Grid slices use the manual BPM if set, otherwise an estimate. All
results are merely suggested regions; the user can remove, merge, resize, add,
label, or reorder them before exporting. Regions remain in time order, stay in
source bounds, and do not overlap.

## Editor

The editor displays a waveform, optional beat grid, editable regions, and a
transport preview. It supports pinch zoom/pan, handle drag, tap-to-loop/tap-to-
stop, long-press delete, undo/redo, and fine nudge controls. Snap modes are
Off, zero-crossing, beat grid, or both where applicable.

Each selected region has an optional lightweight preparation recipe:

- trim leading/trailing silence;
- short fade-in/fade-out;
- gain or peak-normalise;
- mono/stereo conversion;
- reverse; and
- pitch shift or tempo change with clear, conservative limits.

Recipes are stored as settings and applied only during preview/export; the
source is never overwritten.

## Local library and pack builder

The home library lists projects and exported samples with search and filters for
tag, favourite, BPM, key, duration, and format. It supports local collections
and source-aware navigation back to the parent project.

The pack builder is a dedicated screen. Users choose samples, set the pack
name, order, tags, filename template, destination, WAV/FLAC format, sample
rate, bit depth, and mono/stereo output. Filename templates expose sequence,
sample label, BPM, key, and source title.

The output is a named folder and optional ZIP. Export completes with Android's
native share/open flow, so the user can hand the pack to FL Studio Mobile or a
file manager.

## Background execution and failure handling

Analysis and export use persistent background work. Long-running operations
show a foreground notification with progress and may resume after the UI is
closed. The work records per-region results; an individual export failure does
not discard other completed files. Temporary staging/export files are cleaned
on success, cancellation, and the next app launch.

Missing/revoked imported documents show a clear relink action rather than
silently deleting the project. Export collision policy is deterministic:
generate a numbered filename rather than overwriting existing user files.

## Storage and privacy

No source audio or metadata leaves the device. The app requests only the
Storage Access Framework URI chosen by the user; it does not request broad file
access. The Play listing and in-app privacy policy state that audio is processed
locally and that there are no accounts or analytics SDKs.

## Testing

- Pure JVM tests cover streaming peak folding, transient/silence segmentation,
  BPM-grid math, filenames, recipe construction, and project/pack validation.
- Database tests cover migration and project/sample/pack relationships.
- Instrumented tests cover import/share routing, persistent URI handling,
  project restore, and background export state.
- Manual device QA covers short/long audio, SAF providers, interrupted export,
  FL Studio Mobile/file-manager handoff, and offline operation.

## Release criteria

V1 is ready when a user can import owned audio, generate/edit smart chops, save
the project, build a named pack, export a WAV/FLAC ZIP, and share it from an
offline device without creating an account or granting broad storage access.
