package com.galleryorganizer.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The backup file format: **JSON Lines** — one self-contained JSON object per line, with a
 * `type` discriminator.
 *
 * A single top-level JSON object would have to be built in memory to write and parsed in
 * memory to read; at 150k items that is a large allocation on both sides for a file whose
 * whole purpose is to be readable when things have gone wrong. Line-delimited records
 * stream in constant memory in both directions, and — the reason that actually matters
 * here — a file truncated by a full disk or a cancelled write still restores everything up
 * to the truncation point instead of being a total loss. This user has no cloud safety
 * net. See DECISIONS.md.
 */
@Serializable
sealed interface BackupRecord

@Serializable
@SerialName("header")
data class BackupHeader(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val appVersion: String = "",
    /** Advisory only — the import never trusts these over what it actually reads. */
    val tagCount: Int = 0,
    val itemCount: Int = 0,
    val savedSearchCount: Int = 0,
) : BackupRecord {
    companion object {
        const val FORMAT = "gallery-organizer-backup"
        const val VERSION = 1
    }
}

/**
 * A tag, identified by its full path rather than by id: ids are local database details
 * that differ between installs, and restoring onto a device that already has a `Travel`
 * tag must merge with it rather than create a second one.
 *
 * [ref] is only meaningful inside a single file — items and saved searches point at it.
 */
@Serializable
@SerialName("tag")
data class TagRecord(
    val ref: Long,
    val path: List<String>,
    val color: Int? = null,
) : BackupRecord

@Serializable
data class TagAssignment(
    val tag: Long,
    val source: String = "manual",
    val createdAt: Long = 0,
)

/**
 * One tagged item.
 *
 * [hash] is the strong identity, but it is nullable because hashing is lazy and an item
 * can perfectly well be tagged before it has been hashed. [size] and [displayName] are the
 * fallback match key, which is also what makes a restore work on a *fresh install* where
 * nothing has been hashed yet.
 */
@Serializable
@SerialName("item")
data class ItemRecord(
    val hash: String? = null,
    val displayName: String,
    val relativePath: String = "",
    val size: Long,
    val dateTaken: Long = 0,
    val tags: List<TagAssignment> = emptyList(),
    /** Expensive to regenerate — it is minutes of ML Kit over the library. */
    val ocrText: String? = null,
) : BackupRecord

@Serializable
@SerialName("search")
data class SavedSearchRecord(
    val name: String,
    val queryJson: String,
    val pinned: Boolean = false,
) : BackupRecord

/** What an export produced. */
data class BackupStats(
    val tags: Int = 0,
    val items: Int = 0,
    val assignments: Int = 0,
    val savedSearches: Int = 0,
)

/** What an import did, in the words the confirmation screen uses. */
data class ImportReport(
    val tagsCreated: Int = 0,
    val tagsMerged: Int = 0,
    val itemsMatchedByHash: Int = 0,
    val itemsMatchedByName: Int = 0,
    val itemsUnmatched: Int = 0,
    val assignmentsApplied: Int = 0,
    val savedSearchesImported: Int = 0,
    val savedSearchesSkipped: Int = 0,
    val ocrRestored: Int = 0,
    val malformedLines: Int = 0,
    val wrongFormat: Boolean = false,
) {
    val itemsMatched: Int get() = itemsMatchedByHash + itemsMatchedByName
}

internal val BackupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
    // Every record must be one line; a pretty-printed record would break the format.
    prettyPrint = false
}
