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
it into `build/` and wires it as the `debug` signing config. Without this, CI would
generate a fresh random keystore per runner and every downloaded APK would refuse to
install over the previous one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). This key is
worthless — it signs debug builds only and is deliberately public.

The decoding is a *task* wired to `preBuild`, not configure-time code. Configure-time
decoding writes the file before `clean` runs, so `./gradlew clean assembleDebug` in a
single invocation deletes it again and fails at `validateSigningDebug` — which is
exactly what a fresh CI checkout looks like if the two are ever combined.

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

---

## P8 — XMP write-back

### JPEG and PNG are embedded; HEIC, RAW and video get sidecars
JPEG is a marker-segment stream and PNG is a CRC-checked chunk list — both can be rewritten
by copying every other segment through byte for byte and swapping one. HEIF cannot: XMP
lives as a `mime` item inside the `meta` box, and adding one means rewriting the item
location table, i.e. a whole-container rewrite where a mistake produces an unopenable
photo. The app's promise is that it never corrupts a file, and a sidecar keeps that promise
absolutely. Logged in `OPEN_QUESTIONS.md` in case the user would rather have HEIC embedding
and accept the risk.

### The XMP packet is hand-built
The only libraries that write XMP properly on Android are Adobe's XMP Toolkit (not on Maven
Central, heavyweight) and metadata-extractor (read-only). A `dc:subject` bag is thirty lines
of XML. Hand-building it means the app writes exactly what it intends and nothing else — no
surprise properties, no dependency, no APK weight.

### The write is: copy, stage, verify, write, confirm
1. The original is copied byte for byte into the app's cache.
2. The rewritten version is built into a *second* temp file.
3. The staged file is re-parsed and its `dc:subject` checked against what was intended, and
   its length sanity-checked against the original. A failure here means the original is
   never opened for writing at all.
4. Only then is the original opened and the verified bytes streamed in.
5. The destination is re-read before the backup copy is dropped; if it does not confirm,
   the backup is written back.

Every failure path leaves the original untouched and is reported by name.

### Keywords the app did not write are preserved
The database is authoritative for *this app's* tags, but another tool's keywords are not
this app's to delete. Removing a tag in the app removes it from the file; a keyword the app
never wrote survives the rewrite.

### Consent is per batch, through the system dialog
`MediaStore.createWriteRequest` puts Android's own dialog in front of the user listing the
affected files. That is not something the app can or should route around, and it is why
write-back is a manual action rather than something that fires on every tag edit. If the
user declines, the export falls back to sidecars where possible — they said no to changing
their originals, not no to exporting tags.

---

## P9 — On-device suggestions

### Labels are suggestions; OCR text is not
"Beach" is a guess and belongs in a review queue. The text visible in a photograph is a
*fact about the file* — it cannot be wrong in the way a label can — and it only ever makes
search better, so it goes straight into `media.ocr_text` and the FTS index without review.

### The review queue is grouped by label, not by photo
"Beach — 340 photos, tag them?" is one decision. Item-by-item review of a 150k library is
not a feature, it is a punishment. Accepting part of a label is still possible for when the
grouping is too coarse.

### Rejections are kept, not deleted
A rejected suggestion row stays as `status = rejected`. Deleting it is exactly what would
let the next scan offer the same wrong guess again. `label_suggestion` inserts are IGNORE
for the same reason. There is a "forget rejections" escape hatch for after a threshold
change.

### `media.auto_scan_state` rather than "has suggestions"
"We looked and found nothing" and "we have not looked yet" are different states. Inferring
the first from an empty suggestion list would make the worker re-analyse every featureless
photo on every run, forever. A file that cannot be decoded is marked *failed* for the same
reason.

### ML Kit models are bundled, not downloaded
The app has no `INTERNET` permission and never will. Bundled models cost a few MB of APK
and buy analysis that works on a plane, on day one. The clients hold native resources, so
`AppContainer` creates the analyser on demand and the worker closes it when it finishes
rather than holding it for the process lifetime.

