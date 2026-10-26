package com.flowstate.focus.domain.groups

/**
 * Weekly leagues by focus time this IST week. A member with less than Bronze has no league yet.
 * Thresholds are product choices, not data from the backend: every client computes the same league
 * from the synced weekly total, so nothing extra is stored in Firestore.
 */
enum class League(val label: String, val minMs: Long, val color: Long) {
    BRONZE("Bronze", 5 * HOUR_MS, 0xFFB87333),
    SILVER("Silver", 10 * HOUR_MS, 0xFFB0BEC5),
    GOLD("Gold", 20 * HOUR_MS, 0xFFFFC107),
    PLATINUM("Platinum", 30 * HOUR_MS, 0xFF80DEEA),
    DIAMOND("Diamond", 45 * HOUR_MS, 0xFF64B5F6);

    companion object {
        /** The highest league reached, or null below Bronze. */
        fun of(weeklyMs: Long): League? = League.entries.lastOrNull { weeklyMs >= it.minMs }
    }
}

private const val HOUR_MS = 60L * 60 * 1000
