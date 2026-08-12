package com.galleryorganizer.data.db

import androidx.room.migration.Migration

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
 * Schema v1 is the initial release, so there is nothing here yet. `MigrationTestSupport`
 * already carries the v1 DDL so that the first real migration only has to add its own
 * step and its own assertions.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf()