### Images are decoded at ~1280 px for analysis
A 200 MP original decoded at full size is a bitmap of roughly 800 MB. Labels are stable
around a thousand pixels, and OCR needs enough resolution for glyphs but nothing like the
full frame, so a two-pass bounds-then-sample decode is used.

### Analysis runs only while charging *and* idle
It is the most expensive thing the app does and it is never urgent. Each item commits on
its own, so being stopped costs at most one photo's work, and the worker re-enqueues itself
rather than monopolising a single execution window.

### ML Kit sits behind an `ImageAnalyzer` interface
Which is what lets every rule above — thresholds, deduplication, what happens to a failure,
what accepting does to a manual tag — be tested on the JVM with no device and no model.

---

## P10 — Polish

### Hiding a folder is a view preference, not a filter
Hidden folders are folded into the query *downstream* of the state the UI reads, so they
never end up baked into a saved search and never make a quick-filter chip look unselected.
And an explicit folder filter beats the standing preference: "show me only Screenshots"
works while Screenshots is hidden, because otherwise the folder would be unreachable and
the user could not tell why.

### "Recently added" uses the app's own first-seen timestamp
MediaStore's `DATE_ADDED` is when the file landed on the device and `DATE_TAKEN` is when the
shot was taken. A photo restored from a backup has a brand-new `DATE_ADDED` and a years-old
`DATE_TAKEN`, and neither is "new to you". `media.date_first_indexed` is.

### Duplicates are trashed, not deleted, and the oldest copy is always kept
`MediaStore.createTrashRequest` keeps the files recoverable for 30 days and puts Android's
own confirmation in front of the user. This app is not going to be the reason a photo is
gone forever. The oldest copy by `date_added` is never offered for removal — it is the one
whose path anything else on the device is most likely to reference — and the removed rows
are flagged `is_missing` rather than deleted, so their tags are still there if the user
restores them from the trash.

### The duplicate finder tells you when it does not know
Hashing is lazy, so on a fresh install most of the library has no `content_hash` and the
duplicate finder genuinely cannot see duplicates yet. Rather than showing a confident
"no duplicates", the screen says how many items are still unchecked and offers to hash them
now instead of waiting for the idle worker.

### `abiFilters = ["arm64-v8a"]`
ML Kit's bundled models ship native libraries for four ABIs, which made the universal debug
APK about 155 MB — most of it for architectures a Galaxy S25 Ultra will never run.
Restricting to arm64 cuts it to about 95 MB. The remaining size is discussed in
`OPEN_QUESTIONS.md`.

---

## Performance — guarded by query plans, not stopwatches

`QueryPlanTest` runs `EXPLAIN QUERY PLAN` over the queries that decide whether the app is
usable at 150,000 rows, and asserts SQLite picks an index and does not sort.

A wall-clock assertion on Robolectric's SQLite would say nothing about a phone — different
engine speed, a tiny data set, and a number that is either flaky or meaningless. What
actually separates 5 ms from 5 seconds is index versus scan, and `EXPLAIN QUERY PLAN`
answers that deterministically.

It earned its place immediately. The grid query — the single most-run query in the app —
was choosing `index_media_is_missing`, an index over a column with two distinct values that
therefore excluded nothing, and then sorting the whole result set in a temp B-tree. At 150k
rows that is the entire library sorted in memory on every grid load, and every other test in
the suite passed while it happened.

Schema v4 replaces it with `(is_missing, date_taken, id)`, so the planner seeks and then
walks the range already in date order and `LIMIT` stops early, plus
`(bucket_id, date_taken)` for the folder-filtered grid. The lesson generalises: a
single-column index on a boolean is rarely useful as a filter and is very good at
misleading the planner away from the index that carries the ordering.

---

## Organisation — albums, places and typed tags (schema v5)

### An album is not a tag, and a saved search is neither
Three things in this app group photos, and folding any two together would lose something.

