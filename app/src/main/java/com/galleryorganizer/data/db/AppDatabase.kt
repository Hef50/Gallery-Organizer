package com.galleryorganizer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.galleryorganizer.data.db.dao.IndexStateDao
import com.galleryorganizer.data.db.dao.MediaDao
import com.galleryorganizer.data.db.dao.MediaFtsDao
import com.galleryorganizer.data.db.dao.MediaTagDao
import com.galleryorganizer.data.db.dao.SavedSearchDao
import com.galleryorganizer.data.db.dao.TagDao
import com.galleryorganizer.data.db.entity.IndexStateEntity
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.db.entity.MediaFtsEntity
import com.galleryorganizer.data.db.entity.MediaTagCrossRef
import com.galleryorganizer.data.db.entity.SavedSearchEntity
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagSource

class Converters {
    @TypeConverter
    fun tagSourceToWire(source: TagSource): String = source.wire

    @TypeConverter
    fun wireToTagSource(wire: String): TagSource = TagSource.fromWire(wire)
}

@Database(
    entities = [
        MediaEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        SavedSearchEntity::class,
        IndexStateEntity::class,
        MediaFtsEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun tagDao(): TagDao
    abstract fun mediaTagDao(): MediaTagDao
    abstract fun mediaFtsDao(): MediaFtsDao
    abstract fun indexStateDao(): IndexStateDao
    abstract fun savedSearchDao(): SavedSearchDao

    companion object {
        const val VERSION = 1
        const val NAME = "gallery-organizer.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*ALL_MIGRATIONS)
                // Deliberately NOT fallbackToDestructiveMigration: the tag database is
                // the one thing in this app that cannot be regenerated from the device.
                // A missing migration must fail loudly, not wipe the user's work.
                .addCallback(EnableForeignKeys)
                .build()

        /**
         * Room only turns foreign keys on for its own connections when asked. Without
         * this, `media_tag`'s CASCADE rules would silently not fire.
         */
        private val EnableForeignKeys = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}

/** Keys used in the `index_state` table. */
object IndexStateKeys {
    /** Highest `MediaStore.DATE_MODIFIED` (seconds) fully committed to the database. */
    const val IMAGE_WATERMARK = "image_date_modified_watermark"
    const val VIDEO_WATERMARK = "video_date_modified_watermark"

    /** Set while a pass is in flight so an interrupted run can resume rather than restart. */
    const val RESUME_CURSOR = "resume_cursor"

    /** Wall-clock millis of the last pass that ran to completion. */
    const val LAST_FULL_PASS_AT = "last_full_pass_at"

    /** "1" once the very first pass has finished, so the UI can stop saying "indexing". */
    const val INITIAL_INDEX_COMPLETE = "initial_index_complete"

    /** Progress of the P9 auto-tagging sweep: the highest media id already examined. */
    const val AUTO_TAG_CURSOR = "auto_tag_cursor"
}
