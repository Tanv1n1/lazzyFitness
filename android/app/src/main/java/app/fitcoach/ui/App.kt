package app.fitcoach.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.fitcoach.data.Api
import app.fitcoach.data.DayView
import app.fitcoach.data.PASS_STEPS
import app.fitcoach.data.PlanEngine
import app.fitcoach.data.Profile
import app.fitcoach.data.Step
import app.fitcoach.data.Store
import app.fitcoach.data.Today
import app.fitcoach.notify.Coach
import app.fitcoach.notify.Notifier
import app.fitcoach.widget.refreshWidgets
import app.fitcoach.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class Screen { Onboard, Building, Main }

@Composable
fun FitApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var screen by remember { mutableStateOf(if (store.profile == null) Screen.Onboard else Screen.Main) }
    var editing by remember { mutableStateOf<Profile?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            Screen.Onboard -> Onboarding(editing ?: store.profile, store.consent, store.serverUrl, store.invite) { p, consent, server, invite ->
                store.profile = p
                store.consent = consent
                store.serverUrl = server
                store.invite = invite
                screen = Screen.Building
            }
            Screen.Building -> Building(store) {
                Notifier.scheduleNext(ctx)
                editing = null
                screen = Screen.Main
            }
            Screen.Main -> MainShell(
                store,
                onEdit = { editing = store.profile; screen = Screen.Onboard },
                onRebuild = { screen = Screen.Building },
                onReset = { editing = null; screen = Screen.Onboard },
            )
        }
    }
}

/** Builds the plan. With consent the backend asks Claude, otherwise (or on any failure) the offline planner is used. */
@Composable
private fun Building(store: Store, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val profile = store.profile ?: return
    val lines = listOf("Counting calories for you", "Picking Pune-friendly meals", "Checking your health notes", "Laying out your timeline")
    var line by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val local = PlanEngine.localPlan(profile)
        val plan = if (store.consent) withContext(Dispatchers.IO) {
            withTimeoutOrNull(100_000) { if (Api.pushProfile(store, "local")) Api.aiPlan(store, profile) else null }
        } else null
        val final = plan ?: local
        store.plan = final
        if (store.consent) {
            SyncWorker.enqueue(ctx)
            if (final.source == "ai") withContext(Dispatchers.IO) { Api.pushProfile(store, "ai") }
        }
        onFinished()
    }
    LaunchedEffect(Unit) { while (true) { delay(1800); line = (line + 1) % lines.size } }

    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Building your plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(lines[line], color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (store.consent) {
            Text("Asking Claude for meals that fit you. This can take up to a minute.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp))
            TextButton(onClick = {
                store.plan = PlanEngine.localPlan(profile)
                onFinished()
            }) { Text("Use the quick plan now") }
        }
    }
}

@Composable
private fun MainShell(store: Store, onEdit: () -> Unit, onRebuild: () -> Unit, onReset: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Today") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Person, null) }, label = { Text("Me") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) TodayScreen(store) else MeScreen(store, onEdit, onRebuild, onReset)
        }
    }
}

