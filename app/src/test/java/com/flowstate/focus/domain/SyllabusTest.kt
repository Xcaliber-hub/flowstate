package com.flowstate.focus.domain

import com.flowstate.focus.ui.syllabus.SyllabusViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 1.8.0 syllabus tracker: the bundled asset, the parser and the pure progress rules. */
class SyllabusTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(date: LocalDate): Long = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun asset(): String {
        val candidates = listOf(File("src/main/assets/syllabus.json"), File("app/src/main/assets/syllabus.json"))
        return (candidates.firstOrNull { it.exists() } ?: error("syllabus.json not found")).readText()
    }

    // ---- Parser and bundled data ----

    @Test
    fun bundledAssetParsesWithTheMockupCounts() {
        val syl = SyllabusParser.parse(asset())
        assertEquals(listOf("jee", "neet", "iat"), syl.exams.map { it.id })
        val jee = syl.exam("jee")!!
        assertEquals("JEE Main", jee.chip)
        assertEquals(listOf("Physics" to 29, "Chemistry" to 21, "Mathematics" to 22), jee.subjects.map { it.name to it.chapters.size })
        assertEquals(72, jee.chapterCount)
        syl.exams.forEach { e ->
            e.subjects.forEach { s ->
                assertTrue("${e.id} ${s.name} has duplicates", s.chapters.map { SyllabusKeys.normalise(it) }.toSet().size == s.chapters.size)
                s.chapters.forEach { c -> assertTrue("blank chapter in ${e.id}", c.isNotBlank()) }
            }
        }
    }

    @Test
    fun sharedChaptersAreFoundInOtherExams() {
        val syl = SyllabusParser.parse(asset())
        val also = syl.alsoIn("jee", "Physics", "System of Particles and Rotational Motion").map { it.chip }
        assertEquals(listOf("NEET", "IAT"), also)
        // NEET has no Mathematics, so a maths chapter can only be shared with IAT.
        assertEquals(listOf("IAT"), syl.alsoIn("jee", "Mathematics", "Sets").map { it.chip })
    }

    @Test
    fun parserSkipsBlankAndEmptyEntries() {
        val json = """
            {"version":"t","exams":[
              {"id":"x","chip":"X","subjects":[
                {"name":"Physics","chapters":["  Waves ","", "waves", "Optics"]},
                {"name":"Empty","chapters":[]},
                {"name":" ","chapters":["A"]}
              ]},
              {"id":"","subjects":[{"name":"P","chapters":["A"]}]},
              {"id":"y","subjects":[]}
            ]}
        """.trimIndent()
        val syl = SyllabusParser.parse(json)
        assertEquals(listOf("x"), syl.exams.map { it.id })
        val x = syl.exams.single()
        assertEquals(listOf("Physics"), x.subjects.map { it.name })
        assertEquals(listOf("Waves", "Optics"), x.subjects.single().chapters)
        assertEquals(listOf("x"), x.match)
        assertEquals("X", x.title)
    }

    @Test
    fun keysNormaliseNamesSoSharedChaptersAreOneRow() {
        assertEquals("work energy and power", SyllabusKeys.normalise("Work, Energy & Power"))
        assertEquals(SyllabusKeys.chapterKey("Physics", "Work, Energy and Power"), SyllabusKeys.chapterKey("Physics", "work energy & power "))
        // Same name in another subject is a different chapter.
        assertFalse(SyllabusKeys.chapterKey("Physics", "Thermodynamics") == SyllabusKeys.chapterKey("Chemistry", "Thermodynamics"))
    }

    @Test
    fun examNamesMatchTheirSyllabus() {
        val syl = SyllabusParser.parse(asset())
        val jee = syl.exam("jee")!!
        val iat = syl.exam("iat")!!
        assertTrue(jee.matches("JEE Main 2027"))
        assertTrue(jee.matches("jee mains test"))
        assertFalse(jee.matches("NEET UG"))
        assertTrue(iat.matches("IISER Aptitude Test"))
    }

    @Test
    fun matchExamsPrefersPinnedThenNearestUpcoming() {
        val syl = SyllabusParser.parse(asset())
        val today = LocalDate.of(2026, 11, 10)
        val past = Exam(1, "JEE Main mock", today.minusDays(3))
        val far = Exam(2, "JEE Main 2027 S2", LocalDate.of(2027, 4, 2))
        val near = Exam(3, "JEE Main 2027", LocalDate.of(2027, 1, 20))
        assertEquals(3L, SyllabusViewModel.matchExams(syl, listOf(past, far, near), today)["jee"]?.id)
        assertEquals(2L, SyllabusViewModel.matchExams(syl, listOf(past, far.copy(pinned = true), near), today)["jee"]?.id)
        assertNull(SyllabusViewModel.matchExams(syl, listOf(past), today)["jee"])
    }

    // ---- Tick and counter rules ----

    @Test
    fun toggleTicksWithTimestampAndUnticks() {
        val p0 = ChapterProgress.empty("k")
        val p1 = ChapterEdits.toggle(p0, SyllabusStep.DONE, 100L)
        assertEquals(100L, p1.ticks[SyllabusStep.DONE])
        assertEquals(1, p1.doneSteps)
        assertEquals(SyllabusStep.R1, p1.nextStep)
        val p2 = ChapterEdits.toggle(p1, SyllabusStep.DONE, 200L)
        assertTrue(p2.ticks.isEmpty())
        assertEquals(ChapterProgress.DEFAULT_PYQ_TARGET, p2.pyqTarget)
        assertEquals(6, ChapterProgress.DEFAULT_PYQ_TARGET)
    }

    @Test
    fun mockTickAndCounterAgree() {
        val on = ChapterEdits.toggle(ChapterProgress.empty("k"), SyllabusStep.MOCK, 1L)
        assertEquals(1, on.mocks)
        assertEquals(0, ChapterEdits.toggle(on, SyllabusStep.MOCK, 2L).mocks)
        val three = ChapterEdits.setMocks(ChapterProgress.empty("k"), 3, 5L)
        assertTrue(three.isDone(SyllabusStep.MOCK))
        assertEquals(5L, three.ticks[SyllabusStep.MOCK])
        assertEquals(5L, ChapterEdits.setMocks(three, 4, 9L).ticks[SyllabusStep.MOCK])
        assertFalse(ChapterEdits.setMocks(three, 0, 9L).isDone(SyllabusStep.MOCK))
        assertEquals(0, ChapterEdits.setMocks(three, -4, 9L).mocks)
        assertEquals(ChapterEdits.MAX_COUNT, ChapterEdits.setMocks(three, 1000, 9L).mocks)
    }

    @Test
    fun pyqCounterTicksAtTargetAndUnticksBelowIt() {
        var p = ChapterProgress.empty("k")
        for (i in 1..5) p = ChapterEdits.setPyqSets(p, i, i.toLong())
        assertFalse(p.isDone(SyllabusStep.PYQ))
        p = ChapterEdits.setPyqSets(p, 6, 60L)
        assertEquals(60L, p.ticks[SyllabusStep.PYQ])
        p = ChapterEdits.setPyqSets(p, 7, 70L)
        assertEquals(60L, p.ticks[SyllabusStep.PYQ])
        p = ChapterEdits.setPyqSets(p, 5, 80L)
        assertFalse(p.isDone(SyllabusStep.PYQ))
    }

    @Test
    fun manualPyqTickSurvivesCounterBelowTarget() {
        val ticked = ChapterEdits.toggle(ChapterProgress.empty("k"), SyllabusStep.PYQ, 1L)
        val counted = ChapterEdits.setPyqSets(ticked, 2, 2L)
        assertTrue(counted.isDone(SyllabusStep.PYQ))
        assertEquals(2, counted.pyqSets)
    }

    @Test
    fun loweringTheTargetTicksPyq() {
        val p = ChapterEdits.setPyqSets(ChapterProgress.empty("k"), 3, 1L)
        assertFalse(p.isDone(SyllabusStep.PYQ))
        val lowered = ChapterEdits.setPyqTarget(p, 3, 9L)
        assertEquals(3, lowered.pyqTarget)
        assertEquals(9L, lowered.ticks[SyllabusStep.PYQ])
        assertEquals(1, ChapterEdits.setPyqTarget(p, 0, 9L).pyqTarget)
    }

    // ---- Revision suggestions ----

    @Test
    fun revisionsAreSpacedThreeSevenFourteenThirtyDays() {
        val done = LocalDate.of(2026, 10, 24)
        var p = ChapterEdits.toggle(ChapterProgress.empty("k"), SyllabusStep.DONE, at(done))
        assertEquals(LocalDate.of(2026, 10, 27), RevisionPlan.suggestedBy(p, SyllabusStep.R1, null, zone))
        assertNull(RevisionPlan.suggestedBy(p, SyllabusStep.R2, null, zone))
        p = ChapterEdits.toggle(p, SyllabusStep.R1, at(LocalDate.of(2026, 11, 8)))
        // Mockup: Rev 1 done 8 Nov -> Rev 2 suggested by Sun, 15 Nov.
        assertEquals(LocalDate.of(2026, 11, 15), RevisionPlan.suggestedBy(p, SyllabusStep.R2, null, zone))
        p = ChapterEdits.toggle(p, SyllabusStep.R2, at(LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2026, 11, 29), RevisionPlan.suggestedBy(p, SyllabusStep.R3, null, zone))
        p = ChapterEdits.toggle(p, SyllabusStep.R3, at(LocalDate.of(2026, 11, 29)))
        assertEquals(LocalDate.of(2026, 12, 29), RevisionPlan.suggestedBy(p, SyllabusStep.R4, null, zone))
        assertNull(RevisionPlan.suggestedBy(p, SyllabusStep.PYQ, null, zone))
    }

    @Test
    fun lastRevisionIsCappedTheDayBeforeTheExam() {
        var p = ChapterProgress.empty("k")
        p = ChapterEdits.toggle(p, SyllabusStep.DONE, at(LocalDate.of(2027, 1, 1)))
        p = ChapterEdits.toggle(p, SyllabusStep.R1, at(LocalDate.of(2027, 1, 2)))
        p = ChapterEdits.toggle(p, SyllabusStep.R2, at(LocalDate.of(2027, 1, 3)))
        p = ChapterEdits.toggle(p, SyllabusStep.R3, at(LocalDate.of(2027, 1, 4)))
        assertEquals(LocalDate.of(2027, 1, 19), RevisionPlan.suggestedBy(p, SyllabusStep.R4, LocalDate.of(2027, 1, 20), zone))
        // Exam already before R3's day: never suggest a date before the step it follows.
        assertEquals(LocalDate.of(2027, 1, 4), RevisionPlan.suggestedBy(p, SyllabusStep.R4, LocalDate.of(2027, 1, 3), zone))
    }

    // ---- Stats and "Up next" ----

    @Test
    fun statsAverageStepsAcrossChapters() {
        val full = SyllabusStep.entries.fold(ChapterProgress.empty("a")) { p, s -> ChapterEdits.toggle(p, s, 1L) }
        val two = ChapterEdits.toggle(ChapterEdits.toggle(ChapterProgress.empty("b"), SyllabusStep.DONE, 1L), SyllabusStep.R1, 2L)
        val none = ChapterProgress.empty("c")
        val s = SyllabusMath.stats(listOf(full, two, none))
        assertEquals(3, s.chapters)
        assertEquals(2, s.done)
        assertEquals(9f / 21f, s.fraction, 1e-6f)
        assertEquals(5, s.revisions)
        assertEquals(1, s.inProgress)
        assertEquals(1, s.notStarted)
        assertEquals(2f / 3f, s.stepShare[0], 1e-6f)
        assertEquals(1f / 3f, s.stepShare[SyllabusStep.MOCK.ordinal], 1e-6f)
        assertEquals(43, SyllabusMath.percent(s.fraction))
        assertEquals(0, SyllabusMath.stats(emptyList<ChapterProgress>()).chapters)
    }

    @Test
    fun upNextPicksDueRevisionThenFirstUnstarted() {
        val chapters = listOf("A", "B", "C")
        val progress = mutableMapOf<String, ChapterProgress>()
        val of: (String) -> ChapterProgress = { progress[it] ?: ChapterProgress.empty(it) }
        assertEquals(UpNext("A", SyllabusStep.DONE), SyllabusMath.upNext(chapters, of, null, zone))

        progress["B"] = ChapterEdits.toggle(ChapterProgress.empty("B"), SyllabusStep.DONE, at(LocalDate.of(2026, 11, 1)))
        progress["C"] = ChapterEdits.toggle(ChapterProgress.empty("C"), SyllabusStep.DONE, at(LocalDate.of(2026, 10, 1)))
        // C's Rev 1 is due first.
        assertEquals(UpNext("C", SyllabusStep.R1), SyllabusMath.upNext(chapters, of, null, zone))

        val all = SyllabusStep.entries.fold(ChapterProgress.empty("x")) { p, s -> ChapterEdits.toggle(p, s, 1L) }
        chapters.forEach { progress[it] = all }
        assertNull(SyllabusMath.upNext(chapters, of, null, zone))

        // Only PYQs left: not a revision, so the first unfinished step is offered.
        progress["B"] = ChapterEdits.toggle(all, SyllabusStep.PYQ, 2L)
        assertNotNull(SyllabusMath.upNext(chapters, of, null, zone))
        assertEquals(UpNext("B", SyllabusStep.PYQ), SyllabusMath.upNext(chapters, of, null, zone))
    }
}
