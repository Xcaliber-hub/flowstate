package com.flowstate.focus.domain

/**
 * 1.8.4: the subject picker on Focus. Four fixed subjects, plus the user's own ("+ Custom"), of which
 * the last few are remembered in settings so they come back as chips. Pure logic, no Android.
 */
object SubjectChoices {
    val Presets = listOf("Physics", "Chemistry", "Maths", "Biology")

    /** Custom subjects kept as chips (the current one included). */
    const val MAX_RECENT = 3

    /** The preset [subject] stands for, ignoring case and surrounding spaces; null for anything else. */
    fun presetFor(subject: String): String? {
        val s = subject.trim()
        return Presets.firstOrNull { it.equals(s, ignoreCase = true) }
    }

    fun isCustom(subject: String): Boolean = subject.isNotBlank() && presetFor(subject) == null

    /** One line, trimmed: what a custom subject is stored and shown as. */
    fun clean(subject: String): String = subject.replace(Regex("\\s+"), " ").trim()

    /**
     * The custom chips shown between the presets and "+ Custom": the remembered subjects in order,
     * with the current one (if custom and not remembered yet) first. At most [MAX_RECENT].
     */
    fun customChips(current: String, recents: List<String>): List<String> {
        val out = mutableListOf<String>()
        val cur = clean(current)
        val known = recents.map(::clean).filter { isCustom(it) }
        if (isCustom(cur) && known.none { it.equals(cur, ignoreCase = true) }) out += cur
        for (r in known) {
            if (out.size >= MAX_RECENT) break
            if (out.none { it.equals(r, ignoreCase = true) }) out += r
        }
        return out
    }

    /** [recents] with [subject] moved to the front, if it is a custom subject. Presets and blanks are not kept. */
    fun remember(recents: List<String>, subject: String): List<String> {
        val s = clean(subject)
        if (!isCustom(s)) return recents
        return (listOf(s) + recents.filterNot { it.equals(s, ignoreCase = true) }).take(MAX_RECENT)
    }
}
