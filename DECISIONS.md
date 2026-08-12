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

---

## P3 — Indexing

### The watermark is `(date_modified, _id)`, not `date_modified`
A timestamp alone is not a safe resume point. A burst of shots — or any bulk copy — lands
dozens of files in the same second, so resuming at `date_modified > watermark` would skip
the rest of that second, and `>=` would re-read it forever. Carrying `_id` makes the
cursor a total order over the collection and the resume exact.

### Rows and the watermark commit in the same transaction
The chunk's inserts, its FTS rows and the advanced watermark are one transaction. Split
into two, a crash in between either skips a chunk permanently (watermark first) or
re-reads it every launch (rows first).

### The scan holds one cursor open instead of paging with LIMIT/OFFSET
A fresh `LIMIT`/`OFFSET` query per batch makes MediaStore re-sort the whole collection
each time, turning a 150k-item pass into an O(n²) crawl. One cursor, read forward, is
what `CursorWindow` is for.

### Missing files are found by a set difference in memory, not `NOT IN (...)`
SQLite binds at most 999 variables by default, so a 150k-id `NOT IN` clause simply cannot
be expressed, and chunking it would cost a full table scan per chunk. The sweep pulls an
id-only projection from both sides and diffs them; 150k longs is about 1.2 MB for one
pass. The sweep also refuses to act when MediaStore returns *nothing* while the database
is non-empty — a revoked permission or an unmounted volume looks exactly like an empty
library, and flagging the whole library on that would be alarming.

### The indexer only refreshes MediaStore's own columns
`updateFromMediaStore` names each column explicitly instead of `@Update`-ing the whole
entity, because `content_hash`, `date_first_indexed` and `ocr_text` belong to the app. A
whole-row update is one forgotten field away from a rescan silently wiping every item's
identity hash and OCR text.

### A posted notification, not `setForeground`
A foreground worker on Android 14+ needs `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC` in the manifest. The pass is fully resumable — being
stopped costs at most one 500-row chunk — so it runs as an ordinary worker with a plain
low-importance progress notification, and the manifest stays minimal. `POST_NOTIFICATIONS`
is treated as optional: without it, indexing runs exactly the same, just quietly.

### The progress bar is indeterminate
MediaStore will not tell you how many rows match without running the query, so any
denominator would have to be a guess that jumps around as it is refined. An item count
that ticks up is honest; a percentage that goes backwards is not.

---

## P4 — The grid

### Date headers come from `PagingData.insertSeparators`
The obvious way to build a date-sectioned grid is to walk the list, compare each item's
day with the previous one, and splice in headers. That needs the whole list. Paging 3's
`insertSeparators` does the same job *inside* the paged stream, including correctly at
page boundaries, so sections exist without ever materialising 150k rows. The logic is a
top-level `withDateHeaders` rather than a lump inside the view model, so the page-boundary
cases are directly testable.

### Selection is a set of database ids, never grid indices
Indices shift when a page loads, when the indexer inserts a row, or when a filter changes.
An index-based selection silently starts pointing at different photos. Ids do not move.

### The drag is recomputed from its anchor on every move
`extendDrag` replaces the drag's contribution rather than accumulating it, so dragging
back over items deselects them and an overshoot is correctable without lifting a finger.
The selection that existed *before* the drag is kept separately and always survives.

### The drag gesture lives on the grid, not on the cells
A `pointerInput` on each cell stops receiving events the moment the finger leaves that
cell — which is the entire gesture. The grid resolves which cell is under the pointer from
`LazyGridState.layoutInfo`, falls back to the nearest cell when the finger is in the gap
between two (diagonal drags spend a lot of time there), and disables normal scrolling for
the duration so the gesture cannot fight the list.

### Auto-scroll is a loop, not a reaction to drag events
Once the finger stops moving inside the edge hot zone there are no more pointer events, so
event-driven auto-scroll stalls exactly when the user is holding still at the edge waiting
for the list to come to them. A separate coroutine scrolls at a speed that ramps with how
far into the zone the finger is.

### Thumbnails come from `ContentResolver.loadThumbnail`, not from decoding the original
A 200 MP S25 Ultra JPEG is tens of megabytes; even a subsampled decode costs a large
transient allocation per cell. A custom Coil `Fetcher` asks MediaStore for the thumbnail
it has already generated and cached, and only falls back to streaming (and subsampling)
the original when there is not one. That is the difference between a grid that flings and
one that hitches.

### `peek` for selection, `get` for rendering
`LazyPagingItems.get` tells Paging to load around that index. A drag passing over a
hundred indices would trigger a hundred loads the user never asked for, so every
selection-time lookup uses `peek`.

---

## P5 — Tagging

### One gesture is one transaction
Tagging 500 selected photos writes 500 cross-ref rows, bumps the tag's usage counters and
rebuilds 500 FTS rows inside a single `withTransaction`. Either all of it happened or none
of it did; the grid can never show a half-applied tag, and an interrupted write cannot
leave the text index describing tags that are not there.

### Two taps, no Apply button
With a selection already made, tapping the tag button and then tapping a tag *is* the
whole gesture — the tag applies immediately and the snackbar offers Undo. An Apply button
would add a third tap to the most repeated action in the app, and undo is a better safety
net than confirmation because it costs nothing when you were right.

### Tapping a partially-applied tag applies it to everything
Tri-state checkboxes have two defensible behaviours when tapped from the indeterminate
state. "Some of these are tagged Travel and I am tapping Travel" nearly always means "make
them all Travel", so partial resolves upward. Clearing is still one more tap away.

### Hashing happens after the transaction, not inside it
A tag write is the moment identity starts to matter, so it is the right time to hash. But
opening a few hundred files is I/O, and the user's tap must not wait on it. The tags commit
first; hashing runs after and is idempotent, and anything it misses the idle backfill
worker picks up.

