package app.fitcoach.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * What happened to a step besides ticking it.
 * [status]: "skipped" (not eaten or done, [text] holds the reason), "replaced" (something else was eaten or done, [text] says what),
 * or "note" (a change request, nothing else changed). [note] is a free-text request for the coach on any of them.
 */
data class Entry(val status: String, val text: String = "", val kcal: Int? = null, val note: String = "")

data class Week(val daysLogged: Int, val avgPct: Int, val skipped: Int, val replaced: Int, val mostSkipped: String?)

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

    // ---- what happened to a step besides ticking it: { "2026-10-07": { "lunch": {"s":"replaced","text":"..."} } } ----
    private fun entryLog(): JSONObject =
        runCatching { JSONObject(sp.getString("entries", "{}")!!) }.getOrDefault(JSONObject())

    fun entries(date: LocalDate): Map<String, Entry> {
        val day = entryLog().optJSONObject(date.toString()) ?: return emptyMap()
        return day.keys().asSequence().associateWith { id ->
            val o = day.getJSONObject(id)
            Entry(o.optString("s", "note"), o.optString("text", ""), if (o.has("kcal")) o.getInt("kcal") else null, o.optString("note", ""))
        }
    }

    fun setEntry(date: LocalDate, id: String, e: Entry?) {
        val all = entryLog()
        val day = all.optJSONObject(date.toString()) ?: JSONObject()
        if (e == null) day.remove(id) else day.put(id, entryTo(e))
        all.put(date.toString(), day)
        sp.edit().putString("entries", all.toString()).apply()
    }

    // ---- water glasses (250 ml each) ----
    fun water(date: LocalDate): Int = sp.getInt("water_$date", 0)
    fun setWater(date: LocalDate, glasses: Int) = sp.edit().putInt("water_$date", glasses.coerceIn(0, 40)).apply()

    /** The last 7 days (including [today]) in numbers, for the Me tab. */
    fun week(today: LocalDate): Week {
        val counts = doneCounts()
        val days = (0..6).map { today.minusDays(it.toLong()) }
        val total = days.sumOf { counts[it] ?: 0 }
        val skipped = days.flatMap { d -> entries(d).filter { it.value.status == "skipped" }.keys }
        return Week(
            daysLogged = days.count { (counts[it] ?: 0) > 0 },
            avgPct = (100.0 * total / (7 * STEP_IDS.size)).roundToInt(),
            skipped = skipped.size,
            replaced = days.sumOf { d -> entries(d).count { it.value.status == "replaced" } },
            mostSkipped = skipped.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
        )
    }

    // ---- meal swaps ----
    fun swaps(date: LocalDate, slot: String): Int = sp.getInt("swap_${date}_$slot", 0)
    fun bumpSwap(date: LocalDate, slot: String) =
        sp.edit().putInt("swap_${date}_$slot", swaps(date, slot) + 1).apply()

    fun clearAll() = sp.edit().clear().apply()

    // ---- JSON ----
    companion object {
        fun entryTo(e: Entry) = JSONObject().put("s", e.status).put("text", e.text).put("note", e.note)
            .apply { e.kcal?.let { put("kcal", it) } }

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
