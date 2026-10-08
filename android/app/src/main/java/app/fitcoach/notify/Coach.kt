package app.fitcoach.notify

import android.content.Context
import app.fitcoach.data.Api
import app.fitcoach.data.Store
import app.fitcoach.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** After a meal is skipped or swapped, asks the AI for a short tip and, for a swap, the calories. Quiet: the skip or swap is already saved. */
object Coach {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun afterSkipOrSwap(ctx: Context, date: LocalDate, stepId: String, onDone: () -> Unit) {
        scope.launch {
            val store = Store(ctx)
            val step = store.daySteps(date).firstOrNull { it.id == stepId && it.kind == "meal" } ?: return@launch
            val entry = store.entries(date)[stepId]?.takeIf { it.status == "skipped" || it.status == "replaced" } ?: return@launch
            val r = Api.stepAi(store, if (entry.status == "replaced") "replaced" else "skipped", step, entry.text)
            if (r.error != null) return@launch
            store.setEntry(date, stepId, entry.copy(kcal = entry.kcal ?: r.kcal, tip = r.tip))
            SyncWorker.enqueue(ctx)
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
