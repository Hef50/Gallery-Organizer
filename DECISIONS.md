# Decisions

Running log of non-obvious decisions and trade-offs. Newest phase last.

---

## P0 — Scaffold

### Single `app` module rather than a multi-module build
A `:core:db` / `:core:media` / `:feature:*` split would be tidier, but it costs
configuration time and a lot of Gradle boilerplate for a solo, single-surface app.
The package structure (`data/`, `domain/`, `work/`, `ui/`) already enforces the
layering. Revisit only if build times become a problem.

### Manual DI via `AppContainer`, no Hilt
Per the brief. The graph is small (a database, a handful of repositories, a WorkManager
handle) and entirely process-scoped, so a hand-written container is less machinery than
Hilt's annotation processing, and it keeps `kapt`/KSP work limited to Room. Workers get
their dependencies by reaching for `AppContainer` off the `Application` rather than via
`WorkerFactory`, which keeps worker construction trivially testable.

### `compileSdk 35`, `minSdk 33`
`minSdk 33` is from the brief and conveniently means the granular `READ_MEDIA_*`
permissions always exist — no legacy `READ_EXTERNAL_STORAGE` branch anywhere.
`compileSdk 35` is needed for the Android 14+ (`API 34`) partial-media-grant APIs and
the `RELEASE` MediaStore write-request helpers.

### Committed debug keystore
`keystore/debug.keystore.base64` is a base64 dump of a throwaway debug keystore with
the standard `androiddebugkey` / `android` credentials. `app/build.gradle.kts` decodes
it into `build/` at configure time and wires it as the `debug` signing config. Without
this, CI would generate a fresh random keystore per runner and every downloaded APK
would refuse to install over the previous one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
This key is worthless — it signs debug builds only and is deliberately public.

### One branch, phase-by-phase commits, one PR
The session is pinned to the branch `claude/android-photo-organizer-slemb0` and may not
push anywhere else, so the brief's "one PR per phase" is not physically available. Each
phase is instead a self-contained commit (or small run of commits) with its own message
describing what changed and how to verify it on-device, and the branch opens a single
PR. The phase boundaries are preserved in history and each commit builds and passes
tests on its own.

### Toolchain pinning
Java 17 toolchain (`jvmToolchain(17)`) even though the sandbox and CI run JDK 21 — AGP
and the Android bytecode target are happiest on 17, and pinning the toolchain means the
build does not drift when a runner image changes its default JDK.

---

## P1 — Permissions

### Two permission tiers, not one
`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` is the full grant. On Android 14+ the user can
instead pick "Select photos and videos", which grants only
`READ_MEDIA_VISUAL_USER_SELECTED` and makes MediaStore return exactly the items they
picked. The app treats that as a real, supported state rather than an error: indexing
still works, it just sees a subset, and the UI shows a persistent "manage selection"
affordance that re-fires `ACTION_PICK_IMAGES`-style reselection via the system
permission request. Anything that would be wrong on a partial grant (for example
"your library has N items") is phrased as "N selected items".

### `MediaPermissionState` is derived, not stored
Permission state is read from `PackageManager` on every resume rather than cached in
DataStore. The user can revoke in Settings at any time and the app must notice; a cached
copy is one more thing to get stale.

### `shouldShowRequestPermissionRationale` is only meaningful after a first ask
On a cold install it is `false`, which is indistinguishable from "permanently denied".
The app stores a single `has_asked_media_permission` flag in DataStore so it can tell
"never asked" from "denied twice, send them to Settings".

---

## P2 — Room schema

### Content hash = `size + SHA-256(first 64 KiB)`, `date_taken` as tiebreak
Hashing 150k full files is minutes of I/O and battery for no benefit. The first 64 KiB
of a JPEG/HEIC covers EXIF plus the start of entropy-coded data, and combining it with
the exact byte size makes an accidental collision vanishingly unlikely for real camera
output. `date_taken` is folded in as a final tiebreak so two byte-identical copies shot
at different times (rare, but real for burst exports) stay distinct. The hash is stored
as a hex string in `media.content_hash` (indexed, not unique — see below).

The pathological case is a family of files that share a size and a 64 KiB prefix but
differ later — for example, large videos from the same camera with identical container
headers. This is why `content_hash` is nullable and computed lazily rather than being
the primary key: an item is perfectly usable before it is hashed, and the collision path
degrades to "these two items share tags", not "data loss".

