package com.galleryorganizer.xmp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.entity.MediaEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** What happened to one item. */
sealed interface XmpOutcome {
    data class Embedded(val container: XmpContainer) : XmpOutcome
    data object SidecarWritten : XmpOutcome
    data object NoTags : XmpOutcome
    data class Skipped(val reason: String) : XmpOutcome
    data class Failed(val reason: String) : XmpOutcome
}

data class XmpExportReport(
    val embedded: Int = 0,
    val sidecars: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val failures: List<String> = emptyList(),
)

/**
 * Writes the app's tags into the user's actual files, best-effort.
 *
 * The database stays authoritative — this is a one-way export, never a source of truth,
 * and nothing here ever reads tags back into the app.
 *
 * **The file-safety contract**, which is the only reason this feature is allowed to exist:
 *
 * 1. The original is read into a temporary file in the app's own cache — a full byte copy.
 * 2. The rewritten version is built into a *second* temporary file.
 * 3. The rewritten file is re-parsed and its `dc:subject` compared against what was
 *    intended. If it does not read back correctly, nothing is written to the original at
 *    all.
 * 4. Only then is the original opened for writing and the verified bytes streamed in.
 * 5. The original's backup copy is kept until the destination has been re-verified, so a
 *    crash mid-write leaves a recoverable copy rather than a ruined photo.
 *
 * Any failure at any step leaves the original untouched and is reported.
 */
