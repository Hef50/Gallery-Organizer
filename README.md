# Gallery Organizer

A local-first photo and video organizer for Android. It **does not store your media** —
it reads the device's existing library through `MediaStore` (the same index Samsung
Gallery uses) and adds the layer both Samsung Gallery and Google Photos lack:

- **Hierarchical tags** — `Travel/Japan/Kyoto`, not a flat soup of albums.
- **Bulk tagging** — select a few hundred photos, two taps, one database transaction.
- **Saved smart searches** — tag `AND`/`OR`/`NOT` plus date range, media type, folder
  and full-text search, saved as smart albums that stay live.

It is built for a 150,000+ item library on a Samsung Galaxy S25 Ultra.

## Principles

- **Fully offline.** The app has no `INTERNET` permission at all. Nothing is uploaded,
  nothing is fetched, there is no account.
- **Zero cost.** No cloud backend, no hosted inference, no paid dependencies.
- **Your files are never touched.** The app never deletes or overwrites media. The one
  feature that writes to files at all (XMP tag write-back) is opt-in, writes to a temp
  file, verifies it, and only then replaces.
- **Tags live in the app's database**, keyed by a content hash rather than a MediaStore
  id, so they survive reindexing, file moves, reinstalls and id churn.

## Install

Every push builds a signed debug APK. Grab it from the **Actions** tab → latest *Build*
run → **Artifacts** → `app-debug`. The signing key is committed, so each new build
installs straight over the previous one.

## Build

```bash
./gradlew testDebugUnitTest     # JVM unit tests, no device or emulator needed
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

Requires JDK 17+ and an Android SDK with platform 35. Point `ANDROID_HOME` at it, or put
`sdk.dir=/path/to/sdk` in `local.properties`.

## Layout

```
app/src/main/java/com/galleryorganizer/
  data/db/        Room entities, DAOs, migrations — source of truth for tags
  data/media/     MediaStore access, content hashing
  data/repo/      Repositories the UI and workers talk to
  data/backup/    JSON export/import of the whole tag database
  domain/         Models and the search-query language
  work/           WorkManager workers: indexing, hashing, auto-tagging, XMP
  ui/             Compose screens
```

Design notes live in [`DECISIONS.md`](DECISIONS.md); anything genuinely ambiguous is
logged in [`OPEN_QUESTIONS.md`](OPEN_QUESTIONS.md). The working brief is
[`CLAUDE.md`](CLAUDE.md).
