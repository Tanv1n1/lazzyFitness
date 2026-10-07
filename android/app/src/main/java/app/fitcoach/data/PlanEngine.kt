package app.fitcoach.data

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Profile(
    val username: String,
    val age: Int,
    val sex: String,        // male | female | other
    val heightCm: Double,
    val weightKg: Double,
    val goal: String,       // lose | gain | maintain
    val diet: String,       // veg | egg | nonveg
    val place: String,      // home | gym
    val wtime: String,      // morning | evening
    val wake: String,       // HH:MM
    val sleep: String,      // HH:MM
    val conds: List<String>,
    val other: String = "",
)

data class Targets(val kcal: Int, val protein: Int, val waterL: Double, val bmi: Double)

data class Meal(val name: String, val items: List<String>, val skip: List<String>)

/** A plan is a pool of meal options per slot. The day picks one option per slot. */
data class Plan(
    val source: String,                      // "ai" or "local"
    val summary: String,
    val watchlist: List<String>,
    val meals: Map<String, List<Meal>>,
)

/** One tickable item on the timeline. [timeMin] is minutes after midnight of the plan date (can pass 1440). */
data class Step(
    val id: String,
    val kind: String,        // water | workout | meal | sleep
    val timeMin: Int,
    val title: String,
    val kcal: Int? = null,
    val name: String? = null,
    val eat: List<String> = emptyList(),
    val skip: List<String> = emptyList(),
    val note: String? = null,
    val exercises: List<Pair<String, String>> = emptyList(),
)

val STEP_IDS = listOf("water", "workout", "breakfast", "mid", "lunch", "eve", "dinner", "prebed", "sleep")
const val PASS_STEPS = 6

object PlanEngine {

    // ---------- time helpers ----------
    fun hm(s: String): Int {
        val p = s.split(":")
        return p[0].toInt() * 60 + p[1].toInt()
    }