A **tag** is a fact about a photo — "this is Kyoto" — and belongs to every photo that fits
it. An **album** is a curated sequence someone assembled on purpose: it has an order, a
cover, and membership that is nobody's business but the user's. A **saved search** is a
standing question that answers itself as the library grows.

The tempting simplification is to make albums a kind of tag. It fails on ordering: the
twelve shots from a trip are in the order you want them seen, and `media_tag` has nowhere to
put that. The opposite simplification — making tags a kind of album — fails on the fact that
tags nest and describe, and nobody wants to hand-maintain membership of "Anna".

So `album` and `album_media` are their own tables, with an explicit `position`.

### Album positions are sparse
`album_media.position` moves in steps of 1,000, so dragging a photo between two others
rewrites exactly one row — the midpoint of the gap. When a gap is used up (about ten moves
into the same slot) the album is renumbered in one transaction and the move retried.
Renumbering every drag would be the thing that makes reordering feel slow; renumbering
occasionally is invisible.

### `cover_media_id` is deliberately not a foreign key
An album should keep pointing at its chosen cover even while that photo is temporarily
missing, and a cascade would silently clear it. The shelf resolves the cover in SQL and
falls back to the album's first present member, so a missing cover shows a picture rather
than a grey box.

### Tags have a kind, and it is a string on the wire
`TagKind` — Person, Place, Event, Thing, Other — exists because a flat list stops being
navigable at about two hundred tags: "Anna", "Antwerp" and "Anniversary" are three
completely different things sitting next to each other alphabetically. A kind gives each tag
an icon, a colour and a section, so the picker can be organised the way people think.

It is written into the backup as an explicit string (`"person"`) rather than an enum
ordinal, so that reordering the enum can never silently turn everyone's People into Places.
Restoring a v1-era file — where every tag record implicitly says `"note"` — never re-kinds a
tag that already exists on the device, because that would undo work the user did here.

A new child tag inherits its parent's kind: someone adding "Kyoto" under "Japan" means
another place, and asking them to say so again is friction for no information.

### Location is read in a separate, delayed pass
MediaStore **redacts GPS from the copy it hands an app**. Reading EXIF from the ordinary
content URI returns nothing at all, no matter what the file contains: the original is only
available via `MediaStore.setRequireOriginal` and only with `ACCESS_MEDIA_LOCATION`.

That makes location a second full read of every file — the indexer gets everything else from
MediaStore's index without opening anything — which is exactly the kind of eager whole-library
work the brief rules out. So it is its own worker, it starts three minutes behind launch so
it never fights the first index pass for I/O, and it records "no GPS tag" as *read* rather
than leaving it pending, or it would re-open the same tens of thousands of screenshots
forever.

`ACCESS_MEDIA_LOCATION` is requested in the same dialog as the read permissions. The system
will not grant it without a media read grant anyway, and a separate prompt about location
days later is the kind of thing that makes people say no. Refusing it costs the Places
screen and nothing else — it is deliberately absent from `MediaPermissionState.access`.

### The map has no basemap, on purpose
Map tiles come from a tile server, a tile server is the network, and there is no `INTERNET`
permission for the whole of v1. Bundling an offline basemap would mean either a vector
planet (hundreds of megabytes) or a coastline outline coarse enough to be decorative.

So `PlaceMap` draws what it actually knows: every located photo as a faint point, clusters as
weighted discs, a graticule labelled with real coordinates, and a scale bar. It reads as a
plot of the user's own travel rather than a world map, which is the honest thing for it to
be — and for what the screen is *for*, a basemap would not add anything the clusters do not
already say.

### Naming a place is the feature; the map is how you find it
Without a network there is no gazetteer, so 35.0116, 135.7681 cannot be turned into "Kyoto"
by the app. Instead the map finds the photos from one place and the person who was there
names it once. That creates a `Place`-kind tag and applies it to everything in the cluster in
one transaction — so from then on the place is searchable, nestable under `Japan`, and
survives backup and restore like any other tag. A coordinate stored on its own would do none
of that.