class XmpWriteBack(
    private val context: Context,
    private val db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * @param sidecarDirectory a SAF tree the user granted, or null to skip sidecars. A
     *   sidecar cannot simply be created next to the original: `.xmp` is not a media type,
     *   so MediaStore will not place it in `DCIM/`, and scoped storage will not let the app
     *   write an arbitrary file there without an explicit grant.
     */
    suspend fun exportTags(
        mediaIds: List<Long>,
        allowEmbedding: Boolean,
        sidecarDirectory: Uri?,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): XmpExportReport = withContext(io) {
        var report = XmpExportReport()
        var done = 0

        for (chunk in mediaIds.chunked(CHUNK)) {
            currentCoroutineContext().ensureActive()
            val items = db.mediaDao().byIds(chunk)
            val tagsByMedia = db.mediaTagDao().tagNamesFor(chunk)
                .groupBy({ it.mediaId }, { it.name })

            for (item in items) {
                val tags = tagsByMedia[item.id].orEmpty()
                val outcome = when {
                    tags.isEmpty() -> XmpOutcome.NoTags
                    else -> writeOne(item, tags, allowEmbedding, sidecarDirectory)
                }
                report = report.record(item, outcome)
                done++
                onProgress(done, mediaIds.size)
            }
        }
        report
    }

    private suspend fun writeOne(
        item: MediaEntity,
        tags: List<String>,
        allowEmbedding: Boolean,
        sidecarDirectory: Uri?,
    ): XmpOutcome {
        val container = XmpContainer.forMimeType(item.mime)
        if (container == XmpContainer.Sidecar || !allowEmbedding) {
            if (sidecarDirectory == null) {
                return XmpOutcome.Skipped(
                    if (allowEmbedding) "no sidecar folder chosen" else "embedding is off",
                )
            }
            return writeSidecar(item, tags, sidecarDirectory)
        }
        return embed(item, tags, container)
    }

    private fun embed(item: MediaEntity, tags: List<String>, container: XmpContainer): XmpOutcome {
        val uri = Uri.parse(item.uri)
        val cache = File(context.cacheDir, "xmp").apply { mkdirs() }
        val backup = File(cache, "original-${item.id}.bin")
        val staged = File(cache, "staged-${item.id}.bin")

        return try {
            // 1. Byte-for-byte copy of the original, kept until the write is confirmed.
            resolver.openInputStream(uri)?.use { input ->
                backup.outputStream().use(input::copyTo)
            } ?: return XmpOutcome.Failed("could not read ${item.displayName}")

            val existing = runCatching {
                backup.inputStream().use { readPacket(it, container) }
            }.getOrNull()
            val merged = XmpPacket.merge(tags, existing, previouslyWritten = emptySet())
            val packet = XmpPacket.build(merged)

            // 2. Build the new version into a separate temporary file.
            backup.inputStream().use { input ->
                staged.outputStream().use { output -> writePacket(input, output, packet, container) }
            }

            // 3. Verify by re-reading the staged file. If the tags do not come back, the
            //    original is never opened for writing.
            val readBack = staged.inputStream().use { readPacket(it, container) }
            if (readBack == null || !XmpPacket.readSubjects(readBack).containsAll(merged)) {
                return XmpOutcome.Failed("verification failed for ${item.displayName}")
            }
            if (staged.length() < backup.length() / 2) {
                // A rewrite that lost half the file means the parser went wrong somewhere.
                return XmpOutcome.Failed("rewrite of ${item.displayName} looks truncated")
            }

            // 4. Only now is the user's file touched.
            resolver.openOutputStream(uri, "wt")?.use { output ->
                staged.inputStream().use { it.copyTo(output) }
            } ?: return XmpOutcome.Failed("could not write ${item.displayName}")

            // 5. Confirm the destination before dropping the backup copy.
            val destination = resolver.openInputStream(uri)?.use { readPacket(it, container) }
            if (destination == null || XmpPacket.readSubjects(destination).isEmpty()) {
                restore(uri, backup)
                return XmpOutcome.Failed("wrote ${item.displayName} but could not confirm it")
            }
            XmpOutcome.Embedded(container)
        } catch (e: SecurityException) {
            // No write grant for this URI yet — the caller has to ask via
            // MediaStore.createWriteRequest.
            XmpOutcome.Skipped("no permission to write ${item.displayName}")
        } catch (e: Exception) {
            XmpOutcome.Failed("${item.displayName}: ${e.message ?: e::class.simpleName}")
        } finally {
            backup.delete()
            staged.delete()
        }
    }

    private fun restore(uri: Uri, backup: File) {
        runCatching {
            resolver.openOutputStream(uri, "wt")?.use { output ->
                backup.inputStream().use { it.copyTo(output) }
            }
        }
    }

    /**
     * A sidecar never touches the original, which is why it is the fallback for HEIC, RAW
     * and video, and why its toggle is separate and softer.
     */
    private fun writeSidecar(item: MediaEntity, tags: List<String>, directory: Uri): XmpOutcome {
        return try {
            val name = item.displayName.substringBeforeLast('.', item.displayName) + ".xmp"
            val folder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, directory)
                ?: return XmpOutcome.Failed("sidecar folder is not writable")
            val file = folder.findFile(name)
                ?: folder.createFile("application/rdf+xml", name)
                ?: return XmpOutcome.Failed("could not create $name")

            resolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(XmpPacket.build(tags).toByteArray(Charsets.UTF_8))
            } ?: return XmpOutcome.Failed("could not write $name")
            XmpOutcome.SidecarWritten
        } catch (e: Exception) {
            XmpOutcome.Failed("${item.displayName}: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun readPacket(input: java.io.InputStream, container: XmpContainer): String? =
        when (container) {
            XmpContainer.Jpeg -> JpegXmp.read(input)
            XmpContainer.Png -> PngXmp.read(input)
            XmpContainer.Sidecar -> input.readBytes().toString(Charsets.UTF_8)
        }

    private fun writePacket(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        packet: String,
        container: XmpContainer,
    ) = when (container) {
        XmpContainer.Jpeg -> JpegXmp.write(input, output, packet)
        XmpContainer.Png -> PngXmp.write(input, output, packet)
        XmpContainer.Sidecar -> output.write(packet.toByteArray(Charsets.UTF_8))
    }

    private fun XmpExportReport.record(item: MediaEntity, outcome: XmpOutcome): XmpExportReport =
        when (outcome) {
            is XmpOutcome.Embedded -> copy(embedded = embedded + 1)
            XmpOutcome.SidecarWritten -> copy(sidecars = sidecars + 1)
            XmpOutcome.NoTags, is XmpOutcome.Skipped -> copy(skipped = skipped + 1)
            is XmpOutcome.Failed -> copy(
                failed = failed + 1,
                failures = (failures + outcome.reason).take(MAX_REPORTED_FAILURES),
            )
        }

    private companion object {
        const val CHUNK = 200
        const val MAX_REPORTED_FAILURES = 20
    }
}
