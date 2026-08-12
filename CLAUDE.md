# Gallery Organizer — Project Brief (condensed)

Local-first photo/video organizer for a Samsung Galaxy S25 Ultra. It **does not store
media**. It reads the device's existing library via **MediaStore** (the same index
Samsung Gallery uses) and adds what Samsung Gallery and Google Photos lack: fast
hierarchical tagging, bulk tagging, and saved smart searches.

## Hard constraints — never violate

- **ZERO COST.** No paid services, no Firebase, no cloud backend, no hosted inference,
  no paid dependencies, no Play Store submission. Free, open-source, offline only.
- **NO `INTERNET` permission** in the manifest for all of v1. The app works fully
  offline. If something seems to need network, stop and write it into
  `OPEN_QUESTIONS.md` instead of adding the permission.
- **Do NOT integrate the Google Photos API.** As of April 2025 Google removed the
  `photoslibrary` readonly/sharing/full scopes; the Library API only reaches
  app-created content and the Picker API only returns manually selected items.
  Google Photos is offsite backup only and is never read programmatically.
- **Never delete or overwrite user media.** Destructive MediaStore operations require
  an explicit user-consent intent and must sit behind a confirm dialog.
- **Room is the source of truth for tags. MediaStore is the source of truth for files.**
  Never store tags only in MediaStore.

## Scale requirement — the top engineering risk

Design for **150,000+ items** from day one.

- Indexing is **incremental and resumable**, driven by a stored `DATE_MODIFIED`
  watermark, running in WorkManager with a progress notification. **Never full-rescan
  on launch.**
- Batch DB writes in transactions of **~500 rows**.
- The grid uses **Paging 3 with a Room `PagingSource`**. Never load the full list into
  memory. Never decode full-size bitmaps for thumbnails.
- **Do not hash the whole library on first run.** Hash lazily: on the first tag write
  for an item, plus an idle-time background backfill worker.
- Use **SQLite FTS** for tag/filename/OCR text search, plus explicit indices on
  `date_taken`, `bucket_id`, and the cross-ref columns.
- Performance budget (tested/benchmarked where feasible): smooth grid scrolling, cold
  start < 1.5 s, tag queries < 50 ms at 150k rows, first full index of 50k items
  under ~10 minutes.

## Stack

Kotlin, Jetpack Compose (Material 3), Room + Paging 3, Coil, WorkManager,
Coroutines/Flow, ML Kit on-device (bundled models only, free) for labels and OCR.
`minSdk 33`. **Manual DI via a small `AppContainer`** — no Hilt unless justified in
`DECISIONS.md`. Gradle version catalog.

## Identity model

Tags must survive reindexing, file moves, reinstalls, and MediaStore ID churn. Key
each item by a **stable content hash**: file size + hash of the first 64 KB, plus
`date_taken` as a tiebreak. Store `MediaStore._ID` only as a **mutable cache column**
and reconcile it on every index pass.

## Schema (extend as needed; document changes)

```
media(id, content_hash UNIQUE, mediastore_id, uri, display_name, relative_path,
      bucket_id, mime, size, date_taken, date_modified, duration, width, height,
      is_missing)
tag(id, name, parent_id, color)          -- hierarchical
media_tag(media_id, tag_id, source)      -- source: manual | auto | imported
saved_search(id, name, query_json)
index_state(key, value)
media_fts(...)                           -- FTS over name, tags, ocr_text
```

## Phases — each independently shippable

| Phase | Scope |
|---|---|
| P0  | Scaffold, version catalog, CI, `CLAUDE.md`, `DECISIONS.md`, committed debug keystore (base64 in repo, wired into CI signing so every APK installs over the previous one) |
| P1  | Permissions: `READ_MEDIA_IMAGES`/`VIDEO`, plus `READ_MEDIA_VISUAL_USER_SELECTED` partial grants on Android 14+ |
| P2  | Room schema + DAOs + migration tests. Pure JVM tests, no device |
| P3  | `MediaIndexWorker`: incremental, resumable, chunked, progress notification. Tested with a fake `ContentProvider`/Robolectric |
| P4  | Paged Compose grid: date-sectioned, thumbnails, multi-select with long-press-to-drag range selection |
| P5  | Tagging: hierarchical tag manager + bulk tag sheet — tagging N selected items is 2 taps and one DB transaction. **Core feature** |
| P6  | Search: tag AND/OR/NOT, date range, media type, folder, FTS text. Saved searches as smart albums |
| P7  | Backup/restore: export the whole tag DB to JSON, import with hash-based rematching. **Critical — no cloud safety net** |
| P8  | XMP write-back (best effort): `dc:subject` for JPEG/PNG/HEIC, sidecar `.xmp` otherwise (incl. video). DB stays authoritative. Write temp → verify → replace |
| P9  | On-device auto-tagging: ML Kit labeling + OCR in an idle worker, `source='auto'`, never overwrites manual tags. User reviews/promotes suggestions |
| P10 | Polish: dark theme, empty states, duplicate finder via `content_hash`, "recently added", per-folder hiding |

## Process rules

- Work phase by phase; one commit (or set) per phase, clearly described, never bundled.
- Every phase ships unit tests that run on the JVM with no device — the sandbox has no
  emulator. `./gradlew testDebugUnitTest` must pass.
- CI (`.github/workflows/build.yml`) runs unit tests, `assembleDebug`, and uploads
  `app-debug.apk` as an artifact so the phone can install it directly.
- Append every non-obvious decision and trade-off to `DECISIONS.md`.
- Log anything uncertain about product behaviour in `OPEN_QUESTIONS.md` rather than
  guessing.
- If the sandbox network blocks a Gradle dependency, note the exact domain needed in
  `OPEN_QUESTIONS.md` and continue with what you can.

## Build commands

```bash
./gradlew testDebugUnitTest     # JVM unit tests — must always pass
./gradlew assembleDebug         # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew lint
```

The debug keystore lives at `keystore/debug.keystore.base64`; `app/build.gradle.kts`
decodes it at configure time so every debug APK — local or CI — is signed with the
same key and installs over the previous build.