    fun fmt(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        val h = m / 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m % 60, if (h < 12) "AM" else "PM")
    }

    /** 170.18 cm -> (5, 7). The app stores centimetres and shows feet and inches. */
    fun feetInches(cm: Double): Pair<Int, Int> {
        val total = (cm / 2.54).roundToInt()
        return total / 12 to total % 12
    }

    private fun r5(m: Int) = ((m / 5.0).roundToInt()) * 5

    // ---------- targets ----------
    fun targets(p: Profile): Targets {
        val bmr = 10 * p.weightKg + 6.25 * p.heightCm - 5 * p.age +
            when (p.sex) { "male" -> 5.0; "female" -> -161.0; else -> -78.0 }
        val adj = when (p.goal) { "lose" -> -400; "gain" -> 300; else -> 0 }
        val floor = if (p.sex == "female") 1300.0 else 1500.0
        val kcal = ((max(bmr * 1.4 + adj, floor) / 10).roundToInt()) * 10
        val perKg = when {
            "kidney" in p.conds -> 0.8
            p.goal == "maintain" -> 1.2
            else -> 1.6
        }
        val water = (((p.weightKg * 0.035) * 10).roundToInt() / 10.0).coerceIn(2.0, 4.0)
        val h = p.heightCm / 100
        return Targets(kcal, (p.weightKg * perKg).roundToInt(), water, (p.weightKg / (h * h) * 10).roundToInt() / 10.0)
    }

    // ---------- daily schedule, anchored on wake time ----------
    fun schedule(p: Profile): LinkedHashMap<String, Int> {
        val w = hm(p.wake)
        var s = hm(p.sleep)
        if (s <= w) s += 1440
        val k = min(1.0, ((s - w) / 60.0) / 16.0)
        val offsets = if (p.wtime == "morning")
            listOf("workout" to 0.5, "breakfast" to 1.75, "mid" to 4.25, "lunch" to 6.5, "eve" to 10.0)
        else
            listOf("breakfast" to 1.0, "mid" to 4.0, "lunch" to 6.5, "eve" to 9.5, "workout" to 11.0)
        val out = LinkedHashMap<String, Int>()
        out["water"] = w
        var prev = w
        for ((id, o) in offsets) {
            var t = r5(w + (o * 60 * k).roundToInt())
            if (t < prev + 20) t = prev + 20
            out[id] = t
            prev = t
        }
        var dinner = r5(s - 150)
        if (dinner < prev + 45) dinner = prev + 45
        out["dinner"] = dinner
        var pre = r5(s - 45)
        if (pre < dinner + 45) pre = dinner + 45
        if (pre > s - 10) pre = max(dinner + 20, s - 10)
        out["prebed"] = pre
        out["sleep"] = s
        return out
    }

    /** The date a "day" belongs to. A day that runs past midnight still counts as the date it started. */
    fun activeDate(p: Profile, now: java.time.LocalDateTime): LocalDate {
        val sleepMin = schedule(p)["sleep"] ?: 1380
        val minuteOfDay = now.hour * 60 + now.minute
        return if (sleepMin > 1440 && minuteOfDay < sleepMin - 1440 + 60) now.toLocalDate().minusDays(1)
        else now.toLocalDate()
    }

    // ---------- health notes ----------
    val CONDITIONS = listOf(
        "diabetes" to "Diabetes", "thyroid" to "Thyroid", "pcos" to "PCOS / PCOD", "bp" to "High BP",
        "chol" to "High cholesterol", "acidity" to "Acidity / GERD", "lactose" to "Lactose intolerance",
        "gluten" to "Gluten sensitivity", "kidney" to "Kidney issue",
    )

    val NOTES = mapOf(
        "diabetes" to "Spread carbs across meals, pick bhakri over rice and do not skip meals. Share this plan with your doctor.",
        "thyroid" to "If you take thyroid medicine, take it with water 30 to 45 minutes before breakfast, as your doctor advised. Keep soy and raw cabbage or cauliflower modest.",
        "pcos" to "Lean on high-protein, low-sugar meals and strength training. Avoid long gaps between meals.",
        "bp" to "Cut added salt. Papad, pickle, farsan and packaged namkeen are the usual culprits.",
        "chol" to "Keep fried food, bakery items and red meat low. Add oats, sprouts, methi and nuts.",
        "acidity" to "Eat on time, avoid strong tea or coffee on an empty stomach, finish dinner 3 hours before bed.",
        "lactose" to "Dairy is swapped out across the plan. For calcium use ragi, til and leafy greens.",
        "gluten" to "Bhakri (jowar, bajra, ragi), rice and besan replace wheat, rava and pav.",
        "kidney" to "Protein and fluid needs depend on your kidney stage. Treat this plan as a draft and confirm it with your nephrologist before following it.",
    )

    private val EXCLUDE = mapOf(
        "diabetes" to setOf("highgi", "sugar"), "pcos" to setOf("highgi", "sugar"), "bp" to setOf("highsalt"),
        "chol" to setOf("fried"), "acidity" to setOf("spicy"), "lactose" to setOf("dairy"),
        "gluten" to setOf("gluten"), "thyroid" to setOf("soy"), "kidney" to setOf("highsalt"),
    )

    // ---------- Pune meal library ----------
    private class Opt(val n: String, val i: List<String>, val d: String = "veg", val t: Set<String> = emptySet())

    private fun o(n: String, i: List<String>, d: String = "veg", vararg t: String) = Opt(n, i, d, t.toSet())

    private val LIB: Map<String, List<Opt>> = mapOf(
        "breakfast" to listOf(
            o("Kanda poha with peanuts", listOf("1 plate poha, light oil", "2 tbsp roasted peanuts", "1 glass taak"), "veg", "dairy", "highgi"),
            o("Jowar thalipeeth", listOf("2 jowar thalipeeth", "Fresh curd, 1 small bowl", "1 fruit"), "veg", "dairy"),
            o("Moong dal cheela", listOf("2 moong cheela with veggies", "Mint chutney", "1 fruit (guava or papaya)")),
            o("Besan chilla", listOf("2 besan chilla with onion, tomato", "Coriander chutney", "Handful of sprouts")),
            o("Ragi dosa", listOf("2 ragi dosa", "Coconut chutney", "Sambar, 1 small bowl")),
            o("Egg bhurji and bhakri", listOf("2 egg bhurji, less oil", "1 jowar bhakri", "1 fruit"), "egg"),
            o("Masala omelette", listOf("2-egg masala omelette", "2 slices multigrain toast", "Cucumber slices"), "egg", "gluten"),
        ),
        "mid" to listOf(
            o("Fruit and almonds", listOf("1 seasonal fruit (guava, apple, papaya)", "6 almonds")),
            o("Roasted chana", listOf("A fistful of roasted chana", "1 glass coconut water")),
            o("Taak and cucumber", listOf("1 glass taak with jeera", "Cucumber slices"), "veg", "dairy"),
            o("Sprouts chaat", listOf("1 bowl sprouts, onion, tomato, lemon")),
            o("Boiled eggs", listOf("2 boiled eggs", "Black pepper"), "egg"),
        ),
        "lunch" to listOf(
            o("Bhakri, varan, bhaji", listOf("2 jowar or bajra bhakri", "1 bowl varan (dal)", "1 bowl seasonal bhaji", "Kachumber salad")),
            o("Dal-palak thali", listOf("2 bhakri", "Dal palak, 1 bowl", "Carrot-cucumber salad", "Sol kadhi, 1 glass")),
            o("Matki usal meal", listOf("1 bowl matki usal", "2 bhakri", "Salad", "Sol kadhi")),
            o("Paneer bhurji and phulka", listOf("2 phulka", "Paneer bhurji, 1 bowl", "Dal, 1 small bowl", "Salad"), "veg", "dairy", "gluten"),
            o("Amti and rice", listOf("1 small bowl rice", "Amti, 1 bowl", "Usal, 1 bowl", "Salad"), "veg", "highgi"),
            o("Mild chicken sukka", listOf("2 bhakri", "Chicken sukka, 150 g, less oil", "Kachumber salad"), "nonveg", "spicy"),
            o("Grilled fish thali", listOf("Grilled fish, 150 g", "1 small bowl rice", "Sol kadhi", "Salad"), "nonveg", "highgi"),
            o("Egg curry and bhakri", listOf("2-egg curry, light gravy", "2 bhakri", "Salad"), "egg", "spicy"),
        ),
        "eve" to listOf(
            o("Makhana and tea", listOf("A fistful of roasted makhana", "1 cup tea, low sugar")),
            o("Roasted chana chaat", listOf("1 small bowl chana, onion, lemon")),
            o("Fruit and peanuts", listOf("1 fruit", "A small handful of peanuts")),
            o("Boiled corn", listOf("1 boiled corn with lemon and pepper")),
            o("Boiled eggs and cucumber", listOf("2 boiled eggs", "Cucumber"), "egg"),
            o("Grilled chicken tikka", listOf("4 pieces grilled chicken tikka", "Mint chutney"), "nonveg", "spicy"),
        ),
        "dinner" to listOf(
            o("Dal, bhakri and greens", listOf("1 bowl dal", "1 bhakri", "Sauteed palak or methi", "Salad")),
            o("Moong khichdi", listOf("1 bowl moong dal khichdi", "1 tsp ghee", "Kadhi, 1 small bowl"), "veg", "highgi", "dairy"),
            o("Paneer and sauteed veg", listOf("Grilled paneer, 100 g", "Sauteed veggies", "1 phulka"), "veg", "dairy", "gluten"),
            o("Tofu stir-fry", listOf("Tofu, 100 g, stir-fried", "Mixed veggies", "1 bhakri"), "veg", "soy"),
            o("Lauki-chana dal", listOf("Lauki-chana dal, 1 bowl", "1 bhakri", "Salad")),
            o("Chicken soup and veg", listOf("Clear chicken soup", "Grilled chicken, 120 g", "Sauteed veggies"), "nonveg"),
            o("Egg bhurji and bhakri", listOf("2 egg bhurji", "1 bhakri", "Salad"), "egg"),
        ),
        "prebed" to listOf(
            o("Haldi milk", listOf("1 cup warm haldi milk, no sugar"), "veg", "dairy"),
            o("Jeera water and almonds", listOf("Warm jeera or ajwain water", "4 soaked almonds")),
        ),
    )

    // Pune things to skip, per slot. Entries with a condition show first when it applies. "|" means any of.
    private val SKIP: Map<String, List<Pair<String, String>>> = mapOf(
        "breakfast" to listOf(
            "Fruit juice, jam bread, sweet shrikhand" to "diabetes|pcos",
            "Misal pav with extra tarri and farsan" to "acidity|chol",
            "Pickle, papad and extra salt on the side" to "bp",
            "Pav, bread and maida snacks" to "gluten",
            "Milk chai, curd, paneer at the table" to "lactose",
            "Vada pav, kanda bhaji or bun maska from the stall near office" to "",
            "Sugary chai with khari or Marie biscuits" to "",
        ),
        "mid" to listOf(
            "Cold drinks, packaged juice, sugarcane juice" to "diabetes|pcos",
            "Samosa and kachori from the tapri" to "chol",
            "Strong tea or coffee on an empty stomach" to "acidity",
            "Salted chips and salty roasted nuts" to "bp",
            "Bakarwadi, chakli, namkeen packets" to "",
        ),
        "lunch" to listOf(
            "Big portions of white rice" to "diabetes|pcos",
            "Extra tarri, red-oil curries, pickle, papad" to "acidity|bp",
            "Deep-fried sides: bhajji, fried papad, kheema pav" to "chol",
            "Wheat roti and pav, use bhakri instead" to "gluten",
            "Raita, lassi and paneer dishes" to "lactose",
            "Unlimited thali refills of rice and sweets" to "",
            "Misal pav, vada pav or biryani as a quick lunch" to "",
        ),
        "eve" to listOf(
            "Sugary chai, cold coffee, packaged juice" to "diabetes|pcos",
            "Fried snacks: samosa, kachori, batata vada" to "chol",
            "Extra chaat masala and salt" to "bp",
            "Mastani, milkshake, Irani cafe bun maska" to "",
            "Pani puri, sev puri, ragda pattice" to "",
        ),
        "dinner" to listOf(
            "White rice or khichdi in large portions" to "diabetes|pcos",
            "Spicy Kolhapuri or misal-type dinner" to "acidity",
            "Pickles, papad and packet soups" to "bp",
            "Red meat and fried non-veg" to "chol",
            "Late biryani, Chinese bhel or fried rice after 9 pm" to "",
            "Sweet after dinner: shrikhand, gulab jamun, ice cream" to "",
        ),
        "prebed" to listOf(
            "Milk, if it upsets your stomach" to "lactose",
            "Biscuits, chocolate or mithai late at night" to "",
            "Tea or coffee after 6 pm, it hurts sleep" to "",
        ),
    )

    private val SHARE = mapOf("breakfast" to 24, "mid" to 8, "lunch" to 32, "eve" to 10, "dinner" to 22, "prebed" to 4)
    private val SLOT_TITLE = mapOf(
        "breakfast" to "Breakfast", "mid" to "Mid-morning snack", "lunch" to "Lunch",
        "eve" to "Evening snack", "dinner" to "Dinner", "prebed" to "Before bed",
    )

    private fun condMatch(spec: String, conds: List<String>) =
        spec.isEmpty() || spec.split("|").any { it in conds }

    private fun skipFor(slot: String, p: Profile): List<String> {
        val all = SKIP[slot].orEmpty()
        val specific = all.filter { it.second.isNotEmpty() && condMatch(it.second, p.conds) }.map { it.first }
        val generic = all.filter { it.second.isEmpty() }.map { it.first }
        return (specific + generic).take(3)
    }

    private fun dietOk(d: String, diet: String) = when (diet) {
        "veg" -> d == "veg"
        "egg" -> d == "veg" || d == "egg"
        else -> true
    }

    /** Offline planner. Always works, no network. */
    fun localPlan(p: Profile): Plan {
        val excluded = p.conds.flatMap { EXCLUDE[it].orEmpty() }.toSet()
        val meals = LIB.mapValues { (slot, opts) ->
            val ok = opts.filter { dietOk(it.d, p.diet) && it.t.none { t -> t in excluded } }
            val pool = ok.ifEmpty { opts.filter { dietOk(it.d, p.diet) }.take(1) }
            val skip = skipFor(slot, p)
            pool.map { Meal(it.n, it.i, skip) }
        }
        val t = targets(p)
        val goalText = when (p.goal) { "lose" -> "lose fat steadily"; "gain" -> "build muscle"; else -> "hold your weight" }
        val watch = (p.conds.flatMap { c ->
            SKIP.values.flatten().filter { it.second.split("|").contains(c) }.map { it.first }
        } + SKIP.values.flatten().filter { it.second.isEmpty() }.map { it.first }).distinct().take(6)
        return Plan("local", "About ${t.kcal} kcal and ${t.protein} g protein a day to $goalText.", watch, meals)
    }

    // ---------- workouts ----------
    private val EX = mapOf(
        "full" to mapOf(
            "home" to listOf("Bodyweight squats" to "3 x 15", "Push-ups (knees ok)" to "3 x 10", "Backpack rows" to "3 x 12", "Glute bridges" to "3 x 15", "Plank" to "3 x 30 s"),
            "gym" to listOf("Goblet squat" to "3 x 12", "Dumbbell bench press" to "3 x 10", "Seated cable row" to "3 x 12", "Romanian deadlift" to "3 x 10", "Plank" to "3 x 40 s"),
        ),
        "push" to mapOf(
            "home" to listOf("Push-ups" to "4 x 10", "Pike push-ups" to "3 x 8", "Chair dips" to "3 x 12", "Plank shoulder taps" to "3 x 20"),
            "gym" to listOf("Dumbbell bench press" to "4 x 8", "Overhead press" to "3 x 10", "Incline press" to "3 x 10", "Triceps pushdown" to "3 x 12"),
        ),
        "pull" to mapOf(
            "home" to listOf("Backpack rows" to "4 x 12", "Superman hold" to "3 x 30 s", "Towel curls" to "3 x 12", "Dead bug" to "3 x 12"),
            "gym" to listOf("Lat pulldown" to "4 x 10", "Seated cable row" to "3 x 10", "Face pull" to "3 x 15", "Dumbbell curl" to "3 x 12"),
        ),
        "legs" to mapOf(
            "home" to listOf("Squats" to "4 x 15", "Reverse lunges" to "3 x 12 each", "Glute bridges" to "4 x 15", "Calf raises" to "3 x 20"),
            "gym" to listOf("Back squat or leg press" to "4 x 10", "Romanian deadlift" to "3 x 10", "Walking lunges" to "3 x 12 each", "Calf raises" to "3 x 15"),
        ),
        "walk" to mapOf(
            "home" to listOf("Brisk walk" to "30 min, can talk but not sing", "Stretch" to "5 min"),
            "gym" to listOf("Brisk walk or cycle" to "30 min, can talk but not sing", "Stretch" to "5 min"),
        ),
        "rest" to mapOf(
            "home" to listOf("Easy stretch or yoga" to "15 min", "Slow walk" to "15 min"),
            "gym" to listOf("Easy stretch or yoga" to "15 min", "Slow walk" to "15 min"),
        ),
    )

    private val WEEK = mapOf(
        "lose" to listOf("full", "walk", "full", "walk", "full", "walk", "rest"),
        "gain" to listOf("push", "legs", "walk", "pull", "legs", "full", "rest"),
        "maintain" to listOf("full", "walk", "full", "rest", "full", "walk", "rest"),
    )

    private val WTITLE = mapOf(
        "full" to "Full body strength", "push" to "Push day", "pull" to "Pull day", "legs" to "Leg day",
        "walk" to "Cardio walk", "rest" to "Active rest",
    )

    private fun workoutFor(p: Profile, date: LocalDate): Step {
        val key = WEEK.getValue(p.goal)[date.dayOfWeek.value - 1]
        val ex = EX.getValue(key).getValue(p.place).toMutableList()
        if (key == "walk" && date.dayOfWeek == DayOfWeek.SATURDAY)
            ex.add(0, "Weekend option" to "Vetal Tekdi or a hill walk with friends")
        val notes = mutableListOf<String>()
        if ("bp" in p.conds) notes += "Breathe out on effort, never hold your breath, skip max lifts."
        if ("diabetes" in p.conds) notes += "Carry a quick sugar source. Stop and eat if you feel dizzy."
        if ("acidity" in p.conds) notes += "Wait 90 minutes after a big meal."
        if ("kidney" in p.conds) notes += "Keep intensity easy until your doctor clears it."
        val mins = if (key == "rest") 30 else 40
        return Step(
            id = "workout", kind = "workout", timeMin = 0, title = WTITLE.getValue(key),
            name = "$mins min", exercises = ex, note = notes.joinToString(" ").ifEmpty { null },
        )
    }

    // ---------- the day ----------
    fun dayPlan(p: Profile, plan: Plan, date: LocalDate, swaps: (String) -> Int = { 0 }): List<Step> {
        val sch = schedule(p)
        val t = targets(p)
        val idx = date.toEpochDay().toInt()
        val steps = ArrayList<Step>()

        val waterNote = buildString {
            if ("kidney" in p.conds) append("Follow the fluid limit your doctor gave you. ")
            if ("thyroid" in p.conds) append("Thyroid tablet? Take it with this water, as your doctor advised. ")
            append("Before tea, before your phone.")
        }
        steps += Step("water", "water", sch.getValue("water"), "Drink 400 ml of water",
            note = waterNote.trim(), name = "Daily target about ${t.waterL} L")

        for ((slot, time) in sch) {
            when (slot) {
                "workout" -> steps += workoutFor(p, date).copy(timeMin = time)
                in SLOT_TITLE -> {
                    val pool = plan.meals[slot].orEmpty().ifEmpty { localPlan(p).meals[slot].orEmpty() }
                    val m = pool[((idx + STEP_IDS.indexOf(slot) + swaps(slot)) % pool.size + pool.size) % pool.size]
                    val kcal = ((t.kcal * (SHARE[slot] ?: 0) / 100.0) / 10).roundToInt() * 10
                    steps += Step(slot, "meal", time, SLOT_TITLE.getValue(slot), kcal, m.name, m.items, m.skip)
                }
                "sleep" -> steps += Step("sleep", "sleep", time, "In bed, lights out",
                    note = "Screens down 20 minutes before. Aim for the same time every night.")
            }
        }
        return steps.sortedBy { it.timeMin }
    }

    fun healthNotes(p: Profile): List<String> = p.conds.mapNotNull { NOTES[it] }
}
