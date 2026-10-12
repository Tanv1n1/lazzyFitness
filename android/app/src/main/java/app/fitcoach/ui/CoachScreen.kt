package app.fitcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitcoach.data.Api
import app.fitcoach.data.DayView
import app.fitcoach.data.PlanEngine
import app.fitcoach.data.Store
import app.fitcoach.data.Today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

private val STARTERS = listOf("What should I eat right now?", "Where can I eat out near me?", "Am I on track this week?", "What should I have for dinner?")

/** Today as the coach needs it: the clock, the plan with what is done, skipped or swapped, and water. */
private fun todayJson(store: Store, v: DayView): JSONObject {
    val now = LocalDateTime.now()
    val steps = v.steps.map { s ->
        val e = v.entries[s.id]
        val state = when {
            s.id in v.done -> "done"
            e?.status == "skipped" -> "skipped"
            e?.status == "replaced" -> "ate instead: " + e.text.take(40)
            else -> "pending"
        }
        JSONObject().put("time", PlanEngine.fmt(s.timeMin)).put("title", s.title).put("name", if (s.kind == "meal") s.name ?: "" else "").put("state", state)
    }
    val glasses = store.profile?.let { PlanEngine.glassTarget(PlanEngine.targets(it)) } ?: 8
    return JSONObject().put("time", "%02d:%02d".format(now.hour, now.minute)).put("water", store.water(v.date)).put("glasses", glasses)
        .put("steps", JSONArray(steps))
}

/** A chat with a coach that knows this person's plan, week and health flags, and Pune's food. The server writes every reply from that. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(store: Store) {
    val scope = rememberCoroutineScope()
    var chat by remember { mutableStateOf(store.chat) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ready = store.consent && store.userId != null

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || busy) return
        chat = chat + ("user" to msg)
        store.chat = chat
        draft = ""; busy = true; error = ""
        scope.launch {
            val today = Today.load(store)?.let { todayJson(store, it) } ?: JSONObject()
            val r = withContext(Dispatchers.IO) { Api.chat(store, msg, today) }
            busy = false
            if (r.text != null) { chat = chat + ("coach" to r.text); store.chat = chat } else error = r.error ?: "The coach could not answer."
        }
    }
    LaunchedEffect(chat.size, busy) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Your coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 20.dp))
        Text("It knows your plan, your week and ${store.cityName}'s food. Tell it what you like and it remembers.", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!ready) Text("Turn on sharing under Me, Edit profile, so the coach can talk to you.", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            if (chat.isEmpty() && ready) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STARTERS.forEach { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }) }
                }
            }
            items(chat) { (role, text) ->
                val mine = role == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(0.88f),
                    ) {
                        Text(text, Modifier.padding(12.dp), color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Thinking about your day...", fontSize = 13.sp)
                }
            }
            if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            OutlinedTextField(draft, { draft = it.take(500) }, placeholder = { Text("Ask your coach") }, singleLine = true, enabled = ready && !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(draft) }),
                modifier = Modifier.weight(1f))
            TextButton(enabled = ready && !busy && draft.isNotBlank(), onClick = { send(draft) }) { Text("Send") }
        }
    }
}
