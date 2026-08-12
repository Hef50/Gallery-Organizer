# Open questions

Things where product behaviour was genuinely ambiguous. Answers welcome; the current
behaviour is listed so nothing is blocked in the meantime.

---

### 1. What should happen to tags when a file really is gone forever?

Currently the indexer never deletes a `media` row — it flags `is_missing = 1` and keeps
the tags, on the theory that a missing file is usually a remount away from coming back
(see `DECISIONS.md`). That means the DB grows monotonically. Options:

- keep forever (current)
- offer a manual "forget missing items older than N days" sweep in Settings *(a manual
  sweep is implemented in P10; nothing is automatic)*
- auto-forget after N days missing

**Current behaviour:** keep forever, manual sweep available.

---

### 2. Should a partial media grant (Android 14+ "Select photos") be nudged toward full access?

The app fully supports the partial grant, but on a partial grant it can only ever see the
items the user picked, which makes "organize my whole library" impossible. How insistent
should the prompt to upgrade to full access be? Right now it is a dismissible banner on
the grid plus a row in Settings — no modal, no repeat nagging.

**Current behaviour:** dismissible banner + Settings row.

---

### 3. FTS5 vs FTS4

`media_fts` uses FTS4 because it is available everywhere without bundling a SQLite
build. FTS5 would give `bm25()` ranking and better prefix handling. Bundling
`androidx.sqlite` with a modern SQLite adds ~1.5 MB per ABI to the APK. Worth it?

**Current behaviour:** FTS4, results ordered by `date_taken DESC` rather than relevance.

---

### 4. Tag hierarchy semantics: does tagging a child imply the parent?

If an item is tagged `Travel/Japan`, should a search for `Travel` return it? Two schools:
implicit inheritance (search-time subtree expansion) or explicit (only what was written).

**Current behaviour:** *search-time* subtree expansion — searching a parent tag matches
items tagged with any descendant — but the *write* path stores only the exact tag the
user picked. This keeps the DB honest about what the user actually said while making
search behave the way people expect. There is a per-filter "exact tag only" toggle.

---

### 5. XMP write-back default

P8 writes `dc:subject` into JPEG/PNG/HEIC and a sidecar `.xmp` for everything else.
Modifying an original file is not something to do by surprise, even carefully.

**Current behaviour:** write-back is **off by default**, opt-in in Settings, and even
when on it is a manual "export tags to files" action rather than something that fires on
every tag edit. Sidecar writing is a separate, softer toggle since it never touches the
original.

Two limitations worth knowing about:

- **HEIC is sidecar-only.** Embedding XMP in HEIF means inserting a `mime` item into the
  ISO-BMFF `meta` box and rewriting the item location table — a whole-container rewrite
  where a mistake produces an unopenable photo. Given that Samsung shoots HEIF by default
  on some settings, this may matter more than it sounds. Worth the risk, or is a sidecar
  fine?
- **Sidecars need a folder grant.** `.xmp` is not a media type, so MediaStore will not
  place one in `DCIM/`, and scoped storage will not let the app create an arbitrary file
  there. The app asks for a folder via the system picker once and remembers it. Picking
  `DCIM` keeps sidecars next to their photos; picking anything else does not.

---

### 6. What counts as "recently added"?

MediaStore has `DATE_ADDED` (when the file landed on the device) and `DATE_TAKEN` (when
the shot was taken). A photo restored from a backup has a new `DATE_ADDED` and an old
`DATE_TAKEN`. "Recently added" in P10 uses the indexer's own first-seen timestamp, which
is closer to "new to you" than either, but this is a judgement call.

**Current behaviour:** indexer first-seen timestamp (`media.date_first_indexed`).

---

### 7. Auto-tag confidence threshold

ML Kit image labelling returns confidences. Below what confidence should a label not
even be offered as a suggestion? Too low and the review queue is noise; too high and it
misses things.

**Current behaviour:** 0.70, adjustable in Settings, suggestions are never applied
without review.

---

### 8. The debug APK is about 95 MB

