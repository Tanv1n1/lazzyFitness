package app.fitcoach.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.fitcoach.data.Api
import app.fitcoach.data.Entry
import app.fitcoach.data.Revision
import app.fitcoach.data.Step
import app.fitcoach.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val REASONS = listOf("Not hungry", "Ate out", "No time", "Felt unwell", "Other").map { it to it }

/**
 * One dialog for the three things you can tell the app about a step besides ticking it.
 * [mode] is "skip" (with a reason), "replace" (what you had instead, optionally read from a photo)
 * or "note" (a change request). For a meal or workout the AI rewrites that one step to match the request and [onSave] also gets
 * the [Revision]; for anything else, or if the AI is unavailable, the request is kept as a note for the coach.
 * [onSave] gets the record, the tick to apply (null leaves the tick alone) and the rewrite, if any.
 */
@Composable
fun StepDialog(
    store: Store,
    step: Step,
    mode: String,
    existing: Entry?,
    onDismiss: () -> Unit,
    onSave: (Entry, Boolean?, Revision?) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable { mutableStateOf(existing?.takeIf { it.status == "skipped" }?.text ?: "") }
    var text by rememberSaveable { mutableStateOf(existing?.takeIf { it.status == "replaced" }?.text ?: "") }
    var kcal by rememberSaveable { mutableStateOf(existing?.takeIf { it.status == "replaced" }?.kcal?.toString() ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var offerNote by remember { mutableStateOf(false) }          // the AI failed: let the request go to the coach as a note
    val rewrites = step.kind == "meal" || step.kind == "workout"

    fun noteEntry(changedTo: String? = null, tip: String = ""): Entry {
        val status = existing?.status ?: "note"
        val text = if (status == "note" && changedTo != null) "Changed to: $changedTo" else existing?.text ?: ""
        return Entry(status, text, existing?.kcal, note.trim(), tip.ifBlank { existing?.tip ?: "" })
    }

    fun analyse(uri: Uri?) {
        if (uri == null) return
        busy = true
        msg = ""
        scope.launch {
            val b64 = withContext(Dispatchers.IO) { jpegBase64(ctx, uri) }
            if (b64 == null) {
                msg = "Could not read that photo."
                busy = false
                return@launch
            }
            val r = withContext(Dispatchers.IO) { Api.readMeal(store, b64) }
            busy = false
            if (r.error != null) {
                msg = r.error + " You can type what you ate instead."
                return@launch
            }
            text = r.items.joinToString(", ")
            r.kcal?.let { kcal = it.toString() }
            msg = r.note.ifBlank { "Estimated by AI from the photo. Check it and edit if it is wrong." }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { analyse(it) }
    val photoUri = remember {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(dir, "meal.jpg"))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) analyse(photoUri) }

    val title = when (mode) {
        "skip" -> "Skip ${step.title.lowercase()}?"
        "replace" -> if (step.kind == "meal") "What did you eat instead?" else "What did you do instead?"
        else -> if (!rewrites) "Note for your coach" else if (step.kind == "meal") "Change this meal" else "Change this workout"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },       // not while the AI is working: its answer would land after you left
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    "skip" -> {
                        Text("Why? (optional)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Chips(REASONS, setOf(reason)) { reason = if (reason == it) "" else it }
                    }
                    "replace" -> {
                        OutlinedTextField(text, { text = it.take(160) }, label = { Text("What you had") },
                            minLines = 2, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(kcal, { kcal = it.filter(Char::isDigit).take(4) }, label = { Text("Calories, if you know (optional)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth())
                        if (step.kind == "meal") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = !busy, onClick = {
                                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Text("Choose photo") }
                                OutlinedButton(enabled = !busy, onClick = { camera.launch(photoUri) }) { Text("Take photo") }
                            }
                            Text("The photo is sent to the AI service to read the food. It is not saved.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Reading the photo...", fontSize = 13.sp)
                        }
                        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text(
                            if (rewrites) "Say what should change, for example: no paneer, or a lighter dinner. The AI rewrites just this one. " +
                                "Say \"I don't like bhakri\" and it is never suggested again."
                            else "Leave a note for your coach about this step.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(note, { note = it.take(200) }, label = { Text("Your request") },
                            minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("The AI is rewriting this. It can take up to a minute.", fontSize = 13.sp)
                        }
                        if (offerNote) OutlinedButton(onClick = { onSave(noteEntry(), null, null) }) { Text("Save as a note for my coach only") }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when (mode) {
                    "skip" -> onSave(Entry("skipped", reason, null, existing?.note ?: ""), false, null)
                    "replace" -> {
                        if (text.isBlank()) {
                            error = "Say what you had."
                        } else {
                            onSave(Entry("replaced", text.trim(), kcal.toIntOrNull()?.coerceIn(0, 3000), existing?.note ?: ""), true, null)
                        }
                    }
                    else -> {
                        if (note.isBlank()) {
                            error = "Write your request first."
                        } else if (!rewrites) {
                            onSave(noteEntry(), null, null)
                        } else {
                            busy = true
                            error = ""
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Api.stepAi(store, "change", step, note.trim()) }
                                busy = false
                                if (r.error != null || r.revision == null) {
                                    error = r.error ?: "The AI could not do that."
                                    offerNote = true
                                } else {
                                    onSave(noteEntry(r.revision.name ?: r.revision.title, r.tip), null, r.revision.copy(why = note.trim()))
                                }
                            }
                        }
                    }
                }
            }) { Text(if (mode == "note" && rewrites) "Rewrite" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shrinks a photo to at most 1024 pixels on its long edge and returns it as base64 JPEG, a few hundred KB.
 * Null if the photo cannot be read.
 */
private fun jpegBase64(ctx: Context, uri: Uri, maxSide: Int = 1024): String? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        val out = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
        else bmp
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()
}