// ---------------------------------------------------------------- Today

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayScreen(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val view: DayView? = remember(version) { Today.load(store) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }   // step id to dialog mode

    // Ask for notification permission once (Android 13+), and refresh the "up next" every minute.
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) { delay(60_000); version++ }
    }
    if (view == null) return
    val p = store.profile ?: return
    val t = PlanEngine.targets(p)
    val plan = store.plan

    fun toggle(s: Step) {
        val now = s.id in view.done
        Notifier.markDone(ctx, view.date, s.id, !now)
        scope.launch { refreshWidgets(ctx) }
        version++
    }

    dialog?.let { (id, mode) ->
        val step = view.steps.firstOrNull { it.id == id }
        if (step != null) {
            StepDialog(store, step, mode, view.entries[id], onDismiss = { dialog = null }) { entry, done, rewrite ->
                if (rewrite != null) store.setRevision(view.date, id, rewrite)
                Notifier.record(ctx, view.date, id, entry, done)
                // A skipped or swapped meal also gets an AI tip, calories, and maybe an adjusted next meal, in the background.
                if (entry.status == "skipped" || entry.status == "replaced") Coach.afterSkipOrSwap(ctx, view.date, id) { version++ }
                scope.launch { refreshWidgets(ctx) }
                dialog = null
                version++
            }
        }
    }

    val hour = LocalDateTime.now().hour
    val greet = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                Text("$greet, ${p.username}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(view.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) + "  ·  Pune",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${view.done.size} of ${view.steps.size} done", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    val streak = store.streak(view.date)
                    Text(if (streak > 0) "$streak day streak" else "A day counts at $PASS_STEPS steps",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                LinearProgressIndicator(
                    progress = { view.done.size / view.steps.size.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
                Text("Target ${t.kcal} kcal  ·  ${t.protein} g protein  ·  ${t.waterL} L water",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                val glasses = PlanEngine.glassTarget(t)
                val water = store.water(view.date)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Water  $water of $glasses glasses", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Pal.water)
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = water > 0, onClick = { Notifier.addWater(ctx, view.date, -1); version++ }) { Text("-1") }
                    TextButton(onClick = { Notifier.addWater(ctx, view.date, 1); version++ }) { Text("+1 glass") }
                }
            }
        }
        view.next?.let { n ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("UP NEXT  ·  " + PlanEngine.fmt(n.timeMin), fontSize = 11.sp, letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .75f))
                        Text(n.title + (n.name?.takeIf { n.kind == "meal" }?.let { ": $it" } ?: ""),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Detail(n, MaterialTheme.colorScheme.onPrimary, onPrimary = true)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                            Button(
                                onClick = { toggle(n) },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary),
                            ) { Text("Mark done") }
                            TextButton(onClick = { dialog = n.id to "skip" }) { Text("Skip", color = MaterialTheme.colorScheme.onPrimary) }
                            if (n.kind == "meal" || n.kind == "workout") {
                                TextButton(onClick = { dialog = n.id to "replace" }) {
                                    Text("Something else", color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
        items(view.steps, key = { it.id }) { s ->
            val done = s.id in view.done
            val entry = view.entries[s.id]
            val skipped = entry?.status == "skipped"
            val open = expanded[s.id] == true
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Text(PlanEngine.fmt(s.timeMin), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(66.dp).padding(top = 4.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp).fillMaxHeight()) {
                    val ring = when {
                        skipped -> Pal.skip
                        s.kind == "water" -> Pal.water
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(if (done) Pal.ok else Color.Transparent)
                            .border(BorderStroke(2.dp, if (done) Pal.ok else ring), CircleShape)
                            .clickable { toggle(s) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(Icons.Filled.Check, "Done", tint = Color.White, modifier = Modifier.size(16.dp))
                        else if (skipped) Text("-", color = Pal.skip, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outline))
                }
                Card(
                    modifier = Modifier.weight(1f).padding(bottom = 12.dp).clickable { expanded[s.id] = !open },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (done) .5f else 1f))
                            s.kcal?.let { Text("~$it kcal", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        val sub = s.name ?: s.note
                        if (sub != null && (s.kind != "sleep")) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        if (entry != null) {
                            val line = when (entry.status) {
                                "skipped" -> "Skipped" + if (entry.text.isNotBlank()) ": ${entry.text}" else ""
                                "replaced" -> (if (s.kind == "meal") "Ate instead: " else "Did instead: ") + entry.text +
                                    (entry.kcal?.let { " (~$it kcal)" } ?: "")
                                else -> null
                            }
                            if (line != null) Text(line, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = if (skipped) Pal.skip else Pal.ok, modifier = Modifier.padding(top = 4.dp))
                            if (entry.tip.isNotBlank()) Text("Coach: ${entry.tip}", fontSize = 12.sp, color = Pal.water,
                                modifier = Modifier.padding(top = 2.dp))
                            if (entry.note.isNotBlank()) Text("Your request: ${entry.note}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        }
                        view.revisions[s.id]?.let { rev ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (rev.why.isNotBlank()) "Rewritten: ${rev.why}" else "Rewritten by the AI", fontSize = 12.sp,
                                    color = Pal.water, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    store.setRevision(view.date, s.id, null)
                                    Notifier.scheduleNext(ctx)
                                    scope.launch { refreshWidgets(ctx) }
                                    version++
                                }) { Text("Undo") }
                            }
                        }
                        if (open || (s.id == view.next?.id && !done)) {
                            Detail(s, MaterialTheme.colorScheme.onSurface, onPrimary = false)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!skipped) TextButton(onClick = { dialog = s.id to "skip" }) { Text("Skip") }
                                if (s.kind == "meal" || s.kind == "workout") {
                                    TextButton(onClick = { dialog = s.id to "replace" }) {
                                        Text(if (s.kind == "meal") "Ate something else" else "Did something else")
                                    }
                                }
                                TextButton(onClick = { dialog = s.id to "note" }) {
                                    Text(when (s.kind) { "meal" -> "Change this meal"; "workout" -> "Change this workout"; else -> "Note to coach" })
                                }
                                if (s.kind == "meal" && plan != null) {
                                    TextButton(onClick = { store.bumpSwap(view.date, s.id); Notifier.scheduleNext(ctx); version++ }) {
                                        Text("Show a different meal")
                                    }
                                }
                            }
                        } else if (s.kind == "meal") {
                            Text("Tap to see what to eat and skip", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            val notes = PlanEngine.healthNotes(p)
            Column(Modifier.padding(vertical = 12.dp)) {
                if (notes.isNotEmpty()) {
                    Text("Your health notes", fontWeight = FontWeight.Bold)
                    notes.forEach { Text("•  $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }
                Text("This is general guidance, not medical advice. Check your plan with your doctor if you have a medical condition.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** Eat / skip / exercises / note for one step. */
@Composable
private fun Detail(s: Step, base: Color, onPrimary: Boolean) {
    val skipColor = if (onPrimary) base else Pal.skip
    val okColor = if (onPrimary) base else Pal.ok
    Column(Modifier.padding(top = 6.dp)) {
        if (s.kind == "meal") {
            Text("EAT", fontSize = 11.sp, letterSpacing = 1.sp, color = okColor, fontWeight = FontWeight.Bold)
            s.eat.forEach { Text("•  $it", color = base, fontSize = 14.sp) }
            if (s.skip.isNotEmpty()) {
                Text("SKIP", fontSize = 11.sp, letterSpacing = 1.sp, color = skipColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp))
                s.skip.forEach { Text("•  $it", color = base, fontSize = 14.sp) }
            }
        }
        if (s.kind == "workout") {
            s.exercises.forEach { (n, d) ->
                Row { Text(n, color = base, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(d, color = base, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        if (s.kind != "meal" && s.note != null) Text(s.note, color = base.copy(alpha = .85f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- Me

@Composable
private fun MeScreen(store: Store, onEdit: () -> Unit, onRebuild: () -> Unit, onReset: () -> Unit) {
    val p = store.profile ?: return
    val t = PlanEngine.targets(p)
    val plan = store.plan
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 20.dp)) {
                Text(p.username, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                val (ft, inch) = PlanEngine.feetInches(p.heightCm)
                Text("${p.age} yrs  ·  $ft ft $inch in  ·  ${p.weightKg} kg  ·  BMI ${t.bmi}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Panel("Daily targets") {
                Stat("Calories", "${t.kcal} kcal"); Stat("Protein", "${t.protein} g"); Stat("Water", "${t.waterL} L")
                Stat("Wake / sleep", PlanEngine.fmt(PlanEngine.hm(p.wake)) + " / " + PlanEngine.fmt(PlanEngine.hm(p.sleep)))
            }
        }
        item {
            val w = store.week(PlanEngine.activeDate(p, java.time.LocalDateTime.now()))
            Panel("Last 7 days") {
                Stat("Days with a check-in", "${w.daysLogged} of 7")
                Stat("Average completion", "${w.avgPct}%")
                Stat("Skipped steps", "${w.skipped}")
                Stat("Meals or workouts swapped", "${w.replaced}")
                w.mostSkipped?.let { Stat("Skipped most", PlanEngine.STEP_NAMES[it] ?: it) }
            }
        }
        item {
            Panel("Your plan") {
                Text(plan?.summary ?: "", fontSize = 14.sp)
                Text(if (plan?.source == "ai") "Meals written by an AI service for your profile" else "Built by the offline planner",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val watch = plan?.watchlist.orEmpty()
        if (watch.isNotEmpty()) item {
            Panel("Watch out for, in Pune") { watch.forEach { Text("•  $it", fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) } }
        }
        val notes = PlanEngine.healthNotes(p)
        if (notes.isNotEmpty()) item {
            Panel("Health notes") { notes.forEach { Text("•  $it", fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit profile") }
                OutlinedButton(onClick = onRebuild) { Text("Rebuild plan") }
            }
            Text(if (store.consent) "Syncing with your coach dashboard." else "Sync is off. Your data stays on this phone.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (store.consent && store.syncNote.isNotEmpty())
                Text("Sync problem: " + store.syncNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete my data", color = MaterialTheme.colorScheme.error) }
            if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything?") },
            text = { Text("This removes your profile and check-ins from this phone and, if you shared them, from the server. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Api.deleteAccount(store) }
                        if (ok) { store.clearAll(); onReset() } else note = "Could not reach the server. Nothing was deleted. Try again when you are online."
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            content()
        }
    }
}

@Composable
private fun Stat(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(v, fontFamily = FontFamily.Monospace)
    }
}