### Synthetic `media.id` primary key, `content_hash` as a secondary index
Cross-ref tables key on the compact `INTEGER` id rather than a 64-char hex string, which
keeps `media_tag` small and its indices fast. `content_hash` is the *stable identity*
used for backup/restore rematching; `id` is a local database detail that never leaves
the device.

### `mediastore_id` is a mutable cache column, not identity
MediaStore ids churn on rescan, SD-card remount, and factory reset. It is stored
(indexed, non-unique) purely so the indexer and Coil can address the file quickly, and
it is reconciled on every index pass. Nothing durable is ever keyed on it.

### Standalone FTS4 table, maintained by hand
`media_fts` is *not* a Room `contentEntity`-backed external-content table. The searchable
text spans three sources — `media.display_name`/`relative_path`, the joined `tag.name`
set, and `media.ocr_text` — and an external-content FTS table can only mirror a single
table. So `media_fts` is a plain FTS4 table whose `rowid` is `media.id`, rebuilt for a
row inside the same transaction as any write that changes its inputs. `MediaFtsDao`
centralises that so no caller can forget.

### FTS4 rather than FTS5
Room's `@Fts4` is supported on every API level the app targets without shipping a custom
SQLite build. FTS5 would give better ranking, but requires either API 32+ *and* the
system SQLite happening to include it, or bundling `requery`/`android-sqlite` — a
dependency and an APK-size cost for a ranking nicety. Noted in `OPEN_QUESTIONS.md`.

### `ocr_text` exists in v1 but is only filled in P9
Adding a nullable column now costs nothing and avoids a schema migration purely to make
room for a feature that is already specified. The FTS table indexes it from day one.

### `media_tag.source` as a string, not an enum ordinal
`manual` / `auto` / `imported` are written into the JSON backup verbatim. Storing an
ordinal would make the backup format brittle against reordering an enum. The Kotlin side
is still a sealed `TagSource` enum with an explicit stable `wire` string.

### Tag hierarchy: adjacency list, uniqueness per parent, roots at `parent_id = 0`
`tag(parent_id)` is a plain adjacency list. Depth is expected to be 2–3, so recursive
CTEs for subtree queries are cheap and the write path stays trivial. Uniqueness is
`(parent_id, name)` collated `NOCASE`, so `Travel/Japan` and `Food/Japan` coexist but
`Travel/japan` cannot be created twice. Roots use `parent_id = 0` rather than
`NULL`, because SQLite considers every `NULL` distinct and two root tags called
"Travel" would otherwise both satisfy the unique index. The cost is that `parent_id`
carries no foreign key, so `TagDao.deleteSubtree` walks the subtree explicitly.

### Deletion is `is_missing`, never a row delete
When the indexer stops seeing a file it sets `is_missing = 1` and keeps the row and its
tags. Files come back — SD card remounted, folder restored from a backup, phone swapped.
Discarding the tags on a transient absence would be exactly the data loss this app exists
to prevent. Missing rows are hidden from the grid by default and can be swept manually
from Settings.

### `content_hash` is indexed, **not** `UNIQUE` — a deliberate deviation from the brief
The brief's schema sketch says `content_hash UNIQUE`. Implemented literally, the second
copy of a genuinely duplicated file could not be inserted at all: the row would be
rejected, the file would silently vanish from the grid, and P10's duplicate finder — which
is specified as "find items sharing a `content_hash`" — could never find anything, because
the constraint guarantees no two rows ever share one.

What the brief was reaching for is "one row per file", and that is already guaranteed by
matching on `mediastore_id` during indexing. So `content_hash` gets a plain index. Restore
resolves a hash to a *set* of rows and tags all of them, which is the behaviour you want
anyway: identical content deserves identical tags.

### Migration tests build the old schema from Room's own exported JSON
`MigrationTestHelper` wants an `Instrumentation` and reads schemas from the test APK's
assets, which is awkward without a device. `SchemaBundle` instead reads the committed
`app/schemas/**/N.json` — the exact schema that shipped — replays its `createSql`
statements, and writes `room_master_table` with the recorded identity hash. Opening that
database through Room then validates the identity hash for free, so editing an entity
without bumping the version makes `MigrationTest` fail rather than silently shipping.
