package com.flowstate.desktop.cloud

/**
 * Guards the Groups list against a single bad poll (1.8.0 desktop fix).
 *
 * A non-empty list is never replaced by an empty one on one poll: it takes [required] consecutive
 * successful polls that both came back empty, or a confirmed leave/delete ([allowNextEmpty]). A failed
 * poll changes nothing and breaks the streak. Not thread safe; the controller calls it under its groups mutex.
 */
class EmptyListGuard(private val required: Int = 2) {
    private var emptyStreak = 0
    private var allowEmpty = false

    /** The user left or deleted their (last) group: the next empty result is real. */
    fun allowNextEmpty() { allowEmpty = true }

    /** A poll failed: it proves nothing, so an empty streak starts over. */
    fun onFailure() { emptyStreak = 0 }

    /** New account or signed out: forget everything. */
    fun reset() { emptyStreak = 0; allowEmpty = false }

    /** The list to publish for a successful poll that returned [fresh], or null to keep [current]. */
    fun <T> accept(current: List<T>, fresh: List<T>): List<T>? {
        if (fresh.isNotEmpty() || current.isEmpty() || allowEmpty) {
            emptyStreak = 0
            allowEmpty = false // a permission to empty the list is for the next successful poll only
            return fresh
        }
        emptyStreak++
        if (emptyStreak < required) return null
        emptyStreak = 0
        return fresh
    }
}

/** Delay before the next poll after [failures] consecutive failures: 5 s, 10 s, 20 s … capped at 5 min. */
fun pollBackoffMs(failures: Int, baseMs: Long = 5_000L, capMs: Long = 300_000L): Long {
    if (failures <= 0) return 0L
    val shift = (failures - 1).coerceAtMost(20)
    return (baseMs shl shift).coerceAtMost(capMs)
}
