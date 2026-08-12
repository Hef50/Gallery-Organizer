# Running and testing Gallery Organizer

Nothing in this project has ever run on a device — there is no emulator in the sandbox it
was built in, so every guarantee below is backed by 223 JVM tests rather than by use. This
document is the plan for changing that.

Work through **Smoke test** first. If those seven steps pass, the app fundamentally works.

---

## 1. Get it on the phone

### Easiest: install from a release (no computer needed)

On the phone, open:

**<https://github.com/Hef50/Gallery-Organizer/releases/download/latest/app-debug.apk>**

Tap it, then allow Chrome (or whichever browser) to install unknown apps when Android asks.
That prompt is normal for anything not from the Play Store — the app is deliberately never
submitted there.

The URL is stable: every push to `main` rebuilds it, and because the signing key is
committed, each new build installs straight over the old one **without uninstalling and
without losing your tags**.

> If the link 404s, the release workflow has not run yet. Go to **Actions → Release APK →
> Run workflow**, pick the branch, and wait ~5 minutes.

### Alternative: from the CI artifact

**Actions → latest _Build_ run → Artifacts → `app-debug`.** This is a ZIP behind a GitHub
login, so it is really a laptop route: download, unzip, then `adb install -r app-debug.apk`.

### Alternative: build it yourself

