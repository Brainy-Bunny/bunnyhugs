package com.ssbmedia.twogether.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [TogetherSession::class, DateIdea::class, TimeCapsule::class, Moment::class, MomentNote::class, Milestone::class, ListCategory::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): TogetherSessionDao
    abstract fun dateIdeaDao(): DateIdeaDao
    abstract fun timeCapsuleDao(): TimeCapsuleDao
    abstract fun momentDao(): MomentDao
    abstract fun momentNoteDao(): MomentNoteDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun listCategoryDao(): ListCategoryDao

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

        /**
         * v4 -> v5, manual-session delete (tombstone sync): adds together_sessions.updatedAt and
         * .deleted, the same pattern DateIdea/MomentNote/Milestone already use, so a manually-backfilled
         * session entered wrong can be soft-deleted and have that deletion propagate to the partner's
         * phone (see SessionRepository.softDeleteManual / mergeRemoteSessions). updatedAt can't be
         * defaulted to another column's value directly in ALTER TABLE ADD COLUMN, hence the follow-up
         * UPDATE backfilling it from the existing startedAt - same reasoning as MIGRATION_2_3's
         * backfillSyncIds, just simple enough here not to need its own helper function. deleted defaults
         * to 0 for every existing row, which is correct: nothing was ever deletable before this feature
         * existed, so no pre-migration row can possibly be a tombstone.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE together_sessions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE together_sessions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE together_sessions SET updatedAt = startedAt")
            }
        }

        /**
         * v5 -> v6, moment delete (tombstone sync): adds moments.updatedAt and .deleted, the same pattern
         * MIGRATION_4_5 just added for together_sessions - so a Moment (photo) can be soft-deleted and have
         * that deletion propagate to the partner's phone (see MomentRepository.softDelete /
         * mergeRemoteStubs). Unlike sessions, there's no isManual-equivalent restriction on which rows are
         * deletable here - see Moment.deleted's doc. updatedAt can't be defaulted to another column's value
         * directly in ALTER TABLE ADD COLUMN, hence the follow-up UPDATE backfilling it from the existing
         * takenAt - same reasoning as MIGRATION_4_5's startedAt backfill. deleted defaults to 0 for every
         * existing row, which is correct: nothing was ever deletable before this feature existed, so no
         * pre-migration row can possibly be a tombstone.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE moments ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE moments ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE moments SET updatedAt = takenAt")
            }
        }

        /**
         * v6 -> v7, Time Capsule anti-cheat rework: adds time_capsules.manualHoursAtCreation (see
         * TimeCapsule's own doc for what it means and why). For every EXISTING capsule (created before
         * this field existed), backfills it to the couple's current total manual-backfill hours - a raw
         * SUM over together_sessions, not the app's real interval-merge-deduped total (that logic isn't
         * expressible in SQL), so it's a close approximation rather than exact if a couple has manual
         * sessions that overlap each other (a rare edge case). This deliberately GRANDFATHERS IN whatever
         * manual backfill already exists at upgrade time, rather than defaulting to 0 - defaulting to 0
         * would make every existing locked capsule harder to unlock the moment this update installs
         * (since any pre-existing backfill would look like it was "added after creation" to the new
         * anti-cheat math), which is exactly the confusing regression this rework exists to avoid. Any
         * NEW capsule created after this migration gets an exact, correctly-interval-merged snapshot
         * instead - see TimeCapsuleRepository.add / StatsCalculator.manualHoursCredit.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE time_capsules ADD COLUMN manualHoursAtCreation REAL NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE time_capsules SET manualHoursAtCreation = (
                        SELECT COALESCE(SUM(endedAt - startedAt), 0) / 3600000.0
                        FROM together_sessions
                        WHERE isManual = 1 AND deleted = 0 AND endedAt IS NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v7 -> v8, "Our Lists" (multiple named date-idea lists instead of one flat global checklist):
         * adds the new list_categories table (same column shape as ListCategory/Milestone) and seeds it
         * with exactly one row - the default "Date Ideas" list at [DEFAULT_LIST_ID], a HARDCODED constant
         * rather than a freshly-generated UUID (see that constant's own doc for why: two independent
         * devices' migrations must create a list with the identical id, or the couple ends up with two
         * un-mergeable "Date Ideas" lists the first time they sync after upgrading). now is captured once
         * in Kotlin (not per-row SQL `strftime`/etc) and interpolated into the INSERT, same style as
         * MIGRATION_2_3's backfillSyncIds building dynamic SQL from Kotlin.
         *
         * date_ideas.category (nullable, unused - never read/displayed anywhere, confirmed by grep, only
         * ever written as null) becomes date_ideas.listId (NOT NULL, defaulting every existing row to
         * [DEFAULT_LIST_ID] so every pre-existing idea lands in the new default list). This can't be done
         * with a plain ALTER TABLE (renaming/dropping a single column needs SQLite features newer than
         * what minSdk 26 can rely on every device having) - instead this rebuilds the table via the
         * standard portable recipe: create date_ideas_new with the final column set, copy every row across
         * (substituting the literal [DEFAULT_LIST_ID] for the old category column), drop the old table,
         * and rename the new one into its place. ALTER TABLE ... RENAME TO (renaming a whole table) IS
         * safe/portable on every SQLite version this app ships against - only single-column RENAME/DROP is
         * being avoided here.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS list_categories (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO list_categories (id, name, createdAt, updatedAt, deleted) VALUES ('$DEFAULT_LIST_ID', 'Date Ideas', $now, $now, 0)"
                )

                db.execSQL(
                    """
                    CREATE TABLE date_ideas_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        text TEXT NOT NULL,
                        listId TEXT NOT NULL,
                        done INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO date_ideas_new (id, text, listId, done, updatedAt, deleted) " +
                        "SELECT id, text, '$DEFAULT_LIST_ID', done, updatedAt, deleted FROM date_ideas"
                )
                db.execSQL("DROP TABLE date_ideas")
                db.execSQL("ALTER TABLE date_ideas_new RENAME TO date_ideas")
            }
        }

        /**
         * v8 -> v9, Time Capsule sync: adds time_capsules.syncId/updatedAt/deleted, the same
         * cross-device-identity + LWW-merge + tombstone shape every other synced entity already has -
         * see TimeCapsule's own doc and TimeCapsuleRepository.mergeRemote for why a capsule made for
         * your partner never reached them until now. syncId backfilled per-row with a fresh UUID (same
         * recipe as MIGRATION_2_3's backfillSyncIds, duplicated inline here since that helper is a
         * private member of MIGRATION_2_3's own anonymous Migration instance, not shared state) -
         * pre-existing capsules predate sync entirely and can never collide with whatever a partner's
         * device independently backfills for its own. updatedAt backfilled from createdAt, same
         * reasoning as MIGRATION_4_5/5_6's startedAt/takenAt backfills. deleted defaults to 0 - nothing
         * was ever deletable before this feature existed, so no pre-migration row can possibly be a
         * tombstone.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE time_capsules ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                val cursor = db.query("SELECT id FROM time_capsules WHERE syncId = ''")
                cursor.use {
                    val idIndex = it.getColumnIndexOrThrow("id")
                    while (it.moveToNext()) {
                        val rowId = it.getLong(idIndex)
                        db.execSQL("UPDATE time_capsules SET syncId = ? WHERE id = ?", arrayOf(UUID.randomUUID().toString(), rowId))
                    }
                }

                db.execSQL("ALTER TABLE time_capsules ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE time_capsules SET updatedAt = createdAt")

                db.execSQL("ALTER TABLE time_capsules ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Seeds the default "Date Ideas" list (id == DEFAULT_LIST_ID) for a genuinely BRAND-NEW install -
         * i.e. no pre-existing database file at all, so Room creates the schema fresh at the CURRENT
         * version and none of MIGRATION_1_2..MIGRATION_7_8 ever run (migrations only fire when upgrading
         * an EXISTING older-version database - Room's own documented behavior). Without this callback,
         * MIGRATION_7_8's own default-list INSERT (which only benefits an existing user's upgrade path)
         * would never happen for a first-time installer, leaving "Our Lists" with zero lists on first
         * open - a genuinely confusing empty-state first impression, and inconsistent with what every
         * upgrading user sees (their old ideas already sitting under a real "Date Ideas" list). Uses the
         * exact same id/name so a first-time install and an upgraded install converge on an identical
         * default list either way - see DEFAULT_LIST_ID's own doc for why that id must stay fixed.
         */
        private val SEED_DEFAULT_LIST_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO list_categories (id, name, createdAt, updatedAt, deleted) VALUES (?, ?, ?, ?, 0)",
                    arrayOf(DEFAULT_LIST_ID, "Date Ideas", now, now)
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "twogether.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .addCallback(SEED_DEFAULT_LIST_CALLBACK)
                    // Safety net only for a FUTURE schema version we didn't write a real migration for -
                    // the 1->2 and 2->3 paths above are always handled for real, so existing users'
                    // sessions/moments/date-ideas/capsules/notes/milestones are never silently wiped by this.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