### Clustering is a fixed grid, not k-means
O(n) with no distance matrix, which matters when n is every located photo in a 150k library.
More importantly it is **stable**: the same photo lands in the same cell regardless of what
else is in the library, so panning never reshuffles the groups under a half-finished gesture.
The visible cost is a seam at cell edges; cells are chosen an order of magnitude smaller than
the viewport, so a seam is a couple of pixels. Longitude cell width is divided by
cos(latitude) so cells stay roughly square in kilometres instead of collapsing near the poles,
and the pin sits on the centroid rather than the cell centre so it lands on the photos.

### The backup carries albums, and albums come before items
Album membership is hand-made data the device cannot regenerate, exactly like a tag — so the
export set became "everything carrying a tag **or** sitting in an album", and an album of
untagged holiday snaps now survives a reinstall too.

Album records are written *before* the items so each item can carry its own membership, which
keeps the whole file streaming in constant memory. An album's cover is therefore a forward
reference to an item ref; the importer holds at most one pending cover per album while it
reads, and an album whose cover photo is not on this device simply restores without one.

### The viewer swipes through whatever you opened it from
Opening a photo from an album and then swiping into the rest of the library would be a small
betrayal of the album — the point of one is that it is a bounded, ordered set. `ViewerSource`
makes the viewer page over the same source as the screen behind it: the library query, one
album in the user's order, or an explicit bounded id list for a map cluster.

The shared-element host was hoisted out of the grid to wrap the whole shell, so an album or a
map cluster gets the same thumbnail-becomes-photo transition without every screen hosting its
own copy of the viewer.

### A floating nav pill instead of a `NavigationBar`
Material's navigation bar paints an opaque 80 dp band across the bottom of the screen, which
on a gallery means permanently hiding a row of photographs. The pill floats clear of the
edge, is only as wide as its contents, lets the grid scroll visibly underneath, and expands
only the selected item to its label so it stays inside the thumb arc.

---

## Scrolling deep into the library

Reported as "scrolling to old images is slow and lags the app". It turned out to be three
separate things, and only one of them was where you would look first.

### Coil's disk cache never held a single thumbnail
The app configured a 256 MB disk cache for thumbnails. In Coil 2, `HttpUriFetcher` is the
**only** thing that ever writes to the disk cache — a custom `Fetcher` returning a
`content://` result does not populate it. So the cache was dead configuration, and every
thumbnail was regenerated from scratch whenever it fell out of the in-memory cache.

That is invisible at the top of the library and brutal further down. `loadThumbnail` is
nearly free when MediaStore has a cached thumbnail, which it does for recent photos. For a
photo from four years ago it usually does not, and MediaProvider silently falls back to
decoding the original — a 200 MP, ~50 MB JPEG on this phone. Scroll down through a few
thousand old photos and back up and every one was decoded from the original *again*.

`MediaStoreThumbnailFetcher` now writes through to the disk cache itself. MediaProvider is
asked at most once per photo per size, ever.

### Thumbnail requests are snapped to buckets, and capped
Two reasons. Cache keys stay stable when a cell measures 350 px in one layout pass and 352
in the next — otherwise that is two cached thumbnails of the same photo. And the cap is the
important half: above about a thousand pixels MediaStore has nothing cached and *must*
decode the original, so the one-column grid — asking for ~1440 px on this screen — was
decoding a 200 MP original for every visible tile. It is now served a 1024 px thumbnail,
which is very slightly softer and perhaps fifty times cheaper.

### Sixty-four concurrent decodes starve the main thread
Coil's fetcher and decoder dispatchers both default to `Dispatchers.IO`, which is up to 64
threads. A hard fling asks for that many thumbnails at once, and behind an uncached
thumbnail is a full JPEG decode. Sixty-four of those on eight cores saturates the CPU and
starves the main thread — felt as *the grid* stuttering, even though none of the work is on
the main thread. Both dispatchers are now capped at 4. Nothing is lost by queueing: requests
the fling has already flown past are cancelled before they are ever decoded.

