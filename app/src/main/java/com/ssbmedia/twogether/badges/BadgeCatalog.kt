package com.ssbmedia.twogether.badges

import com.ssbmedia.twogether.stats.TogetherStats

enum class BadgeType { HOURS, DAILY_STREAK, WEEKLY_STREAK, REUNIONS, PERFECT_WEEKS }

data class Badge(
    val id: String,
    val type: BadgeType,
    val threshold: Int,
    val title: String,
    val emoji: String
)

data class BadgeStatus(val badge: Badge, val unlocked: Boolean, val progressLabel: String)

object BadgeCatalog {

    /** The curated, hand-picked thresholds each badge type started with - the starting point
     * [extendedThresholds] grows past once the couple's progress catches up to the last one. */
    private val SEEDS: Map<BadgeType, List<Int>> = mapOf(
        BadgeType.HOURS to listOf(1, 10, 50, 100, 500),
        BadgeType.DAILY_STREAK to listOf(7, 30, 100),
        BadgeType.WEEKLY_STREAK to listOf(4, 12, 52),
        BadgeType.REUNIONS to listOf(10, 50, 100),
        BadgeType.PERFECT_WEEKS to listOf(1, 4, 12, 26)
    )

    /** Absolute ceiling for each type - the very last badge that will ever be generated, deliberately
     * an aspirational, essentially-once-in-a-lifetime number rather than something auto-generation would
     * ever need to grow past on its own: HOURS (100,000h, ~11 years of cumulative together-time),
     * DAILY_STREAK (10,000 days, ~27 years of never missing a day), WEEKLY_STREAK (1,000 weeks, ~19
     * years). REUNIONS' 5,000 is deliberately smaller in absolute terms but not in difficulty - a
     * reunion only happens on a genuine apart-then-back-together day (see StatsCalculator's reunion
     * rule), which naturally caps out far lower than raw elapsed hours/days ever could; 5,000 already
     * implies apart-and-reunited on most days for well over a decade. PERFECT_WEEKS has no cap (null) -
     * it's grid-only, never shown as one of the 4 headline progress bars, so there's no "final
     * celebration" UI moment it needs to build toward. Once a couple reaches a type's cap, no further
     * badge of that type is ever generated - see [extendedThresholds]/[isMaxed]. */
    private val CAPS: Map<BadgeType, Int?> = mapOf(
        BadgeType.HOURS to 100_000,
        BadgeType.DAILY_STREAK to 10_000,
        BadgeType.WEEKLY_STREAK to 1_000,
        BadgeType.REUNIONS to 5_000,
        BadgeType.PERFECT_WEEKS to null
    )

    /** Extends a type's curated [seed] list past its last entry, once the couple's [current] progress
     * catches up, until [aheadCount] not-yet-earned thresholds are visible beyond it - so the badge grid
     * always shows "what's next" instead of dead-ending at the last hand-picked milestone, without
     * growing unbounded as progress increases. Each new threshold is the next "nice" round number (see
     * [nextNiceNumber]) rather than simply doubling the last one - doubling alone is mathematically fine
     * but stops producing genuinely clean numbers a few tiers past the seed (819200, 1638400, ...), which
     * matters here since these are meant to keep looking like real, recognizable milestones even for a
     * couple chasing something like 100,000 hours or a million days together.
     *
     * [cap], if non-null, is a hard ceiling: generation clamps the final entry to exactly [cap] (even if
     * the next nice number would overshoot it) and never produces anything beyond it, regardless of how
     * far [current] has caught up - see [CAPS]'s doc for why each type's cap is what it is. */
    private fun extendedThresholds(seed: List<Int>, current: Int, cap: Int?, aheadCount: Int = 2): List<Int> {
        val result = seed.toMutableList()
        var last = result.last()
        while (result.count { it > current } < aheadCount) {
            if (cap != null && last >= cap) break
            last = nextNiceNumber(last)
            if (cap != null && last >= cap) last = cap
            result.add(last)
        }
        return result
    }

