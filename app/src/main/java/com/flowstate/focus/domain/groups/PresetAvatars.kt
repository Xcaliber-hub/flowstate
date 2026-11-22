package com.flowstate.focus.domain.groups

/**
 * Approved, upload-free avatar choices shared by profile and group-member data (app and desktop).
 *
 * 1.9.0: the original 24 sticker avatars are gone. Two categories replace them: Orbs (glass spheres,
 * each holding a tiny world) and Stitched (embroidered patches). A stored id from the old set is no
 * longer valid, so [fallback] turns it into "" and that person shows their initial until they pick again.
 */
object PresetAvatars {
    data class Preset(val id: String, val label: String)
    data class Category(val name: String, val presets: List<Preset>)

    val orbs: List<Preset> = listOf(
        Preset("terrarium", "Terrarium"), Preset("lavalamp", "Lava Lamp"), Preset("aurora", "Aurora"),
        Preset("koi", "Koi Pond"), Preset("nebula", "Nebula"), Preset("chrome", "Chrome"),
        Preset("eclipse", "Eclipse"), Preset("snowglobe", "Snow Globe"), Preset("candle", "Candle"),
        Preset("curl", "Curl"), Preset("sakura", "Sakura"), Preset("desklamp", "Desk Lamp"),
        Preset("dune", "Dune"), Preset("rain", "Rain"), Preset("island", "Island"),
        Preset("jellyfish", "Jellyfish"), Preset("chai", "Chai"), Preset("saturn", "Saturn"),
        Preset("prism", "Prism"), Preset("lantern", "Lantern"), Preset("fireflies", "Fireflies"),
        Preset("storm", "Storm"), Preset("marble", "Marble"), Preset("lighthouse", "Lighthouse"),
    )

    val stitched: List<Preset> = listOf(
        Preset("chaiowl", "Chai Owl"), Preset("axolotl", "Axolotl"), Preset("capybara", "Capybara"),
        Preset("moth", "Moth"), Preset("booksnail", "Book Snail"), Preset("raccoon", "Raccoon"),
        Preset("mushfrog", "Mushroom Frog"), Preset("pomodoro", "Pomodoro"), Preset("gradduck", "Grad Duck"),
        Preset("crane", "Crane"), Preset("cassette", "Cassette"), Preset("bonsai", "Bonsai"),
        Preset("mooncat", "Moon Cat"), Preset("reading", "Reading"), Preset("kulhad", "Kulhad"),
        Preset("trail", "Trail"), Preset("compass", "Compass"), Preset("vinyl", "Vinyl"),
        Preset("paperplane", "Paper Plane"), Preset("pottedcactus", "Potted Cactus"), Preset("balloon", "Balloon"),
        Preset("flame", "Flame"), Preset("toadstools", "Toadstools"), Preset("sunrise", "Sunrise"),
    )

    val categories: List<Category> = listOf(Category("Orbs", orbs), Category("Stitched", stitched))

    val all: List<Preset> = orbs + stitched

    val ids: Set<String> = all.mapTo(linkedSetOf()) { it.id }

    /** Empty means no selected avatar (render initials); unknown stored values safely fall back to it. */
    fun isValid(id: String): Boolean = id.isEmpty() || id in ids

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id

    fun categoryOf(id: String): Int = categories.indexOfFirst { c -> c.presets.any { it.id == id } }.coerceAtLeast(0)

    fun fallback(id: String?): String = id?.takeIf { it in ids } ?: ""
}
