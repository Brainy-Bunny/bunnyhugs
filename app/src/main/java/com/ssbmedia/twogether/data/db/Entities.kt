package com.ssbmedia.twogether.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "together_sessions")
data class TogetherSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** True for sessions backfilled by hand via "Add past time together" rather than detected over BLE. */
    val isManual: Boolean = false
)

@Entity(tableName = "date_ideas")
data class DateIdea(
    @PrimaryKey val id: String,
    val text: String,
    val category: String? = null,
    val done: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "time_capsules")
data class TimeCapsule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val unlockAtHours: Float,
    val createdAt: Long,
    val unlockedAt: Long? = null
)

@Entity(tableName = "moments")
data class Moment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoUri: String,
    val takenAt: Long,
    val sessionId: Long? = null
)
