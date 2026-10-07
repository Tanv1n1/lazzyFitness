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

    private fun profileBody(p: Profile, planSource: String): JSONObject {
        val t = PlanEngine.targets(p)
        return Store.profileTo(p).apply {
            remove("other")                       // free text stays on the phone
            if (p.other.isNotBlank()) put("conds", JSONArray(p.conds + "other"))
            put("kcal", t.kcal); put("protein", t.protein); put("plan_source", planSource)
        }
    }

    /** Creates the account on first use, otherwise updates the profile. Returns true on success. */
    fun pushProfile(store: Store, planSource: String): Boolean = runCatching {
        val p = store.profile ?: return false
        if (!store.consent) return false
        if (store.userId == null) {
            val r = call("POST", "/api/register",
                profileBody(p, planSource).put("consent", true).put("invite", store.invite), store)
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
            call("POST", "/api/profile", profileBody(p, planSource), store).code == 200
        }
    }.getOrElse { store.syncNote = "Could not reach the server. Your ticks are saved and will upload later."; false }

    fun syncDay(store: Store, date: LocalDate): Boolean = runCatching {
        if (!store.consent || store.userId == null) return false
        val body = JSONObject().put("day", date.toString()).put("done", JSONArray(store.done(date).toList()))
        call("POST", "/api/sync", body, store).code == 200
    }.getOrDefault(false)

    /** Deletes the server-side account and logs. True also when there is nothing on the server to delete. */
    fun deleteAccount(store: Store): Boolean = runCatching {
        if (store.userId == null) return true
        call("DELETE", "/api/me", null, store).code in listOf(200, 401)
    }.getOrDefault(false)

    /** Asks the backend (which holds the Claude API key) for a personalised plan. Null means use the offline planner. */
    fun aiPlan(store: Store, p: Profile): Plan? = runCatching {
        if (!store.consent || store.userId == null) return null
        val t = PlanEngine.targets(p)
        val targets = JSONObject().put("kcal", t.kcal).put("protein", t.protein)
        val body = JSONObject().put("profile", Store.profileTo(p).put("targets", targets))
        val r = call("POST", "/api/plan", body, store, timeoutMs = 120000)
        if (r.code != 200) return null
        // Slots the AI leaves out fall back to the offline planner inside PlanEngine.dayPlan.
        Store.planFrom(JSONObject(r.body).put("source", "ai"))
    }.getOrNull()
}
