package com.flowstate.focus.domain

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * 1.8.0 syllabus tracker: the bundled chapter lists (assets/syllabus.json) and the pure progress maths.
 * Everything here is free of Android so it is unit tested on the JVM.
 */

@Immutable
data class Syllabus(val version: String, val exams: List<SyllabusExam>) {
    fun exam(id: String): SyllabusExam? = exams.firstOrNull { it.id == id }

    /** Other exams that list the same chapter (same subject, same normalised name), e.g. "NEET · IAT". */
    fun alsoIn(examId: String, subject: String, chapter: String): List<SyllabusExam> {
        val key = SyllabusKeys.chapterKey(subject, chapter)
        return exams.filter { e ->
            e.id != examId && e.subjects.any { s -> s.name == subject && s.chapters.any { SyllabusKeys.chapterKey(s.name, it) == key } }
        }
    }
}

@Immutable
data class SyllabusExam(
    val id: String,
    /** Exam chip: "JEE Main", "NEET", "IAT". */
    val chip: String,
    /** Overall card label: "JEE Main 2027". */
    val title: String,
    /** "NTA syllabus, Oct 2026". */
    val source: String,
    val sourceUrl: String,
    /** Lower-case words that identify this exam in the user's Exams list ("jee"). */
    val match: List<String>,
    val subjects: List<SyllabusSubject>,
) {
    fun subject(name: String): SyllabusSubject? = subjects.firstOrNull { it.name == name }
    val chapterCount: Int get() = subjects.sumOf { it.chapters.size }

    /** True when a user exam called [name] is this one ("JEE Main 2027" for jee). */
    fun matches(name: String): Boolean {
        val words = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return match.any { m -> words.any { it == m || it.startsWith(m) } }
    }
}

@Immutable
data class SyllabusSubject(val name: String, val chapters: List<String>)

object SyllabusParser {
    /** Parses the bundled asset. Blank names are dropped; an exam or subject without chapters is skipped. */
    fun parse(json: String): Syllabus {
        val root = JSONObject(json)
        val exams = root.getJSONArray("exams")
        val list = (0 until exams.length()).mapNotNull { i ->
            val e = exams.getJSONObject(i)
            val subjectsJson = e.getJSONArray("subjects")
            val subjects = (0 until subjectsJson.length()).mapNotNull { j ->
                val s = subjectsJson.getJSONObject(j)
                val ch = s.getJSONArray("chapters")
                val chapters = (0 until ch.length()).map { ch.getString(it).trim() }.filter { it.isNotEmpty() }.distinctBy { SyllabusKeys.normalise(it) }
                val name = s.optString("name").trim()
                if (name.isEmpty() || chapters.isEmpty()) null else SyllabusSubject(name, chapters)
            }
            val id = e.optString("id").trim()
            val matchJson = e.optJSONArray("match")
            val match = if (matchJson == null) listOf(id) else (0 until matchJson.length()).map { matchJson.getString(it).lowercase() }
            if (id.isEmpty() || subjects.isEmpty()) null
            else SyllabusExam(
                id = id,
                chip = e.optString("chip").ifBlank { id.uppercase() },
                title = e.optString("title").ifBlank { e.optString("chip") },
                source = e.optString("source"),
                sourceUrl = e.optString("sourceUrl"),
                match = match,
                subjects = subjects,
            )
        }
        return Syllabus(root.optString("version"), list)
    }
}

object SyllabusKeys {
    /** "Work, Energy & Power" -> "work energy and power". */
    fun normalise(s: String): String =
        s.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex(" +"), " ")

    /**
     * Progress is stored per (subject, chapter name), not per exam, so a chapter shared by JEE, NEET and
     * IAT is ticked once and counts in all of them.
     */
    fun chapterKey(subject: String, chapter: String): String = normalise(subject) + "|" + normalise(chapter)
}

