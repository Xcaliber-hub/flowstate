package com.flowstate.focus.domain

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 1.7.0: an exam (or any dated goal) the Focus screen counts down to. Stored locally only. */
@Immutable
data class Exam(
    val id: Long = 0,
    val name: String,
    val date: LocalDate,
    val emoji: String = ExamEmojis.first(),
    /** Index into [ExamTags]. */
    val tag: Int = 2,
    val showOnFocus: Boolean = true,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Badge emoji offered when adding an exam. */
val ExamEmojis = listOf("🎯", "⚛️", "🧪", "📐", "🧬", "🏔️", "📚", "✏️")

/** Tag colours (ARGB) from the Slate palette, lightest first. */
val ExamTags = listOf(0xFFF2F4F4, 0xFFCCD0CF, 0xFF9BA8AB, 0xFF4A5C6A, 0xFF253745)

const val EXAM_NAME_MAX = 40

object ExamCountdown {
    /** Whole days from [today] to the exam; 0 on the day itself, negative once it has passed. */
    fun daysLeft(exam: Exam, today: LocalDate): Long = ChronoUnit.DAYS.between(today, exam.date)

    fun isPast(exam: Exam, today: LocalDate): Boolean = exam.date.isBefore(today)

    /** "D-76", "D-Day" on the day, "Done" afterwards. */
    fun dLabel(exam: Exam, today: LocalDate): String {
        val d = daysLeft(exam, today)
        return when {
            d > 0 -> "D-$d"
            d == 0L -> "D-Day"
            else -> "Done"
        }
    }

    /**
     * What the Focus card cycles through: exams marked "show on Focus" that have not passed yet.
     * The pinned one comes first, the rest nearest first.
     */
    fun forFocus(all: List<Exam>, today: LocalDate): List<Exam> =
        all.filter { it.showOnFocus && !isPast(it, today) }
            .sortedWith(compareByDescending<Exam> { it.pinned }.thenBy { it.date }.thenBy { it.id })

    /** The exam the Exams screen features: the pinned one if it is still ahead, else the nearest upcoming. */
    fun main(all: List<Exam>, today: LocalDate): Exam? {
        val upcoming = all.filter { !isPast(it, today) }
        return upcoming.firstOrNull { it.pinned } ?: upcoming.minByOrNull { it.date }
    }

    /** Fraction of the wait already behind you, from when the exam was added to its date. */
    fun progress(exam: Exam, today: LocalDate, addedOn: LocalDate): Float {
        val total = ChronoUnit.DAYS.between(addedOn, exam.date).coerceAtLeast(1)
        val gone = ChronoUnit.DAYS.between(addedOn, today).coerceIn(0, total)
        return gone / total.toFloat()
    }

    /** "10 weeks, 6 days left", "1 day left", "Today", "Done". */
    fun timeLeft(exam: Exam, today: LocalDate): String {
        val d = daysLeft(exam, today)
        if (d < 0) return "Done"
        if (d == 0L) return "Today"
        val weeks = d / 7
        val days = d % 7
        val w = if (weeks == 1L) "1 week" else "$weeks weeks"
        val dd = if (days == 1L) "1 day" else "$days days"
        return when {
            weeks == 0L -> "$dd left"
            days == 0L -> "$w left"
            else -> "$w, $dd left"
        }
    }

    // Built per call so a locale change while the app runs is picked up (lint ConstantLocale).
    private fun shortFmt() = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
    private fun longFmt() = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

    /** "Thu, 21 Jan" this year or next, "Thu, 21 Jan 2027" when the year matters. */
    fun shortDate(date: LocalDate, today: LocalDate): String =
        if (date.year == today.year || (date.isAfter(today) && ChronoUnit.DAYS.between(today, date) < 300)) date.format(shortFmt()) else date.format(longFmt())

    fun longDate(date: LocalDate): String = date.format(longFmt())
}
