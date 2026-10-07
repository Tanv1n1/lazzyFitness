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
import app.fitcoach.data.Entry
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
    private const val WATER_CHANNEL = "water"
    private const val ALARM_CODE = 4001
    const val EXTRA_DATE = "date"
    const val EXTRA_STEP = "step"
    const val EXTRA_ACTION = "action"   // "done" or "skip" on a step notification

    private class Slot(val at: Long, val date: LocalDate, val id: String)

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (nm.getNotificationChannel(WATER_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(WATER_CHANNEL, ctx.getString(R.string.channel_water), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** "water1" to "water4" are the hydration reminders. Plain "water" is the 400 ml wake-up step. */
    fun isNudge(id: String) = id.length == 6 && id.startsWith("water")

    private fun alarmIntent(ctx: Context, date: LocalDate?, id: String?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            date?.let { putExtra(EXTRA_DATE, it.toString()) }
            id?.let { putExtra(EXTRA_STEP, it) }
        }
        return PendingIntent.getBroadcast(ctx, ALARM_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Arms one alarm for the next reminder that still matters: a step that is neither done nor skipped,
     * or a water nudge while today's glasses are below target. Called after every change and after each reminder fires.
     */
    fun scheduleNext(ctx: Context) {
        val store = Store(ctx)
        val p = store.profile ?: return
        val plan = store.plan ?: PlanEngine.localPlan(p)
        val zone = ZoneId.systemDefault()
        val glasses = PlanEngine.glassTarget(PlanEngine.targets(p))
        val base = PlanEngine.activeDate(p, LocalDateTime.now())
        fun epoch(date: LocalDate, minutes: Int) =
            date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

        val slots = ArrayList<Slot>()
        for (offset in -1..1) {
            val date = base.plusDays(offset.toLong())
            val done = store.done(date)
            val entries = store.entries(date)
            for (s in PlanEngine.dayPlan(p, plan, date) { slot -> store.swaps(date, slot) }) {
                if (s.id in done || entries[s.id]?.status == "skipped") continue
                slots += Slot(epoch(date, s.timeMin), date, s.id)
            }
            if (store.water(date) < glasses) {
                for ((id, minutes) in PlanEngine.waterNudges(p)) slots += Slot(epoch(date, minutes), date, id)
            }
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = slots.filter { it.at > System.currentTimeMillis() + 15_000 }.minByOrNull { it.at }
        if (next == null) { am.cancel(alarmIntent(ctx, null, null)); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, alarmIntent(ctx, next.date, next.id))
    }

    /** Every change to a day ends here: re-arm the next reminder and queue a sync. */
    private fun changed(ctx: Context) {
        scheduleNext(ctx)
        SyncWorker.enqueue(ctx)
    }

    /**
     * Ticks or unticks a step and updates everything that shows it or syncs it. Widgets refresh separately (suspend).
     * A plain tick or untick clears an earlier "skipped" or "replaced" record, but keeps a change request.
     */
    fun markDone(ctx: Context, date: LocalDate, id: String, done: Boolean) {
        val store = Store(ctx)
        store.setDone(date, id, done)
        val e = store.entries(date)[id]
        if (e != null && e.status != "note") store.setEntry(date, id, if (e.note.isBlank()) null else Entry("note", note = e.note))
        if (done) cancel(ctx, id)
        changed(ctx)
    }

    /** Records what actually happened to a step: skipped, or replaced by something else, or just a note for the coach. */
    fun record(ctx: Context, date: LocalDate, id: String, entry: Entry, done: Boolean?) {
        val store = Store(ctx)
        store.setEntry(date, id, entry)
        if (done != null) store.setDone(date, id, done)
        if (done == true || entry.status == "skipped") cancel(ctx, id)
        changed(ctx)
    }

    fun addWater(ctx: Context, date: LocalDate, delta: Int) {
        val store = Store(ctx)
        store.setWater(date, store.water(date) + delta)
        if (delta > 0) for (k in 1..4) NotificationManagerCompat.from(ctx).cancel(300 + k)
        changed(ctx)
    }

    private fun notifId(stepId: String) = 200 + STEP_IDS.indexOf(stepId)

    fun cancel(ctx: Context, stepId: String) = NotificationManagerCompat.from(ctx).cancel(notifId(stepId))

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stepAction(ctx: Context, date: LocalDate, id: String, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code,
        Intent(ctx, DoneReceiver::class.java).putExtra(EXTRA_DATE, date.toString()).putExtra(EXTRA_STEP, id).putExtra(EXTRA_ACTION, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun show(ctx: Context, date: LocalDate, s: Step) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(ctx)
        val title = PlanEngine.fmt(s.timeMin) + "  " + s.title + (s.kcal?.let { "  ~$it kcal" } ?: "")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(Today.shortLine(s))
            .setStyle(NotificationCompat.BigTextStyle().bigText(Today.longText(s)))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", stepAction(ctx, date, s.id, "done", notifId(s.id) * 2))
            .addAction(0, "Skip", stepAction(ctx, date, s.id, "skip", notifId(s.id) * 2 + 1))
            .build()
        runCatching { nm.notify(notifId(s.id), n) }   // SecurityException if permission was revoked mid-flight
    }

    /** A hydration reminder. Skipped if the day's glasses are already at target. */
    fun showWater(ctx: Context, date: LocalDate, id: String) {
        val store = Store(ctx)
        val p = store.profile ?: return
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val target = PlanEngine.glassTarget(PlanEngine.targets(p))
        val have = store.water(date)
        if (have >= target) return
        ensureChannel(ctx)
        val drank = PendingIntent.getBroadcast(
            ctx, 300 + id.last().digitToInt(),
            Intent(ctx, WaterReceiver::class.java).putExtra(EXTRA_DATE, date.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, WATER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Water time")
            .setContentText("Drink a glass (250 ml). $have of $target so far today.")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, "Drank a glass", drank)
            .build()
        runCatching { nm.notify(300 + id.last().digitToInt(), n) }
    }
}

/** Refreshes widgets off the main thread, keeping the receiver alive until done. */
private fun BroadcastReceiver.refreshWidgetsFrom(ctx: Context) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try { refreshWidgets(ctx) } finally { pending.finish() }
    }
}

private fun Intent.date(): LocalDate? = getStringExtra(Notifier.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Fires at each reminder time. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val date = intent.date()
        val id = intent.getStringExtra(Notifier.EXTRA_STEP)
        val store = Store(ctx)
        val p = store.profile
        if (p != null && date != null && id != null) {
            if (Notifier.isNudge(id)) {
                Notifier.showWater(ctx, date, id)
            } else if (id !in store.done(date) && store.entries(date)[id]?.status != "skipped") {
                val plan = store.plan ?: PlanEngine.localPlan(p)
                PlanEngine.dayPlan(p, plan, date) { slot -> store.swaps(date, slot) }
                    .firstOrNull { it.id == id }?.let { Notifier.show(ctx, date, it) }
            }
        }
        Notifier.scheduleNext(ctx)
    }
}

/** Handles the "Done" and "Skip" buttons on a step notification. */
class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val date = intent.date() ?: return
        val id = intent.getStringExtra(Notifier.EXTRA_STEP) ?: return
        if (intent.getStringExtra(Notifier.EXTRA_ACTION) == "skip") {
            Notifier.record(ctx, date, id, Entry("skipped", text = "Skipped from the notification"), false)
        } else {
            Notifier.markDone(ctx, date, id, true)
        }
        refreshWidgetsFrom(ctx)
    }
}

/** Handles the "Drank a glass" button on a water reminder. */
class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val date = intent.date() ?: return
        Notifier.addWater(ctx, date, 1)
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