/** The seven steps of a chapter, in order. */
enum class SyllabusStep(val short: String, val full: String, val upNext: String) {
    DONE("Done", "Chapter done", "Start"),
    R1("R1", "Rev 1", "Rev 1"),
    R2("R2", "Rev 2", "Rev 2"),
    R3("R3", "Rev 3", "Rev 3"),
    R4("R4", "Rev 4", "Rev 4"),
    PYQ("PYQ", "PYQs", "PYQs"),
    MOCK("Mock", "Mocks", "Mock");

    val isRevision: Boolean get() = this in REVISIONS

    companion object {
        val REVISIONS = listOf(R1, R2, R3, R4)
        const val COUNT = 7
    }
}

/** One chapter's saved progress: when each step was ticked (epoch ms), plus the counters and note. */
@Immutable
data class ChapterProgress(
    val key: String,
    val ticks: Map<SyllabusStep, Long> = emptyMap(),
    val pyqSets: Int = 0,
    val pyqTarget: Int = DEFAULT_PYQ_TARGET,
    val mocks: Int = 0,
    val note: String = "",
) {
    fun isDone(step: SyllabusStep): Boolean = step in ticks
    val doneSteps: Int get() = ticks.size
    /** Completed steps / 7. */
    val fraction: Float get() = ticks.size / SyllabusStep.COUNT.toFloat()
    val nextStep: SyllabusStep? get() = SyllabusStep.entries.firstOrNull { it !in ticks }
    val started: Boolean get() = ticks.isNotEmpty()
    val complete: Boolean get() = ticks.size == SyllabusStep.COUNT
    val revisions: Int get() = SyllabusStep.REVISIONS.count { it in ticks }

    companion object {
        const val DEFAULT_PYQ_TARGET = 6
        fun empty(key: String) = ChapterProgress(key)
    }
}

/** Pure edits of a chapter's progress, so the tick and counter rules are tested in one place. */
object ChapterEdits {
    fun toggle(p: ChapterProgress, step: SyllabusStep, now: Long): ChapterProgress {
        val on = step !in p.ticks
        val ticks = if (on) p.ticks + (step to now) else p.ticks - step
        return when (step) {
            // Ticking Mock by hand logs one test; unticking clears the count so the counter agrees.
            SyllabusStep.MOCK -> p.copy(ticks = ticks, mocks = if (on) maxOf(1, p.mocks) else 0)
            else -> p.copy(ticks = ticks)
        }
    }

    /** PYQ sets counter; the PYQ step ticks itself when the count reaches the target and unticks below it. */
    fun setPyqSets(p: ChapterProgress, sets: Int, now: Long): ChapterProgress {
        val n = sets.coerceIn(0, MAX_COUNT)
        val reached = n >= p.pyqTarget
        val ticks = when {
            reached && SyllabusStep.PYQ !in p.ticks -> p.ticks + (SyllabusStep.PYQ to now)
            !reached && p.pyqSets >= p.pyqTarget && SyllabusStep.PYQ in p.ticks -> p.ticks - SyllabusStep.PYQ
            else -> p.ticks
        }
        return p.copy(pyqSets = n, ticks = ticks)
    }

    fun setPyqTarget(p: ChapterProgress, target: Int, now: Long): ChapterProgress {
        val t = target.coerceIn(1, MAX_COUNT)
        val withTarget = p.copy(pyqTarget = t)
        return if (p.pyqSets >= t && SyllabusStep.PYQ !in p.ticks) withTarget.copy(ticks = p.ticks + (SyllabusStep.PYQ to now)) else withTarget
    }

    /** Mocks counter; the Mock step is ticked while at least one test is logged. */
    fun setMocks(p: ChapterProgress, mocks: Int, now: Long): ChapterProgress {
        val n = mocks.coerceIn(0, MAX_COUNT)
        val ticks = when {
            n >= 1 && SyllabusStep.MOCK !in p.ticks -> p.ticks + (SyllabusStep.MOCK to now)
            n == 0 -> p.ticks - SyllabusStep.MOCK
            else -> p.ticks
        }
        return p.copy(mocks = n, ticks = ticks)
    }

    const val MAX_COUNT = 99
}