    /** The next "nice" round number strictly greater than [after], from the classic {1, 2, 5} x 10^k
     * family (1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, ...) - the same technique
     * chart-axis tick generators use for exactly this reason: every value in this family reads as an
     * obviously round, memorable number at ANY magnitude, from single digits up through the millions,
     * unlike a plain doubling sequence which drifts into arbitrary-looking numbers within a few steps. */
    private fun nextNiceNumber(after: Int): Int {
        var pow10 = 1
        while (pow10 * 5 <= after) pow10 *= 10
        for (mult in intArrayOf(1, 2, 5, 10)) {
            val candidate = pow10 * mult
            if (candidate > after) return candidate
        }
        return pow10 * 10
    }

    private fun currentValueFor(type: BadgeType, stats: TogetherStats): Int = when (type) {
        BadgeType.HOURS -> stats.totalHoursAllTime.toInt()
        BadgeType.DAILY_STREAK -> stats.longestDailyStreak
        BadgeType.WEEKLY_STREAK -> stats.longestWeeklyStreak
        BadgeType.REUNIONS -> stats.reunionCount
        BadgeType.PERFECT_WEEKS -> stats.perfectWeekCount
    }

    /** The same id/title/emoji template each type's original seed badges used - applied across whatever
     * threshold [extendedThresholds] generated, not just the original curated list. The type's own [CAPS]
     * value, if reached, gets a distinct celebratory title/emoji instead of the plain numbered template -
     * it's the very last badge that type will ever offer, so it's meant to read as a genuine "you two
     * are amazing" moment rather than just another number in the sequence. */
    private fun badgeFor(type: BadgeType, threshold: Int): Badge {
        // CAPS[PERFECT_WEEKS] is null, and threshold (a non-null Int) can never equal null, so this
        // branch is naturally unreachable for PERFECT_WEEKS - no separate guard needed for it.
        if (threshold == CAPS[type]) {
            return when (type) {
                BadgeType.HOURS -> Badge("hours_$threshold", type, threshold, "100,000 Hours — Soulmates Forever", "💍")
                BadgeType.DAILY_STREAK -> Badge("daily_$threshold", type, threshold, "10,000 Days — A Lifetime Together", "🏆")
                BadgeType.WEEKLY_STREAK -> Badge("weekly_$threshold", type, threshold, "1,000 Weeks — Unbreakable", "👑")
                BadgeType.REUNIONS -> Badge("reunion_$threshold", type, threshold, "5,000 Reunions — Never Apart for Long", "💞")
                BadgeType.PERFECT_WEEKS -> error("PERFECT_WEEKS has no cap (CAPS[PERFECT_WEEKS] == null) - unreachable")
            }
        }
        return when (type) {
            // BUG fix: HOURS' smallest seed is 1, so "$threshold Hours Together" was reachable at
            // threshold=1 ("1 Hours Together") - PERFECT_WEEKS below already handled this correctly for
            // its own singular case, this one just hadn't been.
            BadgeType.HOURS -> Badge("hours_$threshold", type, threshold, "$threshold Hour" + (if (threshold == 1) "" else "s") + " Together", "💛")
            BadgeType.DAILY_STREAK -> Badge("daily_$threshold", type, threshold, "$threshold Day Streak", "🔥")
            BadgeType.WEEKLY_STREAK -> Badge("weekly_$threshold", type, threshold, "$threshold Week Streak", "🌟")
            BadgeType.REUNIONS -> Badge("reunion_$threshold", type, threshold, "$threshold Reunions", "🤗")
            BadgeType.PERFECT_WEEKS -> Badge(
                "perfectweek_$threshold", type, threshold,
                "$threshold Perfect Week" + (if (threshold == 1) "" else "s"), "📆"
            )
        }
    }

