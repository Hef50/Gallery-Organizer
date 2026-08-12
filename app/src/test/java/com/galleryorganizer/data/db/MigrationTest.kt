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
    fun `the exported v1 schema is exactly what the compiled entities expect`() = runTest {
        // If an entity is edited without bumping the version, Room's identity hash stops
        // matching the committed JSON and this open throws. That makes an accidental
        // silent schema change impossible to merge.
        seedSchema(version = 1)

        val db = openThroughRoom()

        assertThat(db.mediaDao().count()).isEqualTo(0)
        assertThat(db.openHelper.readableDatabase.version).isEqualTo(AppDatabase.VERSION)
    }

    @Test
    fun `tags written against v1 survive being opened by the current build`() = runTest {
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
