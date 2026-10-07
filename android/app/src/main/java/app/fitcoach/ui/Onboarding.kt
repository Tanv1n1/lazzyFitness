package app.fitcoach.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitcoach.data.PlanEngine
import app.fitcoach.data.Profile

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Chips(options: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (id, label) ->
            FilterChip(selected = id in selected, onClick = { onToggle(id) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val (h, m) = value.split(":").map { it.toInt() }
    OutlinedButton(
        onClick = { TimePickerDialog(ctx, { _, hh, mm -> onChange("%02d:%02d".format(hh, mm)) }, h, m, false).show() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("$label   ${PlanEngine.fmt(h * 60 + m)}") }
}

/** Three short steps: you, your day, goal and health. [initial] pre-fills the form when editing. */
@Composable
fun Onboarding(initial: Profile?, hadConsent: Boolean, initialServer: String, initialInvite: String,
               onFinish: (Profile, Boolean, String, String) -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    var username by rememberSaveable { mutableStateOf(initial?.username ?: "") }
    var age by rememberSaveable { mutableStateOf(initial?.age?.toString() ?: "") }
    val startFtIn = initial?.heightCm?.let { PlanEngine.feetInches(it) }
    var feet by rememberSaveable { mutableStateOf(startFtIn?.first?.toString() ?: "") }
    var inches by rememberSaveable { mutableStateOf(startFtIn?.second?.toString() ?: "") }
    var weight by rememberSaveable { mutableStateOf(initial?.weightKg?.toString() ?: "") }
    var sex by rememberSaveable { mutableStateOf(initial?.sex ?: "") }
    var wake by rememberSaveable { mutableStateOf(initial?.wake ?: "06:30") }
    var sleep by rememberSaveable { mutableStateOf(initial?.sleep ?: "23:00") }
    var diet by rememberSaveable { mutableStateOf(initial?.diet ?: "") }
    var place by rememberSaveable { mutableStateOf(initial?.place ?: "home") }
    var wtime by rememberSaveable { mutableStateOf(initial?.wtime ?: "evening") }
    var goal by rememberSaveable { mutableStateOf(initial?.goal ?: "") }
    var condsCsv by rememberSaveable { mutableStateOf(initial?.conds?.joinToString(",") ?: "") }
    var other by rememberSaveable { mutableStateOf(initial?.other ?: "") }
    var consent by rememberSaveable { mutableStateOf(hadConsent) }
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var invite by rememberSaveable { mutableStateOf(initialInvite) }
    var error by rememberSaveable { mutableStateOf("") }
    val conds = condsCsv.split(",").filter { it.isNotEmpty() }.toSet()

    fun heightCm() = ((feet.toIntOrNull() ?: 0) * 12 + (inches.toIntOrNull() ?: 0)) * 2.54

    fun validate(): String {
        when (step) {
            0 -> {
                if (username.trim().length !in 2..24) return "Pick a username with 2 to 24 characters."
                if (age.toIntOrNull() !in 16..80) return "Age should be between 16 and 80."
                if (feet.toIntOrNull() == null || inches.toIntOrNull().let { it != null && it > 11 })
                    return "Enter your height as feet and inches, for example 5 and 7."
                if (heightCm() !in 120.0..230.0) return "Height should be between about 4 ft and 7 ft 6 in."
                if (weight.toDoubleOrNull()?.let { it in 30.0..250.0 } != true) return "Weight should be in kg, between 30 and 250."
                if (sex.isEmpty()) return "Choose one option for sex. It is only used for the calorie estimate."
            }
            1 -> if (diet.isEmpty()) return "Choose what you eat."
            2 -> {
                if (goal.isEmpty()) return "Choose a goal."
                if (consent && !server.trim().startsWith("http")) return "Enter the server address you were given, starting with https://"
            }
        }
        return ""
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 28.dp, bottom = 16.dp)) {
        Text("Lazy Fitness", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("Step ${step + 1} of 3", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> {
                    Text("About you", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(username, { username = it.take(24) }, label = { Text("Username") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(age, { age = it.filter(Char::isDigit).take(2) }, label = { Text("Age") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Weight kg") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(feet, { feet = it.filter(Char::isDigit).take(1) }, label = { Text("Height ft") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(inches, { inches = it.filter(Char::isDigit).take(2) }, label = { Text("Height in") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Label("Sex (for the calorie estimate)")
                    Chips(listOf("male" to "Male", "female" to "Female", "other" to "Prefer not to say"), setOf(sex)) { sex = it }
                }
                1 -> {
                    Text("Your day", style = MaterialTheme.typography.titleLarge)
                    Text("No fixed schedule is fine. Your timeline starts the moment you wake up.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    TimeField("Wake up", wake) { wake = it }
                    Spacer(Modifier.height(8.dp))
                    TimeField("Sleep", sleep) { sleep = it }
                    Label("What do you eat?")
                    Chips(listOf("veg" to "Vegetarian", "egg" to "Eggetarian", "nonveg" to "Non-veg"), setOf(diet)) { diet = it }
                    Label("Where do you train?")
                    Chips(listOf("home" to "At home", "gym" to "Gym"), setOf(place)) { place = it }
                    Label("When do you prefer to train?")
                    Chips(listOf("morning" to "Morning", "evening" to "Evening"), setOf(wtime)) { wtime = it }
                }
                else -> {
                    Text("Goal and health", style = MaterialTheme.typography.titleLarge)
                    Label("Your goal")
                    Chips(listOf("lose" to "Lose fat", "gain" to "Build muscle", "maintain" to "Stay where I am"), setOf(goal)) { goal = it }
                    Label("Any medical issue that affects diet? (optional)")
                    Chips(PlanEngine.CONDITIONS, conds) { c ->
                        condsCsv = (if (c in conds) conds - c else conds + c).joinToString(",")
                    }
                    OutlinedTextField(other, { other = it.take(80) }, label = { Text("Anything else, like a food allergy") },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    Row(Modifier.padding(top = 14.dp)) {
                        Checkbox(consent, { consent = it })
                        Column(Modifier.padding(top = 12.dp)) {
                            Text("Share my profile and daily check-ins with my coach dashboard", fontWeight = FontWeight.Medium)
                            Text("Your username, body stats, goal, medical flags and ticks are sent to the Lazy Fitness server. " +
                                "Free-text notes stay on your phone. Leave this off and everything stays on your phone, " +
                                "with the offline planner. You can delete your data any time from the Me tab.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (consent) {
                        OutlinedTextField(server, { server = it.take(120) }, label = { Text("Server address") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        OutlinedTextField(invite, { invite = it.take(40) }, label = { Text("Invite code") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text("Both come from the person running the test.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (step > 0) TextButton(onClick = { step--; error = "" }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                error = validate()
                if (error.isEmpty()) {
                    if (step < 2) step++ else onFinish(
                        Profile(
                            username.trim(), age.toInt(), sex, heightCm(), weight.toDouble(), goal, diet, place, wtime,
                            wake, sleep, conds.toList(), other.trim()
                        ), consent, server, invite
                    )
                }
            }) { Text(if (step < 2) "Next" else "Build my plan") }
        }
    }
}
