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

/**
 * v2 → v3: the suggestion queue for on-device auto-tagging, and `media.auto_scan_state`.
 *
 * The column defaults to 0 ("not looked at yet") so every existing row is simply queued
 * for analysis. Nothing is read, rewritten or dropped, so no tag can be lost here either.
 * The `label_suggestion` DDL is copied verbatim from Room's generated v3 schema — an index
 * name or a collation that differs by one character makes Room reject the database at
 * startup, and the migration test is what proves it does not.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `media` ADD COLUMN `auto_scan_state` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_auto_scan_state` " +
                "ON `media` (`auto_scan_state`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `label_suggestion` (" +
                "`media_id` INTEGER NOT NULL, " +
                "`label` TEXT NOT NULL COLLATE NOCASE, " +
                "`confidence` REAL NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`media_id`, `label`), " +
                "FOREIGN KEY(`media_id`) REFERENCES `media`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_label_suggestion_status_label` " +
                "ON `label_suggestion` (`status`, `label`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_label_suggestion_label` " +
                "ON `label_suggestion` (`label`)",
        )
    }
}

/**
 * v3 → v4: replace two single-column boolean indices with composites that carry the sort.
 *
 * `QueryPlanTest` caught SQLite choosing `index_media_is_missing` for the grid — an index
 * over a column with two distinct values, so it excluded nothing — and then sorting the
 * entire result set in a temp B-tree. At 150,000 rows that is the whole library sorted in
 * memory on every grid load. `(is_missing, date_taken, id)` lets the planner seek and then
 * walk the range already in order, so `LIMIT` stops early and there is no sort.
 *
 * Indices only: no row is read, rewritten or deleted.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_media_is_missing`")
        db.execSQL("DROP INDEX IF EXISTS `index_media_is_video`")
        db.execSQL("DROP INDEX IF EXISTS `index_media_bucket_id`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_is_missing_date_taken_id` " +
                "ON `media` (`is_missing`, `date_taken`, `id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_bucket_id_date_taken` " +
                "ON `media` (`bucket_id`, `date_taken`)",
        )
    }
}

/**
 * v4 → v5: albums, tag kinds, and EXIF location.
 *
 * Three additive changes, no rewrites:
 *
 * - `album` / `album_media` for hand-curated collections.
 * - `tag.kind`, defaulting to `note` so every existing tag keeps working and simply lands
 *   in "Other" until the user says otherwise.
 * - `media.latitude` / `longitude` / `location_state`, defaulting to NULL and 0 so every
 *   existing row is queued for a location read rather than assumed to have none.
 *
 * The DDL is copied verbatim from Room's generated v5 schema; the migration test is what
 * proves it, since a single character of drift makes Room refuse to open the database.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tag` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'note'")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_kind` ON `tag` (`kind`)")

        db.execSQL("ALTER TABLE `media` ADD COLUMN `latitude` REAL")
        db.execSQL("ALTER TABLE `media` ADD COLUMN `longitude` REAL")
        db.execSQL("ALTER TABLE `media` ADD COLUMN `location_state` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_location_state` ON `media` (`location_state`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_latitude_longitude` " +
                "ON `media` (`latitude`, `longitude`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `album` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL COLLATE NOCASE, " +
                "`description` TEXT NOT NULL, " +
                "`cover_media_id` INTEGER, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "`sort_order` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_album_name` ON `album` (`name`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `album_media` (" +
                "`album_id` INTEGER NOT NULL, " +
                "`media_id` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`added_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`album_id`, `media_id`), " +
                "FOREIGN KEY(`album_id`) REFERENCES `album`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`media_id`) REFERENCES `media`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_media_media_id` ON `album_media` (`media_id`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_album_media_album_id_position` " +
                "ON `album_media` (`album_id`, `position`)",
        )
    }
}

val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
