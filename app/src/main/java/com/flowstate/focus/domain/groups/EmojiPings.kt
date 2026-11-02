package com.flowstate.focus.domain.groups

/**
 * The emojis a member can send a group friend. Keep this list identical to `validEmoji` in
 * firebase/firestore.rules: the rules reject anything else.
 */
object EmojiPings {
    val ALL = listOf("🔥", "💪", "👏", "📚", "🎯", "☕", "👀", "😴")

    /** A short line under the emoji when it arrives. */
    fun meaning(emoji: String): String = when (emoji) {
        "🔥" -> "You're on fire, keep going"
        "💪" -> "You've got this"
        "👏" -> "Nice work today"
        "📚" -> "Time to study"
        "🎯" -> "Stay on target"
        "☕" -> "Take a break"
        "👀" -> "I see you slacking"
        "😴" -> "Go to sleep"
        else -> ""
    }
}
