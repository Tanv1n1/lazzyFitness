package app.fitcoach.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.fitcoach.MainActivity
import app.fitcoach.R
import app.fitcoach.data.PlanEngine
import app.fitcoach.data.STEP_IDS
import app.fitcoach.data.Step
import app.fitcoach.data.Store
import app.fitcoach.data.Today
import app.fitcoach.widget.refreshWidgets
import app.fitcoach.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Notifier {
    private const val CHANNEL = "reminders"
    private const val ALARM_CODE = 4001
    const val EXTRA_DATE = "date"
    const val EXTRA_STEP = "step"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun alarmIntent(ctx: Context, date: LocalDate?, id: String?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            date?.let { putExtra(EXTRA_DATE, it.toString()) }
            id?.let { putExtra(EXTRA_STEP, it) }
        }
        return PendingIntent.getBroadcast(ctx, ALARM_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Arms one alarm for the next step that is still undone. Called after every change and after each reminder fires. */
    fun scheduleNext(ctx: Context) {
        val store = Store(ctx)
        val p = store.profile ?: return
        val plan = store.plan ?: PlanEngine.localPlan(p)
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val base = PlanEngine.activeDate(p, now)
        var best: Triple<Long, LocalDate, Step>? = null
        for (offset in -1..1) {
            val date = base.plusDays(offset.toLong())
            val done = store.done(date)
            for (s in PlanEngine.dayPlan(p, plan, date) { slot -> store.swaps(date, slot) }) {
                if (s.id in done) continue
                val at = date.atStartOfDay(zone).plusMinutes(s.timeMin.toLong()).toInstant().toEpochMilli()
                if (at > System.currentTimeMillis() + 15_000 && (best == null || at < best.first)) best = Triple(at, date, s)
            }
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val target = best ?: run { am.cancel(alarmIntent(ctx, null, null)); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.first, alarmIntent(ctx, target.second, target.third.id))
    }

    /** Ticks or unticks a step and updates everything that shows it or syncs it. Widgets refresh separately (suspend). */
    fun markDone(ctx: Context, date: LocalDate, id: String, done: Boolean) {
        Store(ctx).setDone(date, id, done)
        if (done) cancel(ctx, id)
        scheduleNext(ctx)
        SyncWorker.enqueue(ctx)
    }

    private fun notifId(stepId: String) = 200 + STEP_IDS.indexOf(stepId)

    fun cancel(ctx: Context, stepId: String) = NotificationManagerCompat.from(ctx).cancel(notifId(stepId))

    fun show(ctx: Context, date: LocalDate, s: Step) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val doneIntent = PendingIntent.getBroadcast(
            ctx, notifId(s.id),
            Intent(ctx, DoneReceiver::class.java).putExtra(EXTRA_DATE, date.toString()).putExtra(EXTRA_STEP, s.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = PlanEngine.fmt(s.timeMin) + "  " + s.title + (s.kcal?.let { "  ~$it kcal" } ?: "")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(Today.shortLine(s))
            .setStyle(NotificationCompat.BigTextStyle().bigText(Today.longText(s)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", doneIntent)
            .build()
        runCatching { nm.notify(notifId(s.id), n) }   // SecurityException if permission was revoked mid-flight
    }
}

/** Refreshes widgets off the main thread, keeping the receiver alive until done. */
private fun BroadcastReceiver.refreshWidgetsFrom(ctx: Context) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try { refreshWidgets(ctx) } finally { pending.finish() }
    }
}

/** Fires at each step time. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val date = intent.getStringExtra(Notifier.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val id = intent.getStringExtra(Notifier.EXTRA_STEP)
        val store = Store(ctx)
        val p = store.profile
        if (p != null && date != null && id != null && id !in store.done(date)) {
            val plan = store.plan ?: PlanEngine.localPlan(p)
            PlanEngine.dayPlan(p, plan, date) { slot -> store.swaps(date, slot) }
                .firstOrNull { it.id == id }?.let { Notifier.show(ctx, date, it) }
        }
        Notifier.scheduleNext(ctx)
    }
}

/** Handles the "Done" button on a notification. */
class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val date = intent.getStringExtra(Notifier.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val id = intent.getStringExtra(Notifier.EXTRA_STEP) ?: return
        Notifier.markDone(ctx, date, id, true)
        refreshWidgetsFrom(ctx)
    }
}

/** Re-arms reminders after reboot, app update, or a clock / timezone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifier.ensureChannel(ctx)
        Notifier.scheduleNext(ctx)
        refreshWidgetsFrom(ctx)
    }
}