### `maxSize` was making deep scrolling worse, not cheaper
The grid's `PagingConfig` had `maxSize = pageSize * 10` with `enablePlaceholders = false`.
That combination does not bound memory so much as break the list: pages dropped from the
front cancel out pages appended at the back, so the window never grows and every position
in it slides as you scroll. `GridPagingWindowTest` pins this down — under a cap, position
400 is not addressable at all.

Two consequences. A long fling became a continuous load-and-drop cycle, re-querying photos
it had just discarded. And the index the grid hands the viewer stopped being an offset into
the query, so past a certain depth **tapping a photo opened a different photo**. That was a
correctness bug found while chasing the performance one.

The cap is gone. The cost is memory — a row is a few hundred bytes, so flinging through a
whole 150k library in one uninterrupted sitting would hold tens of megabytes, and any write
to the library invalidates the source and releases it. Bounding it properly means turning
placeholders on, which trades away the `insertSeparators` date headers; that is written up
in `OPEN_QUESTIONS.md` rather than decided here.

### The location worker was interrupting the grid every 250 rows
Every commit to `media` invalidates the grid's paging source, and Room's invalidation is
per table — there is no way to say "this write does not change anything the grid shows". The
EXIF backfill added in P12 committed every 250 rows, so a 150k library would interrupt the
grid six hundred times while it ran. The only lever is committing less often, so it now uses
the app's standard ~500-row transaction.

---

## The date slider

A 150,000-item library is well over a thousand screens of grid, so "take me to spring 2019"
was not a scrolling problem to be optimised — no fling is short enough. It needed a way to
jump.

### It works in dates, not indices
The obvious design tracks a position: the thumb shows index / total, and dragging it scrolls
to an index. That is wrong here for the same reason that broke the viewer: the grid pages
without placeholders, so an item's index in the list is not its position in the query, and
after a jump it is not even close.

A date has no such problem. Every photo carries one, so the thumb's position is derived from
the date of the topmost visible photo, and a drop is resolved from a date back to an offset.
Nothing has to reconcile the two coordinate systems, because there is only one.

### Jumping re-anchors the paging window rather than scrolling to a position
With placeholders off, there is no position 40,000 to scroll to until 40,000 items have been
paged through, which is exactly the crawl the slider exists to replace. So a jump rebuilds
the pager with `initialKey` at the target offset: one page loads at the destination, and
Paging still prepends normally as the user scrolls back up towards newer photos.

### The rail is allocated by photo, not by month
One equal slot per month would spend most of the rail on months with almost nothing in them,
and compress the summer you actually took nine thousand photographs into a sliver you cannot
aim at. Rail space is proportional to item count, so the slider is a picture of where the
photos are. The thumb also interpolates *within* a month rather than snapping to its start —
otherwise, in a library dominated by one month, it would sit still for most of the drag.

### Bucketing months in Kotlin, not in SQL — measured at seven to one
The natural implementation is `GROUP BY strftime('%Y-%m', date_taken, 'unixepoch',
'localtime')`. Measured on 150,000 rows it took **550 ms**, because `strftime` runs per row
and consults the time zone database each time, and the grouped expression cannot use an
index so SQLite sorts the whole result in a temp B-tree.

Selecting the bare `date_taken` values instead is a covering-index scan of the index the grid
already depends on, and folding them into months in Kotlin costs one boundary calculation per
month — about a hundred and fifty for a decade — instead of one per row. Same answer, **72 ms**.
That is the difference between the slider appearing instantly and taking a couple of seconds
on a phone every time a filter changes.

The boundaries still come from `java.time`, so daylight saving is exact rather than assumed;
`ScrubberTest` covers a month containing a clock change and the first and last instants of a
month.

### It is not offered where it would be lying
Only for date-ordered results. "Largest first" has no chronology, and "recently added" orders
by when this app first saw a file — so a photo from 2014 restored last week sits at the top,
and a rail labelled with years would be describing something the grid is not doing. A library
spanning a single month gets no slider either: a control that cannot go anywhere invites a
gesture that does nothing.

