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

### 8. Network-blocked dependencies

None so far — `dl.google.com`, `repo1.maven.org` and `plugins.gradle.org` are all
reachable from the sandbox, and the Android SDK downloads fine. If this changes, the
domains needed are:

- `dl.google.com` — Android SDK packages and the Google Maven repository
- `repo1.maven.org` — Maven Central
- `plugins.gradle.org` — Gradle plugin portal
- `services.gradle.org` — Gradle distribution for the wrapper
