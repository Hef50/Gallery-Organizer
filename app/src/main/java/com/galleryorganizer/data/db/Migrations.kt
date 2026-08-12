package com.galleryorganizer.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every migration, in order. Registered on the builder in [AppDatabase.build].
 *
 * The rules here, because getting them wrong loses the only data the user cannot
 * regenerate:
 *
 * - never `fallbackToDestructiveMigration`;
 * - never drop a column that holds tags or hashes;
 * - every migration gets a test in `MigrationTest` that builds the *old* schema, writes
 *   representative rows, migrates, and asserts the rows survived intact.
 *
 * `SchemaBundle` in the tests rebuilds any old schema from Room's own committed exported
 * JSON, so a migration test never drifts from what actually shipped.
 */
/**
 * v1 → v2: index `(size, display_name)`.
 *
 * Restore has to find an item that was tagged before it was ever hashed, and the only
 * thing it can match on then is size plus filename. That lookup runs once per backed-up
 * item; unindexed it is a full scan of 150k rows each time. Pure index addition — no data
 * is read, written or dropped, so there is nothing here that can lose a tag.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_size_display_name` " +
                "ON `media` (`size`, `display_name`)",
        )
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