---

## A launch-crash test, added the hard way

A build shipped that crashed on every launch. The cause was one unbounded index: the date
slider read the date of the topmost visible photo by scanning forward from the first visible
index, and Paging's `peek` *throws* for an index it does not hold rather than returning null.
The paged list is empty on the first frame, so `peek(0)` threw before a single photo could
be drawn.

Two hundred and ninety-one tests were green. None of them opened the app.

`AppLaunchTest` now drives the real `MainActivity` through the real `Application` under
Robolectric, in three states: nothing granted, granted with an empty library, and granted
with a library already indexed. It exercises the whole first frame — the container, Room, the
permission gate, the paged grid, the slider, and every `LaunchedEffect` that runs on
composition.

It was verified the only way a regression test is worth anything: by putting the bug back and
watching it fail with the exact exception the phone produced —
`IndexOutOfBoundsException: Illegal attempt to access index 0 in ItemSnapshotList of size 0`.

The general lesson is worth writing down, because this codebase invites the mistake. Testing
every piece in isolation says nothing about whether the pieces compose, and this app's UI is
assembled from paged lists whose contents arrive *after* the first frame. The empty first
frame is a real state that every screen passes through on every launch, and it is exactly the
state that unit tests of the parts never visit.

---

## Three bugs from the first real device session

### The viewer was showing a thumbnail, not the photograph
Opening a photo full screen and pinching into it revealed nothing, because the viewer was
never getting the photograph. It asks for `Size.ORIGINAL`, which has *no pixel dimensions* —
and the fetcher read those dimensions with `pxOrElse { 0 }`, got zero, and treated zero as a
request for the smallest bucket. So a 200 MP photograph was served as a 384-pixel thumbnail
stretched across a 1440-pixel screen, and cached at that size so it never improved.

The lesson is in the type. `boundedSizeOf` returns `Int?`, where null means "unbounded, give
them the original", precisely so that "no size specified" cannot silently collapse into "the
smallest size available". A default of zero looked harmless and was not.

Originals are deliberately *not* written to the disk cache: a handful of 50 MB photographs
would evict every thumbnail in it.

### The zoomed-out grid was paying for chrome nobody could see
At ten columns there are a couple of hundred tiles on screen. Each one was allocating four
running animations and — much worse — a `graphicsLayer` clipping to a freshly built
`RoundedCornerShape`, which is a separate clipped render node per tile. Affordable for the
forty tiles of a four-column grid; not remotely affordable for a ten-column one.

`PlainMediaCell` is the path taken when there is no selection anywhere and nothing is dimmed,
which is almost always. It clips once on the container, runs no animations, and is otherwise
an image in a rounded box. Switching between it and the full cell costs one frame when a
selection begins, which is a deliberate gesture rather than a fling.

Unloaded tiles are now near-black rather than mid grey, so a half-filled zoomed-out grid
reads as absence rather than as a wall of placeholders — the same thing Samsung Gallery does.

### The smallest thumbnail bucket was making zoom worse
Four, six and ten columns used to resolve to different buckets, so pinching out re-fetched
every visible tile at a new size — the moment the grid felt worst. Dropping the bucket below
384 makes every dense level share one cached file per photo. Nothing is lost: Coil still
downsamples to the cell when it decodes, so a ten-column grid holds ten-column bitmaps in
memory; only the file on disk is shared.

### The filter sheet could not be applied
The sheet's content column had no scroll, and it is far taller than a phone: media type,
sort, dates, up to twenty-four folder chips, two switches and a tag list — around 990 dp of
content on a screen with roughly 780 dp to give it. "Show results" sat below the bottom of
the display with no way to reach it, so a filter could be chosen and never committed. From
the outside that is indistinguishable from filters that do nothing.

The content now scrolls and the action row is pinned outside it, so it is reachable however
tall the filters get. The button also reads "Done" rather than "Show results" when nothing
has been changed, so it stops promising an action it is not going to take.