object RevisionPlan {
    private val gaps = mapOf(SyllabusStep.R1 to 3L, SyllabusStep.R2 to 7L, SyllabusStep.R3 to 14L, SyllabusStep.R4 to 30L)
    private val previous = mapOf(SyllabusStep.R1 to SyllabusStep.DONE, SyllabusStep.R2 to SyllabusStep.R1, SyllabusStep.R3 to SyllabusStep.R2, SyllabusStep.R4 to SyllabusStep.R3)

    /**
     * When a revision is due: R1 3 days after the chapter is done, R2 7 days after R1, R3 14 after R2,
     * R4 30 after R3 but no later than the day before the exam. Null until the step before it is ticked,
     * and for non-revision steps.
     */
    fun suggestedBy(p: ChapterProgress, step: SyllabusStep, examDate: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val gap = gaps[step] ?: return null
        val prevAt = p.ticks[previous.getValue(step)] ?: return null
        var date = Instant.ofEpochMilli(prevAt).atZone(zone).toLocalDate().plusDays(gap)
        if (step == SyllabusStep.R4 && examDate != null) {
            val cap = examDate.minusDays(1)
            val prevDay = Instant.ofEpochMilli(prevAt).atZone(zone).toLocalDate()
            if (cap.isBefore(date)) date = if (cap.isBefore(prevDay)) prevDay else cap
        }
        return date
    }

    fun previousOf(step: SyllabusStep): SyllabusStep? = previous[step]
}

/** Aggregates over one subject (or a whole exam) for the cards. */
@Immutable
data class SyllabusStats(
    val chapters: Int,
    /** Chapters with "Chapter done" ticked. */
    val done: Int,
    /** Mean chapter fraction (completed steps / 7). */
    val fraction: Float,
    val revisions: Int,
    val pyqSets: Int,
    /** Share of chapters that reached each step, in [SyllabusStep] order. */
    val stepShare: List<Float>,
    val inProgress: Int,
    val notStarted: Int,
)

data class UpNext(val chapter: String, val step: SyllabusStep)

object SyllabusMath {
    fun stats(chapters: List<String>, progressOf: (String) -> ChapterProgress): SyllabusStats = stats(chapters.map(progressOf))

    fun stats(ps: List<ChapterProgress>): SyllabusStats {
        if (ps.isEmpty()) return SyllabusStats(0, 0, 0f, 0, 0, List(SyllabusStep.COUNT) { 0f }, 0, 0)
        return SyllabusStats(
            chapters = ps.size,
            done = ps.count { it.isDone(SyllabusStep.DONE) },
            fraction = ps.sumOf { it.doneSteps } / (ps.size * SyllabusStep.COUNT).toFloat(),
            revisions = ps.sumOf { it.revisions },
            pyqSets = ps.sumOf { it.pyqSets },
            stepShare = SyllabusStep.entries.map { s -> ps.count { it.isDone(s) } / ps.size.toFloat() },
            inProgress = ps.count { it.started && !it.complete },
            notStarted = ps.count { !it.started },
        )
    }

    /**
     * What to do next in a subject: the revision that is due soonest among started chapters; otherwise the
     * first chapter not started yet ("Start"); otherwise the first unfinished step anywhere. Null when all done.
     */
    fun upNext(chapters: List<String>, progressOf: (String) -> ChapterProgress, examDate: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): UpNext? {
        val due = chapters.mapNotNull { c ->
            val p = progressOf(c)
            val n = p.nextStep ?: return@mapNotNull null
            RevisionPlan.suggestedBy(p, n, examDate, zone)?.let { Triple(c, n, it) }
        }.minByOrNull { it.third.toEpochDay() }
        if (due != null) return UpNext(due.first, due.second)
        chapters.firstOrNull { !progressOf(it).started }?.let { return UpNext(it, SyllabusStep.DONE) }
        chapters.firstNotNullOfOrNull { c -> progressOf(c).nextStep?.let { UpNext(c, it) } }?.let { return it }
        return null
    }

    fun percent(f: Float): Int = Math.round(f * 100f)
}