### `IN (...)` clauses are chunked at 500
SQLite caps bound variables, so bulk-tagging a few thousand items has to be split. The
chunks run inside the caller's transaction, so splitting them costs nothing in atomicity.

### Tags carry their ancestors into the text index
An item tagged `Travel/Japan/Kyoto` has "Travel Japan Kyoto" in its FTS row, so typing
"travel" finds it. This mirrors what the structured tag filter does by expanding a parent
to its subtree (OPEN_QUESTIONS.md #4), and it means a rename or a re-parent has to rebuild
the FTS rows of everything in the affected subtree — which `TagRepository` does.

### A tag cannot be moved into its own subtree
Allowing it would detach the whole branch from every root: the rows would still exist,
still hold assignments and still block name reuse, but nothing would ever render them.
`buildTagTree` also defends the read side — orphans and cycle-stranded tags are promoted to
roots rather than silently disappearing.

---

## P6 — Search

### The grid *is* the search results
There is no separate search screen. The gallery is driven by a `SearchQuery`, and an empty
query means "everything". That means saved searches open in the same grid with the same
selection and bulk-tag machinery, rather than a second, weaker copy of it.

### `@RawQuery` with `observedEntities`
Tag AND/OR/NOT × date range × folder × media type × full text is a genuinely dynamic filter
shape; there is no finite set of `@Query` methods that covers it. `observedEntities` is
what keeps the paged results live — tag an item and the filtered grid updates itself
without anything having to remember to refresh.

### Tag filters are `EXISTS` subqueries, not joins
Joining `media_tag` multiplies rows per matching tag and forces a `DISTINCT` over the whole
result — a temp B-tree instead of an index scan, at 150k rows. `EXISTS` short-circuits on
the first match and leaves the outer query a plain scan of `media`. AND is one `EXISTS` per
required tag (a single `EXISTS` over all the ids would be an OR wearing an AND's clothes);
OR and NOT are one `EXISTS` / `NOT EXISTS` over the union.

### User text is never passed to `MATCH` as-is
FTS4 treats `"`, `*`, `-`, `^`, `:` and `OR`/`AND`/`NEAR` as syntax, so searching for
`mum's "best"` would be a syntax error rather than a search. Input is tokenised on anything
that is not a letter or digit — which keeps CJK working, since those are letters — and each
token becomes a prefix term, so "kyo" already finds "Kyoto". Blank or punctuation-only
input produces no clause at all rather than accidentally matching everything or nothing.

### Saved searches are JSON, and decoding never throws
`saved_search.query_json` means the query language can gain a field without a migration and
without invalidating searches the user already saved. Every field has a default so an old
saved search still deserialises, unknown keys are ignored so one written by a newer build
still opens, and anything unparseable degrades to "everything" — a corrupt row must not be
able to break the screen that lists them.

### Typing is debounced; sorting is not a filter
Every keystroke would otherwise cancel a Pager and start a fresh FTS query over 150k rows.
Clearing the box skips the debounce, because waiting to see your library again feels broken.
`SearchQuery.isEmpty` deliberately ignores `sort`: changing the order does not mean the user
is looking at a filtered subset, and the UI should not claim they are.

### Date headers only appear for chronological sorts
"Largest first" broken up by date headings would be nonsense, so that sort renders a plain
grid.

### "Select all results" is capped at 10,000
Selecting 150k items produces a `Set<Long>` the selection bar can do nothing sensible with,
and a bulk tag at that scale deserves to be a deliberate act rather than an accidental one.

---

## P7 — Backup and restore

### The format is JSON Lines, not one JSON document
A single top-level object has to be built in memory to write and parsed in memory to read.
More importantly, a JSON document truncated by a full disk or a cancelled write is a total
loss, whereas a line-delimited file still restores everything up to the cut. This user has
no cloud safety net, so the format is chosen for how it fails, not just for how it works.
Both directions stream in constant memory.

### Only tagged items are exported
The other 149,000 rows are pure MediaStore facts that reindexing rebuilds in minutes.
Including them would inflate the file by two orders of magnitude and protect nothing.

### Matching is two-tier: content hash, then size + filename
The hash is the strong key, but hashing is *lazy* — on a fresh install almost nothing has
been hashed yet, so a hash-only restore would match nothing on the one occasion it matters
most, the day the phone is replaced. Size plus filename is the fallback, and schema v2 adds
`index_media_size_display_name` so that lookup is an index seek rather than a full scan per
backed-up item.

### A hash resolves to a *set* of rows, and all of them get the tags
This is the other half of dropping `UNIQUE` from `content_hash`: two copies of the same
photo are two rows, and identical content deserves identical tags.

### Restore is purely additive
Nothing is ever deleted or overwritten. Tags merge by *path*, not by id — ids are local
database details, so restoring onto a device that already has a `Travel` tag must merge
with it. Assignments insert with IGNORE, so a tag the user has since applied by hand keeps
its `manual` source instead of being restamped as `imported`. OCR text is only written
where there is none.

### Saved searches have their tag ids remapped
A saved search stores tag *ids*, which differ between installs, so they are translated
through the file's tag refs on the way in. `bucketIds` are MediaStore's and mean nothing on
another device, so they are dropped. A search whose name is taken is skipped rather than
duplicated.

### One corrupt line costs that line
A malformed record is counted and skipped rather than aborting the import — the whole point
of the format. A file with no valid header, though, is rejected outright rather than
half-applied.

### Export opens with mode `"wt"`
Without truncation, overwriting a larger existing backup leaves the tail of the old file
behind and silently produces a corrupt one.
