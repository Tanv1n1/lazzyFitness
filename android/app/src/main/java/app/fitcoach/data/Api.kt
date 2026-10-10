package app.fitcoach.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Talks to the Lazy Fitness backend. Call from a background thread (Dispatchers.IO or a worker).
 * Nothing here runs unless the user ticked the consent box in onboarding.
 */
object Api {
    private class Res(val code: Int, val body: String)

    private fun call(method: String, path: String, body: JSONObject?, store: Store, timeoutMs: Int = 15000): Res {
        val conn = URL(store.serverUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            store.userId?.let { conn.setRequestProperty("X-User-Id", it) }
            store.userKey?.let { conn.setRequestProperty("X-User-Key", it) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Res(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun profileBody(p: Profile, planSource: String, avoid: List<String>): JSONObject {
        val t = PlanEngine.targets(p)
        return Store.profileTo(p).apply {
            remove("other")                       // free text stays on the phone
            if (p.other.isNotBlank()) put("conds", JSONArray(p.conds + "other"))
            put("kcal", t.kcal); put("protein", t.protein); put("plan_source", planSource); put("avoid", JSONArray(avoid))
        }
    }

    /** Creates the account on first use, otherwise updates the profile. Returns true on success. */
    fun pushProfile(store: Store, planSource: String): Boolean = runCatching {
        val p = store.profile ?: return false
        if (!store.consent) return false
        if (store.userId == null) {
            val r = call("POST", "/api/register",
                profileBody(p, planSource, store.avoid).put("consent", true).put("invite", store.invite), store)
            if (r.code != 200) {
                store.syncNote = runCatching { JSONObject(r.body).getString("error") }.getOrDefault("Server said no (${r.code})")
                    .replaceFirstChar { it.uppercase() }
                return false
            }
            val j = JSONObject(r.body)
            store.userId = j.getString("user_id"); store.userKey = j.getString("user_key")
            store.syncNote = ""
            true
        } else {
            call("POST", "/api/profile", profileBody(p, planSource, store.avoid), store).code == 200
        }
    }.getOrElse { store.syncNote = "Could not reach the server. Your ticks are saved and will upload later."; false }

    /** Uploads one day: ticks, skipped / replaced / noted steps, and water glasses. */
    fun syncDay(store: Store, date: LocalDate): Boolean = runCatching {
        if (!store.consent || store.userId == null) return false
        val entries = JSONObject()
        store.entries(date).forEach { (id, e) -> entries.put(id, Store.entryTo(e)) }
        val body = JSONObject().put("day", date.toString()).put("done", JSONArray(store.done(date).toList()))
            .put("entries", entries).put("water", store.water(date))
        call("POST", "/api/sync", body, store).code == 200
    }.getOrDefault(false)

    /** What to tell the person when an AI route did not answer 200. */
    private fun aiFailure(code: Int, body: String): String = when {
        code == 429 && body.contains("ai_busy") -> "The AI is busy right now. Try again in a minute."
        code == 429 -> "You have used today's AI help. Try again tomorrow."
        code == 503 && body.contains("not configured") -> "AI is not switched on for this server yet."
        code == 503 && body.contains("refused") -> "The AI service is not answering right now, and the server owner needs to look at it. Try again later."
        code == 503 -> "The AI is not available right now. Try again in a minute."
        else -> runCatching { JSONObject(body).getString("error") }.getOrDefault("The AI could not do that.")
    }

    class MealReading(val items: List<String>, val kcal: Int?, val note: String, val error: String? = null)

    /** Asks the backend to read a meal photo (base64 JPEG). The photo is not stored. [MealReading.error] is set on failure. */
    fun readMeal(store: Store, imageB64: String): MealReading {
        fun fail(msg: String) = MealReading(emptyList(), null, "", msg)
        if (!store.consent || store.userId == null) return fail("Turn on sharing under Me, Edit profile to read photos.")
        return runCatching {
            val r = call("POST", "/api/meal-photo", JSONObject().put("image", imageB64), store, timeoutMs = 120000)
            when (r.code) {
                200 -> {
                    val j = JSONObject(r.body)
                    val arr = j.getJSONArray("items")
                    MealReading((0 until arr.length()).map { arr.getString(it) }, if (j.has("kcal")) j.getInt("kcal") else null, j.optString("note", ""))
                }
                else -> fail(aiFailure(r.code, r.body))
            }
        }.getOrElse { fail("Could not reach the server.") }
    }

    /** Deletes the server-side account and logs. True also when there is nothing on the server to delete. */
    fun deleteAccount(store: Store): Boolean = runCatching {
        if (store.userId == null) return true
        call("DELETE", "/api/me", null, store).code in listOf(200, 401)
    }.getOrDefault(false)

    class StepAi(val revision: Revision?, val kcal: Int?, val tip: String, val error: String? = null)

    private fun stepJson(s: Step) = JSONObject().put("id", s.id).put("kind", s.kind).put("title", s.title)
        .put("name", s.name ?: "").put("eat", JSONArray(s.eat)).put("skip", JSONArray(s.skip))
        .put("exercises", JSONArray(s.exercises.map { JSONArray(listOf(it.first, it.second)) }))
        .apply { s.kcal?.let { put("kcal", it) } }

    /**
     * Asks the AI about one step. [mode]: "change" rewrites [step] to match [request]; "skipped" (request is the reason) and
     * "replaced" (request is what was eaten instead) return a tip, and for "replaced" the calories of what was eaten.
     * [StepAi.error] says why nothing came back, in words for the person.
     */
    fun stepAi(store: Store, mode: String, step: Step, request: String): StepAi {
        fun fail(msg: String) = StepAi(null, null, "", msg)
        val p = store.profile ?: return fail("Set up your profile first.")
        if (!store.consent || store.userId == null) return fail("Turn on sharing under Me, Edit profile, so the AI can help.")
        return runCatching {
            val t = PlanEngine.targets(p)
            val body = JSONObject().put("mode", mode).put("request", request).put("step", stepJson(step))
                .put("profile", Store.profileTo(p).apply { remove("username") }
                    .put("targets", JSONObject().put("kcal", t.kcal).put("protein", t.protein)))
            val r = call("POST", "/api/step-ai", body, store, timeoutMs = 150000)
            // The server sends the person's "foods I don't want" list on every answer, even a failed one.
            runCatching { JSONObject(r.body).optJSONArray("avoid") }.getOrNull()?.let { a -> store.avoid = (0 until a.length()).map { a.getString(it) } }
            when (r.code) {
                200 -> {
                    val j = JSONObject(r.body)
                    StepAi(j.optJSONObject("revision")?.let { Store.revisionFrom(it) }, if (j.has("kcal")) j.getInt("kcal") else null, j.optString("tip", ""))
                }
                else -> fail(aiFailure(r.code, r.body))
            }
        }.getOrElse { fail("Could not reach the server.") }
    }

    /** Asks the backend (which holds the AI key) for a personalised plan. Null means use the offline planner. */
    fun aiPlan(store: Store, p: Profile): Plan? = runCatching {
        if (!store.consent || store.userId == null) return null
        val t = PlanEngine.targets(p)
        val targets = JSONObject().put("kcal", t.kcal).put("protein", t.protein)
        // No username: the AI provider only needs body stats, goal, diet and health flags.
        val body = JSONObject().put("profile", Store.profileTo(p).apply { remove("username") }.put("targets", targets))
        val r = call("POST", "/api/plan", body, store, timeoutMs = 120000)
        if (r.code != 200) return null
        // Slots the AI leaves out fall back to the offline planner inside PlanEngine.dayPlan.
        Store.planFrom(JSONObject(r.body).put("source", "ai"))
    }.getOrNull()
}
