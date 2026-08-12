package com.galleryorganizer.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration tests, on the JVM, with no device.
 *
 * The pattern for every future migration is the one used below: build the *old* schema
 * from its exported JSON, write representative rows through raw SQL, open the database
 * through Room with [ALL_MIGRATIONS] applied, and assert the rows are still there and
 * still mean the same thing. Losing tags to a botched migration is the one failure this
 * app cannot recover from — there is no cloud copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private var opened: AppDatabase? = null

    @After
    fun cleanUp() {
        opened?.close()
        context.deleteDatabase(dbName)
    }

    /** Creates the on-disk database at [version] and returns it for raw writes. */
    private fun seedSchema(version: Int, write: SQLiteDatabase.() -> Unit = {}) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            SchemaBundle.create(version) { raw.execSQL(it) }
            raw.write()
            raw.version = version
        }
    }

    private fun openThroughRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { opened = it }

    @Test
    fun `the exported current schema is exactly what the compiled entities expect`() = runTest {
        // If an entity is edited without bumping the version, Room's identity hash stops
        // matching the committed JSON and this open throws. That makes an accidental
        // silent schema change impossible to merge.
        seedSchema(version = AppDatabase.VERSION)

        val db = openThroughRoom()

        assertThat(db.mediaDao().count()).isEqualTo(0)
        assertThat(db.openHelper.readableDatabase.version).isEqualTo(AppDatabase.VERSION)
    }

    @Test
    fun `tags written against v1 survive every migration to the current build`() = runTest {
        seedSchema(version = 1) {
            execSQL(
                """
                INSERT INTO media (id, content_hash, mediastore_id, uri, display_name,
                    relative_path, bucket_id, bucket_name, mime, is_video, size, date_taken,
                    date_modified, date_added, date_first_indexed, duration, width, height,
                    orientation, is_missing, ocr_text)
                VALUES (1, 'deadbeef', 42, 'content://media/external/images/media/42',
                    'IMG_0042.jpg', 'DCIM/Camera/', 7, 'Camera', 'image/jpeg', 0, 123456,
                    1700000000000, 1700000000, 1700000000, 1700000000000, 0, 4032, 3024,
                    0, 0, NULL)
                """.trimIndent(),
            )
            execSQL("INSERT INTO tag (id, name, parent_id, color, last_used_at, usage_count) VALUES (1, 'Travel', 0, NULL, 0, 0)")
            execSQL("INSERT INTO tag (id, name, parent_id, color, last_used_at, usage_count) VALUES (2, 'Japan', 1, NULL, 0, 0)")
            execSQL("INSERT INTO media_tag (media_id, tag_id, source, created_at) VALUES (1, 2, 'manual', 1700000000000)")
            execSQL("INSERT INTO saved_search (id, name, query_json, created_at, updated_at, pinned, sort_order) VALUES (1, 'Japan trip', '{}', 0, 0, 1, 0)")
            execSQL("INSERT INTO index_state (`key`, `value`) VALUES ('image_date_modified_watermark', '1700000000')")
        }

        val db = openThroughRoom()

        val item = db.mediaDao().byContentHash("deadbeef")
        assertThat(item).isNotNull()
        assertThat(item!!.displayName).isEqualTo("IMG_0042.jpg")

        val itemTags = db.mediaTagDao().tagsFor(item.id)
        assertThat(itemTags.map { it.name }).containsExactly("Japan")
        assertThat(db.tagDao().ancestorChain(itemTags.single().id).map { it.name })
            .containsExactly("Travel", "Japan").inOrder()
        assertThat(db.mediaTagDao().rowsFor(item.id).single().source)
            .isEqualTo(com.galleryorganizer.data.db.entity.TagSource.Manual)
        assertThat(db.indexStateDao().get(IndexStateKeys.IMAGE_WATERMARK)).isEqualTo("1700000000")
        assertThat(db.savedSearchDao().count()).isEqualTo(1)
    }

    @Test
    fun `v1 to v2 adds the restore lookup index without disturbing any data`() = runTest {
        seedSchema(version = 1) {
            execSQL(
                """
                INSERT INTO media (id, content_hash, mediastore_id, uri, display_name,
                    relative_path, bucket_id, bucket_name, mime, is_video, size, date_taken,
                    date_modified, date_added, date_first_indexed, duration, width, height,
                    orientation, is_missing, ocr_text)
                VALUES (1, 'deadbeef', 42, 'content://media/external/images/media/42',
                    'IMG_0042.jpg', 'DCIM/Camera/', 7, 'Camera', 'image/jpeg', 0, 123456,
                    1700000000000, 1700000000, 1700000000, 1700000000000, 0, 4032, 3024,
                    0, 0, 'a receipt')
                """.trimIndent(),
            )
            execSQL("INSERT INTO tag (id, name, parent_id, color, last_used_at, usage_count) VALUES (1, 'Travel', 0, NULL, 5, 3)")
            execSQL("INSERT INTO media_tag (media_id, tag_id, source, created_at) VALUES (1, 1, 'manual', 99)")
        }

        val db = openThroughRoom()

        // The point of the migration: the (size, display_name) lookup restore depends on.
        val indices = db.query("SELECT name FROM sqlite_master WHERE type='index'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertThat(indices).contains("index_media_size_display_name")
        assertThat(db.mediaDao().bySizeAndName(123456, "IMG_0042.jpg")).hasSize(1)

        // ...and nothing it touched lost anything.
        val item = db.mediaDao().byContentHash("deadbeef")!!
        assertThat(item.ocrText).isEqualTo("a receipt")
        assertThat(item.dateFirstIndexed).isEqualTo(1700000000000L)
        assertThat(db.mediaTagDao().rowsFor(item.id).single().createdAt).isEqualTo(99)
        assertThat(db.tagDao().byId(1)!!.usageCount).isEqualTo(3)
    }

    @Test
    fun `v2 to v3 adds the suggestion queue and queues every existing item for analysis`() =
        runTest {
            seedSchema(version = 2) {
                execSQL(
                    """
                    INSERT INTO media (id, content_hash, mediastore_id, uri, display_name,
                        relative_path, bucket_id, bucket_name, mime, is_video, size, date_taken,
                        date_modified, date_added, date_first_indexed, duration, width, height,
                        orientation, is_missing, ocr_text)
                    VALUES (1, 'deadbeef', 42, 'content://media/external/images/media/42',
                        'IMG_0042.jpg', 'DCIM/Camera/', 7, 'Camera', 'image/jpeg', 0, 123456,
                        1700000000000, 1700000000, 1700000000, 1700000000000, 0, 4032, 3024,
                        0, 0, 'a receipt')
                    """.trimIndent(),
                )
                execSQL("INSERT INTO tag (id, name, parent_id, color, last_used_at, usage_count) VALUES (1, 'Travel', 0, NULL, 0, 0)")
                execSQL("INSERT INTO media_tag (media_id, tag_id, source, created_at) VALUES (1, 1, 'manual', 1)")
            }

            val db = openThroughRoom()

            // Room validates the whole schema on open, so this passing means the migration's
            // hand-written DDL matches Room's generated v3 exactly — one character of drift
            // in an index name or a collation and the app would refuse to start.
            assertThat(db.suggestionDao().count()).isEqualTo(0)

            val item = db.mediaDao().byContentHash("deadbeef")!!
            // Default 0 = "not looked at yet", so existing rows simply join the queue.
            assertThat(item.autoScanState)
                .isEqualTo(com.galleryorganizer.data.db.entity.MediaEntity.AUTO_SCAN_PENDING)
            assertThat(item.ocrText).isEqualTo("a receipt")
            assertThat(db.mediaTagDao().tagsFor(item.id).map { it.name }).containsExactly("Travel")
            assertThat(db.mediaDao().unanalysedCount()).isEqualTo(1)
        }

    @Test
    fun `a v1 database migrates all the way to the current version in one open`() = runTest {
        seedSchema(version = 1) {
            execSQL(
                """
                INSERT INTO media (id, content_hash, mediastore_id, uri, display_name,
                    relative_path, bucket_id, bucket_name, mime, is_video, size, date_taken,
                    date_modified, date_added, date_first_indexed, duration, width, height,
                    orientation, is_missing, ocr_text)
                VALUES (1, 'deadbeef', 42, 'content://media/external/images/media/42',
                    'IMG_0042.jpg', 'DCIM/Camera/', 7, 'Camera', 'image/jpeg', 0, 123456,
                    1700000000000, 1700000000, 1700000000, 1700000000000, 0, 4032, 3024,
                    0, 0, NULL)
                """.trimIndent(),
            )
            execSQL("INSERT INTO tag (id, name, parent_id, color, last_used_at, usage_count) VALUES (1, 'Travel', 0, NULL, 0, 0)")
            execSQL("INSERT INTO media_tag (media_id, tag_id, source, created_at) VALUES (1, 1, 'manual', 1)")
        }

        val db = openThroughRoom()

        assertThat(db.openHelper.readableDatabase.version).isEqualTo(AppDatabase.VERSION)
        assertThat(db.mediaTagDao().count()).isEqualTo(1)
        assertThat(db.mediaDao().bySizeAndName(123456, "IMG_0042.jpg")).hasSize(1)
        assertThat(db.suggestionDao().count()).isEqualTo(0)
        assertThat(db.mediaDao().unanalysedCount()).isEqualTo(1)
    }

    @Test
    fun `v3 to v4 swaps the boolean indices for the composite the grid needs`() = runTest {
        seedSchema(version = 3) {
            execSQL(
                """
                INSERT INTO media (id, content_hash, mediastore_id, uri, display_name,
                    relative_path, bucket_id, bucket_name, mime, is_video, size, date_taken,
                    date_modified, date_added, date_first_indexed, duration, width, height,
                    orientation, is_missing, ocr_text, auto_scan_state)
                VALUES (1, 'deadbeef', 42, 'content://media/external/images/media/42',
                    'IMG_0042.jpg', 'DCIM/Camera/', 7, 'Camera', 'image/jpeg', 0, 123456,
                    1700000000000, 1700000000, 1700000000, 1700000000000, 0, 4032, 3024,
                    0, 0, 'a receipt', 1)
                """.trimIndent(),
            )
            execSQL("INSERT INTO tag (id, name, parent_id, color, last_used_at, usage_count) VALUES (1, 'Travel', 0, NULL, 0, 0)")
            execSQL("INSERT INTO media_tag (media_id, tag_id, source, created_at) VALUES (1, 1, 'manual', 1)")
            execSQL("INSERT INTO label_suggestion (media_id, label, confidence, status, created_at) VALUES (1, 'Beach', 0.9, 'pending', 1)")
        }

        val db = openThroughRoom()

        val indices = db.query("SELECT name FROM sqlite_master WHERE type='index'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertThat(indices).contains("index_media_is_missing_date_taken_id")
        assertThat(indices).contains("index_media_bucket_id_date_taken")
        // The two-value indices are gone: they excluded nothing and misled the planner
        // into sorting the whole library.
        assertThat(indices).doesNotContain("index_media_is_missing")
        assertThat(indices).doesNotContain("index_media_is_video")

        val item = db.mediaDao().byContentHash("deadbeef")!!
        assertThat(item.ocrText).isEqualTo("a receipt")
        assertThat(item.autoScanState).isEqualTo(1)
        assertThat(db.mediaTagDao().tagsFor(item.id).map { it.name }).containsExactly("Travel")
        assertThat(db.suggestionDao().count()).isEqualTo(1)
    }

    @Test
    fun `every registered migration forms an unbroken chain up to the current version`() {
        if (ALL_MIGRATIONS.isEmpty()) {
            assertThat(AppDatabase.VERSION).isEqualTo(1)
            return
        }
        val byStart = ALL_MIGRATIONS.associateBy { it.startVersion }
        var version = 1
        while (version < AppDatabase.VERSION) {
            val step = byStart[version]
            assertThat(step).isNotNull()
            version = step!!.endVersion
        }
        assertThat(version).isEqualTo(AppDatabase.VERSION)
    }
}
