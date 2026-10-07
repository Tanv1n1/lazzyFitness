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
 * or "note" (a change request for your coach). [onSave] gets the record and the tick to apply (null leaves the tick alone).
 */
@Composable
fun StepDialog(
    store: Store,
    step: Step,
    mode: String,
    existing: Entry?,
    onDismiss: () -> Unit,
    onSave: (Entry, Boolean?) -> Unit,
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
                msg = r.error
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
        else -> "Ask for a change"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                        Text("Tell your coach what should change, for example: no paneer, or a lighter dinner.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(note, { note = it.take(200) }, label = { Text("Your request") },
                            minLines = 3, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when (mode) {
                    "skip" -> onSave(Entry("skipped", reason, null, existing?.note ?: ""), false)
                    "replace" -> {
                        if (text.isBlank()) {
                            error = "Say what you had."
                        } else {
                            onSave(Entry("replaced", text.trim(), kcal.toIntOrNull()?.coerceIn(0, 3000), existing?.note ?: ""), true)
                        }
                    }
                    else -> {
                        if (note.isBlank()) {
                            error = "Write your request first."
                        } else {
                            onSave(Entry(existing?.status ?: "note", existing?.text ?: "", existing?.kcal, note.trim()), null)
                        }
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
