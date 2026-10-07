package app.fitcoach.notify

import android.content.Context
import app.fitcoach.data.Api
import app.fitcoach.data.PlanEngine
import app.fitcoach.data.Store
import app.fitcoach.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Updates the rest of the day with the AI after a meal is skipped or swapped. Quiet: the skip or swap is already saved. */
object Coach {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun afterSkipOrSwap(ctx: Context, date: LocalDate, stepId: String, onDone: () -> Unit) {
        scope.launch {
            val store = Store(ctx)
            val p = store.profile ?: return@launch
            val steps = store.daySteps(date)
            val step = steps.firstOrNull { it.id == stepId && it.kind == "meal" } ?: return@launch
            val entry = store.entries(date)[stepId]?.takeIf { it.status == "skipped" || it.status == "replaced" } ?: return@launch
            val done = store.done(date)
            val entries = store.entries(date)
            val next = steps.firstOrNull {
                it.kind == "meal" && it.timeMin > step.timeMin && it.id !in done && entries[it.id]?.status != "skipped"
            }
            val eaten = steps.filter { it.kind == "meal" && it.id in done }.sumOf { entries[it.id]?.kcal ?: it.kcal ?: 0 }
            val mode = if (entry.status == "replaced") "replaced" else "skipped"
            val r = Api.stepAi(store, mode, step, entry.text, next, eaten, PlanEngine.targets(p).kcal)
            if (r.error != null) return@launch
            store.setEntry(date, stepId, entry.copy(kcal = entry.kcal ?: r.kcal, tip = r.tip))
            if (r.revision != null && next != null) {
                val why = "Adjusted after you ${if (mode == "skipped") "skipped" else "swapped"} ${step.title.lowercase()}"
                store.setRevision(date, next.id, r.revision.copy(why = why))
            }
            Notifier.scheduleNext(ctx)
            SyncWorker.enqueue(ctx)
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