```bash
git clone https://github.com/Hef50/Gallery-Organizer
cd Gallery-Organizer
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and an Android SDK with platform 35. Point `ANDROID_HOME` at your SDK, or put
`sdk.dir=/path/to/sdk` in `local.properties`.

The APK is ~95 MB and arm64-only — most of that is ML Kit's bundled OCR and labelling
models, which are bundled precisely so the app never needs the network.

---

## 2. Smoke test — eleven steps, about fifteen minutes

Do these in order on the real library. Each one exercises a whole phase.

| # | Do this | Expect |
|---|---|---|
| 1 | Launch on a fresh install | Rationale screen explaining what it reads and that nothing leaves the phone |
| 2 | Tap **Allow access to photos & videos** → grant | Grid appears, "Indexing…" strip at the top, notification in the shade |
| 3 | Wait for indexing, then pull down the notification shade during it | Item count climbs; leaving the app does not stop it |
| 4 | Scroll the grid hard | Smooth; date headings between days; no blank tiles that never fill |
| 5 | Long-press a photo, drag across several rows without lifting | Selection follows the finger; count in the toolbar climbs; drag *back* deselects |
| 6 | Tap the tag icon → type a name → **Create "…" and apply** | Snackbar: "Tagged N items · name", with **Undo** |
| 7 | Type that tag name into the search box | Exactly those items come back |
| 8 | Pinch the grid apart, then together | Column count steps 4 → 2 → 1 and 4 → 6 → 10, springing rather than snapping; the photo under your fingers stays put |
| 9 | Tap a photo | It grows out of its tile into full screen; swipe sideways for the next; drag down to dismiss back into the same tile |
| 10 | Select a few photos → album icon → type a name → **Create "…" and add** | Snackbar with **Undo**; the album appears on the **Albums** tab with one of those photos as its cover |
| 11 | Open **Places** | A plot of where your photos were taken, with clusters. Tap one → **Name this place** → a Place tag lands on everything in it |

If all eleven pass, indexing, paging, selection, tagging, FTS, the permission flow, the
viewer transition, albums and the EXIF location pass are all working end to end.

Step 11 needs `ACCESS_MEDIA_LOCATION` (granted in the same dialog as step 2) and needs the
location worker to have run — it starts three minutes after launch and works through the
library in the background, so on a large library it fills in over an hour or two rather than
at once. The screen says how many photos it has still to check.

---

## 3. The claims most worth checking

These are the things the JVM tests assert but only a device can really confirm.

### Indexing is genuinely incremental

1. Let the first index finish. Note the item count.
2. Force-stop the app, reopen it.
3. **Expect:** the grid is there immediately; the indexing strip either does not appear or
   vanishes in seconds. It must not re-scan the library.
4. Take a new photo in the camera app, come back.
5. **Expect:** it appears at the top within a pass, without a full rescan.

### Indexing survives being killed

1. Clear app data to force a fresh index (Settings → Apps → Gallery Organizer → Storage).
2. Reopen, let indexing run for ~20 seconds, then force-stop mid-pass.
3. Reopen.
4. **Expect:** it continues from roughly where it stopped rather than starting over. The
   count should not drop back to zero.

### Scrolling performance at your library's size

The budget is a smooth grid and a cold start under 1.5 s. On a large library, watch for:

- tiles that stay grey for more than a moment while flinging;
- a stutter every time a new page loads;
- the app taking noticeably long to show the grid after a cold launch.

To measure cold start properly:

```bash
adb shell am force-stop com.galleryorganizer
adb shell am start-activity -W -n com.galleryorganizer/.ui.MainActivity | grep TotalTime
```

### Bulk tagging really is one transaction

1. Search or filter to something with a few hundred matches.
2. Toolbar → **select all results** icon.
3. Tag them.
4. **Expect:** the count in the snackbar matches, and it lands in one go rather than the
   grid updating progressively. Then tap **Undo** and confirm it all comes back off.

### Backup and restore — the one that matters most

This is worth doing deliberately, because it is the only protection your tags have.

1. Tag a handful of items so you have something to lose.
2. **Settings → Export tags to a file.** Save to Drive, or anywhere off the phone.
3. Note what the summary says (tags, items, saved searches).
4. **Settings → Apps → Gallery Organizer → Storage → Clear data.** This deletes every tag.
5. Reopen, re-grant, let it index.
6. **Settings → Restore tags from a file** → pick the file.
7. **Expect:** the summary reports items matched, and your tags are back on the right
   photos.

Restore matches on content first, then filename and size. Step 5 matters: restoring
*before* indexing finishes will report unmatched items, which is correct behaviour and why
the dialog tells you to wait and try again.

### Albums keep their order across a reinstall
Make an album, drag its photos into a deliberate order, export a backup from Settings, clear
app data, reindex, and restore. The album should come back with the same photos in the same
order and the same cover. This is the one thing about albums that cannot be regenerated, so
it is the one worth checking.

### Places without a location permission
Deny `ACCESS_MEDIA_LOCATION` (Settings → Apps → Gallery Organizer → Permissions). Everything
else must keep working exactly as before, and Places must say that no photo carries a
location rather than showing an error or an empty map.

### Partial access (Android 14+)

1. Clear data. Relaunch. At the permission dialog choose **Select photos and videos** and
   pick a handful.
2. **Expect:** the app works normally on just those, with a banner offering "Change
   selection" / "Allow all". Nothing should claim it can see your whole library.

---

## 4. The risky features — test these carefully or not at all

### Writing tags into your photo files (off by default)

This is the only feature that modifies your originals. It copies the file, rewrites the
copy, verifies the tags read back, and only then replaces — and any failure leaves the file
untouched. It has never run on a real photo.

**Try it on something disposable first:** copy two or three photos into a test folder, tag
those, and run the export. Then open one in another gallery app and check the keywords.

JPEG and PNG are written in place. **HEIC, RAW and video get a `.xmp` sidecar instead** —
embedding into HEIF needs a container rewrite where a mistake produces an unopenable photo,
so it is deliberately not done. If your camera shoots HEIF, expect sidecars, not embedded
tags.

### Duplicates

Removal uses Android's **trash**, not delete — recoverable for 30 days — always keeps the
oldest copy, and Android shows its own confirmation. Tags on removed copies are kept in case
you restore them.

Because hashing is lazy, the screen may say a lot of items are still unchecked. That is
honest rather than broken; tap **Check them now** if you do not want to wait for the idle
worker.

### Suggested tags

Off by default. Turn it on in Settings, then leave the phone **charging and idle** —
overnight is realistic for a large library. Suggestions appear under **Settings → Review
suggested tags**, grouped by label. Nothing is applied until you accept it.

---

## 5. If something is wrong

```bash
# Everything the app logs
adb logcat --pid=$(adb shell pidof -s com.galleryorganizer)

# Just crashes
adb logcat -b crash

# What the background workers are doing
adb shell dumpsys jobscheduler | grep -A 20 galleryorganizer
```

Useful things to capture in a bug report: what the grid said the item count was, whether
indexing had finished, and whether the phone was on Android 14+ with a partial grant.

**Your tags are always recoverable if you exported them.** If the app misbehaves badly,
clearing app data is safe *provided you have a backup file* — it never touches your photos,
only the app's own database.

---

## 6. Running the tests

```bash
./gradlew testDebugUnitTest   # 248 tests, JVM only, no device needed
./gradlew lintDebug           # must be clean
```

Both also run on every push — see `.github/workflows/build.yml`.

Notably absent: instrumented tests. Everything is covered on the JVM instead — Robolectric
for the real Room database, a fake `ContentProvider` for MediaStore, synthetic JPEG and PNG
bytes for the XMP writer. That was a constraint of the environment this was built in, not a
preference, and it is exactly why the device checks above are worth doing by hand.
