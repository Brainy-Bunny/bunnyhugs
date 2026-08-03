package com.ssbmedia.twogether.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [TogetherSession::class, DateIdea::class, TimeCapsule::class, Moment::class, MomentNote::class, Milestone::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): TogetherSessionDao
    abstract fun dateIdeaDao(): DateIdeaDao
    abstract fun timeCapsuleDao(): TimeCapsuleDao
    abstract fun momentDao(): MomentDao
    abstract fun momentNoteDao(): MomentNoteDao
    abstract fun milestoneDao(): MilestoneDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2 added TogetherSession.isManual (see Entities.kt). Column type/NOT NULL/default here
         * must match the @Entity declaration exactly (INTEGER NOT NULL DEFAULT 0, matching `val isManual:
         * Boolean = false`) or Room's schema validation throws IllegalStateException("Migration didn't
         * properly handle...") the first time the DB is opened after this migration runs.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE together_sessions ADD COLUMN isManual INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 -> v3, this feature batch:
         *  - together_sessions.syncId (Feature A cross-device sync identity)
         *  - moments.syncId + moments.isRemote (Feature D)
         *  - new moment_notes table (Feature D)
         *  - new milestones table (Feature F)
         *
         * syncId columns are added as TEXT NOT NULL DEFAULT '' (SQLite has no UUID() builtin to default
         * to something unique per row in the ALTER TABLE itself), then immediately backfilled row-by-row
         * with a real random UUID via [backfillSyncIds] - safe to do independently on each existing
         * install since these pre-migration rows predate sync entirely and can never collide with
         * whatever a partner's device independently backfills for its own pre-existing rows.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE together_sessions ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                backfillSyncIds(db, "together_sessions")

                db.execSQL("ALTER TABLE moments ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE moments ADD COLUMN isRemote INTEGER NOT NULL DEFAULT 0")
                backfillSyncIds(db, "moments")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS moment_notes (
                        momentSyncId TEXT NOT NULL,
                        authorDeviceId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(momentSyncId, authorDeviceId)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS milestones (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        month INTEGER NOT NULL,
                        day INTEGER NOT NULL,
                        year INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }

            private fun backfillSyncIds(db: SupportSQLiteDatabase, table: String) {
                val cursor = db.query("SELECT id FROM $table WHERE syncId = ''")
                cursor.use {
                    val idIndex = it.getColumnIndexOrThrow("id")
                    while (it.moveToNext()) {
                        val rowId = it.getLong(idIndex)
                        db.execSQL("UPDATE $table SET syncId = ? WHERE id = ?", arrayOf(UUID.randomUUID().toString(), rowId))
                    }
                }
            }
        }

        /**
         * v3 -> v4, Feature 2 (photo sync): adds moments.photoDownloaded, splitting "do I have the
         * actual photo bytes locally" out of isRemote's "whose photo is this / did metadata arrive via
         * sync" (see Moment.photoDownloaded's doc). Backfilled true for every existing row by the
         * ALTER TABLE default (a photo taken on this device, or already-downloaded before this
         * migration, genuinely has real bytes sitting at photoUri already) then flipped back to false
         * for whatever's currently a remote stub (isRemote=1) - those never had real bytes under the old
         * metadata-only sync, which is exactly what GattSyncManager's new photo-transfer phase now goes
         * and fetches on the next together-session. Never destructive - no existing Moment row, file, or
         * any other table is touched, only this one new column.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE moments ADD COLUMN photoDownloaded INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE moments SET photoDownloaded = 0 WHERE isRemote = 1")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "twogether.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // Safety net only for a FUTURE schema version we didn't write a real migration for -
                    // the 1->2 and 2->3 paths above are always handled for real, so existing users'
                    // sessions/moments/date-ideas/capsules/notes/milestones are never silently wiped by this.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
