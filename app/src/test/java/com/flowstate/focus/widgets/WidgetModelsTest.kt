package com.flowstate.focus.widgets

import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.db.TaskEntity
import com.flowstate.focus.domain.Exam
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.Syllabus
import com.flowstate.focus.domain.SyllabusExam
import com.flowstate.focus.domain.SyllabusSubject
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 19) // a Thursday
    private fun at(date: LocalDate, hour: Int = 10) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun idleTimerShowsReady() {
        val m = TimerModels.map(TimerState(), at(today), null, zone, false, 25)
        assertTrue(m.idle)
        assertEquals(TimeDisplay.Fixed("Ready"), m.time)
        assertFalse(m.canPause || m.canStop || m.canSkip)
    }

    @Test fun runningPomodoroCountsDownWithRoundAndBreakTime() {
        val start = at(today, 17)
        val s = TimerState(mode = TimerMode.POMODORO, phase = Phase.FOCUS, status = Status.RUNNING, phaseDurationMs = 25 * 60_000L,
            phaseStartedAt = start, round = 1, plan = Plan(rounds = 4), subject = "Physics")
        val m = TimerModels.map(s, start + 5 * 60_000L, null, zone, false, 25)
        assertEquals("Round 2 of 4", m.roundLabel)
        assertEquals("2/4", m.roundShort)
        assertEquals(TimeDisplay.CountDown(start + 25 * 60_000L), m.time)
        assertEquals(0.2f, m.fraction, 0.001f)
        assertEquals("Break at 5:25 pm", m.nextLine.lowercase(Locale.ROOT))
        assertTrue(m.canPause && m.canStop && m.canSkip)
    }

    @Test fun strictFocusHidesPauseStopAndSkip() {
        val s = TimerState(phase = Phase.FOCUS, status = Status.RUNNING, phaseDurationMs = 60_000L, phaseStartedAt = 1L, strict = true)
        val m = TimerModels.map(s, 2L, null, zone, true, 25)
        assertFalse(m.canPause || m.canStop || m.canSkip)
    }

    @Test fun pausedIsStaticAndStopwatchCountsUp() {
        val paused = TimerState(phase = Phase.FOCUS, status = Status.PAUSED, phaseDurationMs = 10 * 60_000L, phaseStartedAt = 0L, pausedAt = 4 * 60_000L)
        assertEquals(TimeDisplay.Fixed("06:00"), TimerModels.map(paused, 9 * 60_000L, null, zone, true, 25).time)
        val sw = TimerState(mode = TimerMode.STOPWATCH, phase = Phase.FOCUS, status = Status.RUNNING, phaseStartedAt = 1000L)
        val m = TimerModels.map(sw, 5000L, null, zone, true, 25)
        assertEquals(TimeDisplay.CountUp(1000L), m.time)
        assertFalse(m.canSkip)
    }

    @Test fun breakRoundBelongsToTheRoundJustFinished() {
        val s = TimerState(phase = Phase.SHORT_BREAK, status = Status.RUNNING, phaseDurationMs = 5 * 60_000L, phaseStartedAt = 0L, round = 1)
        val m = TimerModels.map(s, 0L, null, zone, true, 25)
        assertEquals("SHORT BREAK", m.status)
        assertEquals("1/4", m.roundShort)
        assertTrue(m.isBreak && m.canSkip)
    }

    @Test fun remoteLockIsReported() {
        val m = TimerModels.map(TimerState(), 0L, "PC", zone, true, 25)
        assertTrue(m.remoteLocked)
    }

    @Test fun clockText() {
        assertEquals("16:12", TimerModels.clockText(16 * 60_000L + 12_000L))
        assertEquals("1:00:00", TimerModels.clockText(3_600_000L))
    }

    private fun session(date: LocalDate, minutes: Long) = SessionEntity(startedAt = at(date), durationMs = minutes * 60_000L, mode = "POMODORO", subject = "", completed = true)

    @Test fun dailyWeekStartsMondayWithTodayIndex() {
        val rows = listOf(session(today, 30), session(today, 30), session(today.minusDays(1), 20), session(today.minusDays(7), 50))
        val m = StatsModels.daily(rows, 120 * 60_000L, today, zone)
        assertEquals(60 * 60_000L, m.focusedMs)
        assertEquals(50, m.percent)
        assertEquals(2, m.sessions)
        assertEquals(3, m.todayIndex)
        assertEquals(listOf(0L, 0L, 20 * 60_000L, 60 * 60_000L, 0L, 0L, 0L), m.week)
        assertEquals(2, m.streak)
    }

    @Test fun heatmapAndBestStreak() {
        val perDay = mapOf(
            today to 120 * 60_000L, today.minusDays(1) to 50 * 60_000L,
            today.minusDays(10) to 10 * 60_000L, today.minusDays(11) to 10 * 60_000L, today.minusDays(12) to 10 * 60_000L,
        )
        val m = StatsModels.streak(perDay, 120 * 60_000L, today)
        assertEquals(28, m.levels.size)
        assertEquals(4, m.levels.last())
        assertEquals(2, m.levels[26])
        assertEquals(2, m.current)
        assertEquals(3, m.best)
        assertEquals(0, StatsModels.heatLevel(60_000L, 0L))
        assertEquals(3, StatsModels.heatLevel(30 * 60_000L, 0L))
    }

    private fun task(id: Long, done: Boolean, due: LocalDate?) = TaskEntity(id = id, title = "T$id", category = "Study", priority = 1, deadline = due?.let { at(it) }, done = done, createdAt = id)

    @Test fun todayTasksPicksDueTodayOverdueAndUndated() {
        val all = listOf(
            task(1, true, today), task(2, false, null), task(3, false, today.minusDays(2)),
            task(4, false, today.plusDays(3)), task(5, true, null), task(6, false, today), task(7, false, null),
        )
        val m = TaskModels.today(all, today, zone)
        assertEquals(5, m.total)
        assertEquals(1, m.done)
        assertEquals(listOf(1L, 2L, 3L, 6L), m.items.map { it.id })
    }

    private val jee = SyllabusExam("jee", "JEE Main", "JEE Main 2027", "", "", listOf("jee"),
        listOf(SyllabusSubject("Physics", listOf("Kinematics", "Optics")), SyllabusSubject("Chemistry", listOf("Atoms")), SyllabusSubject("Mathematics", listOf("Limits"))))

    @Test fun examCountdownMatchesTheSyllabus() {
        val exams = listOf(Exam(id = 1, name = "JEE Main 2027", date = today.plusDays(68)))
        val progress = mapOf(com.flowstate.focus.domain.SyllabusKeys.chapterKey("Physics", "Optics") to
            com.flowstate.focus.domain.ChapterProgress("k", mapOf(com.flowstate.focus.domain.SyllabusStep.DONE to 1L)))
        val m = SyllabusModels.exam(exams, today, Syllabus("1", listOf(jee)), progress)!!
        assertEquals(68L, m.daysLeft)
        assertEquals(1, m.chaptersDone)
        assertEquals(4, m.chapters)
        assertEquals(25, m.syllabusPercent)
        assertNull(SyllabusModels.exam(emptyList(), today, Syllabus("1", listOf(jee)), emptyMap()))
        val other = SyllabusModels.exam(listOf(Exam(id = 2, name = "Driving test", date = today.plusDays(3))), today, Syllabus("1", listOf(jee)), emptyMap())!!
        assertNull(other.syllabusPercent)
    }

    @Test fun syllabusRingsUseShortNamesAndUpNext() {
        val m = SyllabusModels.rings(jee, emptyMap(), null, zone)
        assertEquals(listOf("Phy", "Chem", "Maths"), m.rings.map { it.label })
        assertEquals(listOf(0, 0, 0), m.rings.map { it.percent })
        assertEquals("Start Kinematics", m.upNext)
    }

    @Test fun memberBarIsAnHourLong() {
        assertEquals(0.5f, memberBarFraction(30), 0.001f)
        assertEquals(1f, memberBarFraction(200), 0.001f)
    }
}
