package app.fitcoach.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Everything the app remembers lives in one SharedPreferences file on the phone. */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("fitcoach", Context.MODE_PRIVATE)

    var consent: Boolean
        get() = sp.getBoolean("consent", false)
        set(v) = sp.edit().putBoolean("consent", v).apply()

    /** Server address and invite code are entered by the tester, so the backend URL can change without a new APK. */
    var serverUrl: String
        get() = sp.getString("server_url", null) ?: app.fitcoach.BuildConfig.API_BASE
        set(v) {
            val url = v.trim().trimEnd('/')
            if (url != serverUrl) { userId = null; userKey = null }   // the login belongs to the old server
            sp.edit().putString("server_url", url).apply()
        }

    var invite: String
        get() = sp.getString("invite", "") ?: ""
        set(v) = sp.edit().putString("invite", v.trim()).apply()

    /** Last sync problem in plain words, shown on the Me tab. Empty when all is well. */
    var syncNote: String
        get() = sp.getString("sync_note", "") ?: ""
        set(v) = sp.edit().putString("sync_note", v).apply()

    var userId: String?
        get() = sp.getString("user_id", null)
        set(v) = sp.edit().putString("user_id", v).apply()

    var userKey: String?
        get() = sp.getString("user_key", null)
        set(v) = sp.edit().putString("user_key", v).apply()

    var profile: Profile?
        get() = sp.getString("profile", null)?.let { runCatching { profileFrom(JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit().putString("profile", v?.let { profileTo(it).toString() }).apply()

    var plan: Plan?
        get() = sp.getString("plan", null)?.let { runCatching { planFrom(JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit().putString("plan", v?.let { planTo(it).toString() }).apply()

    // ---- daily ticks: { "2026-10-07": ["water","lunch"] } ----
    private fun logs(): JSONObject = runCatching { JSONObject(sp.getString("log", "{}")!!) }.getOrDefault(JSONObject())

    fun done(date: LocalDate): Set<String> {
        val arr = logs().optJSONArray(date.toString()) ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun setDone(date: LocalDate, id: String, value: Boolean) {
        val all = logs()
        val cur = done(date).toMutableSet()
        if (value) cur += id else cur -= id
        all.put(date.toString(), JSONArray(STEP_IDS.filter { it in cur }))
        sp.edit().putString("log", all.toString()).apply()
    }

    fun doneCounts(): Map<LocalDate, Int> {
        val all = logs()
        return all.keys().asSequence().associate { LocalDate.parse(it) to all.getJSONArray(it).length() }
    }

    /** Consecutive days at PASS_STEPS or more, ending today or yesterday. */
    fun streak(today: LocalDate): Int {
        val counts = doneCounts()
        var d = today
        if ((counts[d] ?: 0) < PASS_STEPS) d = d.minusDays(1)
        var n = 0
        while ((counts[d] ?: 0) >= PASS_STEPS) { n++; d = d.minusDays(1) }
        return n
    }

    // ---- meal swaps ----
    fun swaps(date: LocalDate, slot: String): Int = sp.getInt("swap_${date}_$slot", 0)
    fun bumpSwap(date: LocalDate, slot: String) =
        sp.edit().putInt("swap_${date}_$slot", swaps(date, slot) + 1).apply()

    fun clearAll() = sp.edit().clear().apply()

    // ---- JSON ----
    companion object {
        fun profileTo(p: Profile) = JSONObject().apply {
            put("username", p.username); put("age", p.age); put("sex", p.sex)
            put("height_cm", p.heightCm); put("weight_kg", p.weightKg); put("goal", p.goal)
            put("diet", p.diet); put("place", p.place); put("wtime", p.wtime)
            put("wake", p.wake); put("sleep", p.sleep); put("conds", JSONArray(p.conds)); put("other", p.other)
        }

        fun profileFrom(j: JSONObject) = Profile(
            username = j.getString("username"), age = j.getInt("age"), sex = j.getString("sex"),
            heightCm = j.getDouble("height_cm"), weightKg = j.getDouble("weight_kg"), goal = j.getString("goal"),
            diet = j.getString("diet"), place = j.getString("place"), wtime = j.getString("wtime"),
            wake = j.getString("wake"), sleep = j.getString("sleep"),
            conds = j.getJSONArray("conds").let { a -> (0 until a.length()).map { a.getString(it) } },
            other = j.optString("other", ""),
        )

        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

        fun planTo(p: Plan) = JSONObject().apply {
            put("source", p.source); put("summary", p.summary); put("watchlist", JSONArray(p.watchlist))
            put("meals", JSONObject().also { m ->
                p.meals.forEach { (slot, list) ->
                    m.put(slot, JSONArray().also { arr ->
                        list.forEach { meal ->
                            arr.put(JSONObject().put("name", meal.name)
                                .put("items", JSONArray(meal.items)).put("skip", JSONArray(meal.skip)))
                        }
                    })
                }
            })
        }

        fun planFrom(j: JSONObject): Plan {
            val m = j.getJSONObject("meals")
            val meals = m.keys().asSequence().associateWith { slot ->
                val arr = m.getJSONArray(slot)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Meal(o.getString("name"), strings(o.optJSONArray("items")), strings(o.optJSONArray("skip")))
                }
            }
            return Plan(j.optString("source", "local"), j.optString("summary", ""), strings(j.optJSONArray("watchlist")), meals)
        }
    }
}
