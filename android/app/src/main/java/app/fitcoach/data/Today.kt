package app.fitcoach.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

data class DayView(
    val date: LocalDate,
    val steps: List<Step>,
    val done: Set<String>,
    val next: Step?,
    val nowMin: Int,
)

object Today {
    /** Builds today's timeline from the saved profile and plan. Null until onboarding is finished. */
    fun load(store: Store, now: LocalDateTime = LocalDateTime.now()): DayView? {
        val p = store.profile ?: return null
        val plan = store.plan ?: PlanEngine.localPlan(p)
        val date = PlanEngine.activeDate(p, now)
        val steps = PlanEngine.dayPlan(p, plan, date) { slot -> store.swaps(date, slot) }
        val done = store.done(date)
        val nowMin = Duration.between(date.atStartOfDay(), now).toMinutes().toInt()
        val pending = steps.filter { it.id !in done }
        val next = pending.firstOrNull { it.timeMin >= nowMin - 90 } ?: pending.firstOrNull()
        return DayView(date, steps, done, next, nowMin)
    }

    /** One-line summary used by the widget and notifications. */
    fun shortLine(s: Step): String = when (s.kind) {
        "meal" -> "Eat: " + s.eat.take(2).joinToString(", ")
        "workout" -> s.title + ", " + (s.name ?: "")
        "water" -> "Two big glasses, before tea or your phone"
        else -> "Screens down, lights out"
    }

    fun longText(s: Step): String = buildString {
        when (s.kind) {
            "meal" -> {
                append("~${s.kcal} kcal. ${s.name}\n")
                append("Eat: ").append(s.eat.joinToString(", "))
                if (s.skip.isNotEmpty()) append("\nSkip: ").append(s.skip.joinToString("; "))
            }
            "workout" -> {
                append(s.title).append(", ").append(s.name).append("\n")
                append(s.exercises.joinToString("\n") { "${it.first}: ${it.second}" })
            }
            else -> append(s.note ?: "")
        }
    }
}