    /** The special final badge for [type] (see [CAPS]/[badgeFor]'s cap special-case), or null if [type]
     * has no cap (PERFECT_WEEKS). Lets a caller like BadgesScreen's "maxed out" celebration reuse the
     * EXACT same title/emoji the badge grid itself shows for that badge, rather than a second hand-typed
     * copy that could silently drift from it if either one is ever edited alone. */
    fun maxedBadge(type: BadgeType): Badge? {
        val cap = CAPS[type] ?: return null
        return badgeFor(type, cap)
    }

    /** The full badge list for the grid, with each type's thresholds auto-extended (see
     * [extendedThresholds]) against the couple's CURRENT progress in that category - so, unlike the old
     * fixed list, this has to be recomputed against [stats] every time rather than cached as a static val. */
    fun badgesFor(stats: TogetherStats): List<Badge> =
        BadgeType.entries.flatMap { type ->
            extendedThresholds(SEEDS.getValue(type), currentValueFor(type, stats), CAPS[type]).map { badgeFor(type, it) }
        }

    fun statuses(stats: TogetherStats): List<BadgeStatus> = badgesFor(stats).map { badge ->
        val (unlocked, current) = when (badge.type) {
            BadgeType.HOURS -> (stats.totalHoursAllTime >= badge.threshold) to stats.totalHoursAllTime.toInt()
            BadgeType.DAILY_STREAK -> (stats.longestDailyStreak >= badge.threshold) to stats.longestDailyStreak
            BadgeType.WEEKLY_STREAK -> (stats.longestWeeklyStreak >= badge.threshold) to stats.longestWeeklyStreak
            BadgeType.REUNIONS -> (stats.reunionCount >= badge.threshold) to stats.reunionCount
            BadgeType.PERFECT_WEEKS -> (stats.perfectWeekCount >= badge.threshold) to stats.perfectWeekCount
        }
        val label = if (unlocked) "Unlocked" else "$current / ${badge.threshold}"
        BadgeStatus(badge, unlocked, label)
    }

    /** True once [current] has reached [type]'s absolute ceiling (see [CAPS]) - the couple has earned
     * every badge that type will ever offer. Always false for PERFECT_WEEKS (uncapped). The Badges
     * screen's progress bars check this FIRST, before [nextAndPrevThreshold], since there's no
     * meaningful "next badge" left to show a countdown toward once this is true - see BadgesScreen's
     * BadgeProgressBarsSection for the celebratory copy shown instead. */
    fun isMaxed(type: BadgeType, current: Int): Boolean {
        val cap = CAPS[type] ?: return false
        return current >= cap
    }

    /** For [type]'s auto-extended threshold list (see [extendedThresholds]) against the couple's
     * [current] progress: (previous threshold already passed, or 0 if none has been yet) to (next
     * not-yet-reached threshold). Powers the Badges screen's progress bars so their bar-fraction/countdown
     * math stays derived from the exact same threshold list the badge grid itself renders, rather than a
     * second hand-maintained copy that could drift from it.
     *
     * Callers MUST check [isMaxed] first - once [current] has reached the type's cap, EVERY generated
     * threshold is `<= current` (extendedThresholds never generates anything past the cap), so there is
     * no threshold left for `.first { it > current }` to find. Deliberately left as a hard failure
     * (not a silent fallback) if called anyway, so a future caller can't accidentally ship a broken bar
     * instead of the proper "maxed out" state. */
    fun nextAndPrevThreshold(type: BadgeType, current: Int): Pair<Int, Int> {
        require(!isMaxed(type, current)) { "nextAndPrevThreshold called for an already-maxed type $type - check isMaxed() first" }
        val thresholds = extendedThresholds(SEEDS.getValue(type), current, CAPS[type])
        val next = thresholds.first { it > current }
        val prev = thresholds.lastOrNull { it <= current } ?: 0
        return prev to next
    }
}
