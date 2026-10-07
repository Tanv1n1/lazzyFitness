package app.fitcoach.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fitcoach.data.Api
import app.fitcoach.data.PlanEngine
import app.fitcoach.data.Store
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Uploads profile and daily ticks to the coach dashboard. Does nothing without consent. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val p = store.profile ?: return Result.success()
        if (!store.consent) return Result.success()
        if (store.userId == null && !Api.pushProfile(store, store.plan?.source ?: "local")) return retryOrGiveUp()
        val today: LocalDate = PlanEngine.activeDate(p, LocalDateTime.now())
        val ok = listOf(today.minusDays(1), today).all { Api.syncDay(store, it) }
        return if (ok) Result.success() else retryOrGiveUp()
    }

    private fun retryOrGiveUp() = if (runAttemptCount < 5) Result.retry() else Result.failure()

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("fitcoach-sync", ExistingWorkPolicy.REPLACE, req)
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("fitcoach-sync-daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
