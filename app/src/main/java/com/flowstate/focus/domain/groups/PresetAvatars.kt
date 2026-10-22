package com.flowstate.focus.domain.groups

/** Approved, upload-free avatar choices shared by profile and group-member data. */
object PresetAvatars {
    data class Preset(val id: String, val label: String)

    val all: List<Preset> = listOf(
        Preset("sprout", "Sprout"),
        Preset("sunbeam", "Sunbeam"),
        Preset("moon", "Moon"),
        Preset("fox", "Fox"),
        Preset("orbit", "Orbit"),
        Preset("summit", "Summit"),
        Preset("cloud", "Cloud"),
        Preset("ember", "Ember"),
        Preset("turtle", "Turtle"),
        Preset("jelly", "Jelly"),
        Preset("comet", "Comet"),
        Preset("bloom", "Bloom"),
    )

    val ids: Set<String> = all.mapTo(linkedSetOf()) { it.id }

    /** Empty means no selected avatar (render initials); unknown stored values safely fall back to it. */
    fun isValid(id: String): Boolean = id.isEmpty() || id in ids

    fun fallback(id: String?): String = id?.takeIf { it in ids } ?: ""
}
