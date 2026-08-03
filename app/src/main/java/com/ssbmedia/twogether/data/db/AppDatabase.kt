package com.ssbmedia.twogether.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TogetherSession::class, DateIdea::class, TimeCapsule::class, Moment::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): TogetherSessionDao
    abstract fun dateIdeaDao(): DateIdeaDao
    abstract fun timeCapsuleDao(): TimeCapsuleDao
    abstract fun momentDao(): MomentDao

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

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "twogether.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // Safety net only for a FUTURE schema version we didn't write a real migration for -
                    // the 1->2 path above is always handled for real by MIGRATION_1_2, so existing users'
                    // sessions/moments/date-ideas/capsules are never silently wiped by this.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