Roughly 70 MB of that is un-shrunk dex — `material-icons-extended` is the biggest single
contributor — and about 22 MB is ML Kit's bundled OCR and labelling native libraries.
Restricting the build to `arm64-v8a` already cut it from ~155 MB, since the S25 Ultra will
never run the other three ABIs.

Getting it below ~30 MB means one of:

- **Turn on R8 for the debug build.** Debug builds are the shipping vehicle here, so this is
  not as odd as it sounds — but R8 with Room, ML Kit and kotlinx.serialization needs to be
  verified on a real device, and there is no device in this sandbox to verify it on.
- **Drop `material-icons-extended`** and hand-draw the fourteen icons it provides that the
  core set does not.

**Current behaviour:** arm64-only, no shrinking, ~95 MB. It installs and runs fine; it is
just a chunky download over mobile data.

---

### 9. Network-blocked dependencies

None so far — `dl.google.com`, `repo1.maven.org` and `plugins.gradle.org` are all
reachable from the sandbox, and the Android SDK downloads fine. If this changes, the
domains needed are:

- `dl.google.com` — Android SDK packages and the Google Maven repository
- `repo1.maven.org` — Maven Central
- `plugins.gradle.org` — Gradle plugin portal
- `services.gradle.org` — Gradle distribution for the wrapper

---

### 10. Should the map be able to show a real basemap?

`PlaceMap` draws points, clusters, a graticule and a scale bar, but no coastlines or roads,
because tiles need the network and v1 has no `INTERNET` permission. Three ways out, none of
them obviously right:

- **Bundle a coarse world vector outline.** A few hundred kilobytes for country borders would
  orient someone looking at a continent-scale view. It would be useless at city scale, which
  is where most of the clusters are.
- **Let the user drop in an offline tile pack** they downloaded themselves (an `.mbtiles`
  file via the document picker). Stays offline and gives a real map, but it is a chunky
  feature for something most people will never do.
- **Leave it as is.** The clusters already answer "which trip is this", and naming one turns
  it into a searchable tag, which is what the screen is for.

**Current behaviour:** the third. No basemap, and the screen says what it is showing.

---

### 11. Should naming a place re-run as new photos arrive?

Naming a cluster tags the photos in it *at that moment*. A photo taken at the same place next
year lands on the map in the same cluster but carries no tag, and nothing tells the user.

Options: re-offer the name when a cluster containing a named photo gains untagged ones; store
the coordinate on the tag and auto-apply within a radius (which is a standing rule, closer to
a saved search than to a tag); or leave it manual.

**Current behaviour:** manual. Re-naming the same cluster with the same name is idempotent —
`ensureTag` merges and `applyTags` ignores conflicts — so the fix is one gesture, but the user
has to notice.

---

### 12. Should an album be able to hold the same photo twice?

The primary key is `(album_id, media_id)`, so it cannot. That is right for "the twelve shots
worth showing my mother" and wrong for anything sequence-like where a photo might reasonably
recur.

**Current behaviour:** once per album, at the position where it was first added; adding it
again is a no-op rather than a silent reorder.

---

### 13. Should the grid use placeholders, and lose its date headers?

The grid pages with `enablePlaceholders = false` and no `maxSize`, so the loaded window
grows as you scroll and is only released when something writes to the library. Flinging
through an entire 150k library in one sitting would hold tens of megabytes.

Turning placeholders on would bound that and make the grid instantly scrollable to any
depth, because it would know its own full length up front. The cost is real: date headers
are spliced in with `PagingData.insertSeparators`, which compares each item with its
neighbour, and a placeholder has no date — so headers would appear and disappear at every
unloaded boundary as pages arrived.

The alternatives are a date scrubber down the edge that jumps by month (what Samsung
Gallery and Google Photos both do, and which solves "get me to 2019" far better than any
amount of flinging), or computing sections from a separate cheap `GROUP BY` over dates
instead of from the paged stream.

**Current behaviour:** unbounded window, real date headers, no scrubber. This wants a device
with a genuinely large library to decide on.
