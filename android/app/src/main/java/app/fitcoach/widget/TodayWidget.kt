package app.fitcoach.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.fitcoach.MainActivity
import app.fitcoach.data.PlanEngine
import app.fitcoach.data.Store
import app.fitcoach.data.Today
import app.fitcoach.notify.Notifier
import java.time.LocalDate

private val INK = Color(0xFF10231D)
private val TEXT = Color(0xFFE8EFEA)
private val MUTED = Color(0xFF93A59C)
private val TURMERIC = Color(0xFFF2B825)

/** Home-screen widget: progress, the next step with what to eat, and a Done button. */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store(context)
        val view = Today.load(store)
        val streak = view?.let { store.streak(it.date) } ?: 0
        provideContent {
            Column(
                modifier = GlanceModifier.fillMaxSize().background(ColorProvider(INK)).cornerRadius(20.dp)
                    .padding(14.dp).clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                if (view == null) {
                    Text("Open Lazy Fitness to set up your plan",
                        style = TextStyle(color = ColorProvider(TEXT), fontSize = 14.sp))
                } else {
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text("${view.done.size}/${view.steps.size} done",
                            style = TextStyle(color = ColorProvider(TURMERIC), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.defaultWeight())
                        if (streak > 0) Text("$streak day streak",
                            style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp))
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    val next = view.next
                    if (next == null) {
                        Text("All done for today. Nice work.",
                            style = TextStyle(color = ColorProvider(TEXT), fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    } else {
                        Text(PlanEngine.fmt(next.timeMin) + "  " + next.title,
                            style = TextStyle(color = ColorProvider(TEXT), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        Text(Today.shortLine(next),
                            style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp), maxLines = 2)
                        Spacer(GlanceModifier.height(8.dp))
                        androidx.glance.Button(
                            text = "Done",
                            onClick = actionRunCallback<MarkDoneAction>(
                                androidx.glance.action.actionParametersOf(
                                    KEY_STEP to next.id, KEY_DATE to view.date.toString()
                                )
                            ),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = ColorProvider(TURMERIC), contentColor = ColorProvider(INK)
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        val KEY_STEP = ActionParameters.Key<String>("step")
        val KEY_DATE = ActionParameters.Key<String>("date")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class MarkDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val step = parameters[TodayWidget.KEY_STEP] ?: return
        val date = parameters[TodayWidget.KEY_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        Notifier.markDone(context, date, step, true)
        refreshWidgets(context)
    }
}

suspend fun refreshWidgets(context: Context) = TodayWidget().updateAll(context)
