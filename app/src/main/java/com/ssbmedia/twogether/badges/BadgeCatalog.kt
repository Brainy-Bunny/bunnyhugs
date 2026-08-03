package com.ssbmedia.twogether.badges

import com.ssbmedia.twogether.stats.TogetherStats

enum class BadgeType { HOURS, DAILY_STREAK, WEEKLY_STREAK, REUNIONS }

data class Badge(
    val id: String,
    val type: BadgeType,
    val threshold: Int,
    val title: String,
    val emoji: String
)

data class BadgeStatus(val badge: Badge, val unlocked: Boolean, val progressLabel: String)

object BadgeCatalog {
    val all: List<Badge> = buildList {
        listOf(1, 10, 50, 100, 500).forEach {
            add(Badge("hours_$it", BadgeType.HOURS, it, "$it Hours Together", "💛"))
        }
        listOf(7, 30, 100).forEach {
            add(Badge("daily_$it", BadgeType.DAILY_STREAK, it, "$it Day Streak", "🔥"))
        }
        listOf(4, 12, 52).forEach {
            add(Badge("weekly_$it", BadgeType.WEEKLY_STREAK, it, "$it Week Streak", "🌟"))
        }
        listOf(10, 50, 100).forEach {
            add(Badge("reunion_$it", BadgeType.REUNIONS, it, "$it Reunions", "🤗"))
        }
    }

    fun statuses(stats: TogetherStats): List<BadgeStatus> = all.map { badge ->
        val (unlocked, current) = when (badge.type) {
            BadgeType.HOURS -> (stats.totalHoursAllTime >= badge.threshold) to stats.totalHoursAllTime.toInt()
            BadgeType.DAILY_STREAK -> (stats.longestDailyStreak >= badge.threshold) to stats.longestDailyStreak
            BadgeType.WEEKLY_STREAK -> (stats.longestWeeklyStreak >= badge.threshold) to stats.longestWeeklyStreak
            BadgeType.REUNIONS -> (stats.reunionCount >= badge.threshold) to stats.reunionCount
        }
        val label = if (unlocked) "Unlocked" else "$current / ${badge.threshold}"
        BadgeStatus(badge, unlocked, label)
    }
}
