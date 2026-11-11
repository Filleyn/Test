package com.trailguidegps.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A packing list suggested for a route: the essentials every time, plus what the nights out,
 * the heat, dry camps, the season and the climbing call for. A starting point to edit, not a
 * guarantee that nothing is missing.
 */
object Checklist {
    data class Item(val id: String, val group: String, val label: String, val why: String? = null)

    /** What the list is worked out from. */
    data class Trip(
        val days: Int,
        val nights: Int,
        val heat: WaterCarry.Heat,
        /** The most water to carry at once on this route, or null when it couldn't be worked out. */
        val carryL: Double?,
        /** A night at a camp with no reliable water. */
        val dryCamp: Boolean,
        val month: Int,
        val highFt: Double,
        /** The most climb or descent in one day, in feet. */
        val steepestDayFt: Double,
    )

    const val ESSENTIALS = "Essentials"
    const val WATER_FOOD = "Water and food"
    const val OVERNIGHT = "Overnight"
    const val CONDITIONS = "For this trip"
    const val YOURS = "Your items"
    val GROUPS = listOf(ESSENTIALS, WATER_FOOD, OVERNIGHT, CONDITIONS, YOURS)

    fun suggest(t: Trip): List<Item> {
        val out = ArrayList<Item>()
        fun add(id: String, group: String, label: String, why: String? = null) { out += Item(id, group, label, why) }
        val heat = t.heat.label.lowercase()

        add("phone", ESSENTIALS, "Phone with these maps downloaded")
        add("power", ESSENTIALS, "Power bank and cable")
        add("map", ESSENTIALS, "Paper map and compass")
        add("headlamp", ESSENTIALS, "Headlamp and spare batteries")
        add("firstaid", ESSENTIALS, "First-aid kit with blister care")
        add("knife", ESSENTIALS, "Knife or multitool, repair tape")
        add("fire", ESSENTIALS, "Lighter or matches, fire starter")
        add("whistle", ESSENTIALS, "Whistle")
        add("bivy", ESSENTIALS, "Emergency blanket or bivy")
        add("sun", ESSENTIALS, "Sun protection: hat, sunglasses, sunscreen")
        add("rain", ESSENTIALS, "Rain jacket")
        add("warm", ESSENTIALS, "Warm layer")
        add("keys", ESSENTIALS, "ID, car key, some cash")
        add("permit", ESSENTIALS, "Permit or parking pass", "Check with the land manager; put details in the route notes.")
        add("plan", ESSENTIALS, "Trip plan sent to someone at home")

        add("bottles", WATER_FOOD, t.carryL?.let { "Bottles or a bladder for at least ${WaterCarry.liters(it)}" } ?: "Water bottles or a bladder",
            t.carryL?.let { "The most to carry at once on this route in $heat weather." })
        add("filter", WATER_FOOD, "Water filter or treatment")
        if (t.heat >= WaterCarry.Heat.WARM) add("electrolytes", WATER_FOOD, "Electrolytes", "It's planned as $heat.")
        add("food", WATER_FOOD, "Lunch and snacks for ${t.days} day" + (if (t.days == 1) "" else "s"))
        add("extrafood", WATER_FOOD, "A little extra food")

        if (t.nights > 0) {
            val n = "${t.nights} night" + (if (t.nights == 1) "" else "s")
            add("tent", OVERNIGHT, "Tent or shelter, with stakes")
            add("bag", OVERNIGHT, "Sleeping bag")
            add("pad", OVERNIGHT, "Sleeping pad")
            add("stove", OVERNIGHT, "Stove, fuel and lighter")
            add("pot", OVERNIGHT, "Pot, mug and spoon")
            add("meals", OVERNIGHT, "Dinners and breakfasts for $n")
            add("bear", OVERNIGHT, "Bear canister or a hang kit", "Rules differ by area; check before you go.")
            add("trowel", OVERNIGHT, "Trowel, toilet paper, hand sanitizer")
            add("personal", OVERNIGHT, "Toothbrush and personal items")
            add("sleepclothes", OVERNIGHT, "Dry clothes to sleep in")
            if (t.dryCamp) add("drycamp", CONDITIONS, "Extra water containers for a dry camp",
                "A camp on this plan has no reliable water: about ${WaterCarry.liters(WaterCarry.CAMP_L)} for the night, carried in.")
        }
        if (t.heat == WaterCarry.Heat.COOL) {
            add("gloves", CONDITIONS, "Warm hat and gloves", "It's planned as cool.")
            add("insulation", CONDITIONS, "Extra insulating layer", "It's planned as cool.")
        }
        if (t.heat == WaterCarry.Heat.HOT) add("sunshirt", CONDITIONS, "Sun shirt or sun umbrella", "It's planned as hot.")
        if (t.month in 6..8) add("bugs", CONDITIONS, "Insect repellent or a head net", "Mosquito season in much of the mountains.")
        if (t.highFt > 6000 && (t.month >= 10 || t.month <= 6)) add("traction", CONDITIONS, "Traction for snow (microspikes)",
            "The route goes above %,d ft, where snow can linger this time of year.".format((t.highFt / 500).toInt() * 500))
        if (t.steepestDayFt >= 2500) add("poles", CONDITIONS, "Trekking poles",
            "A day with about %,d ft of climb or descent.".format((t.steepestDayFt / 100).toInt() * 100))
        return out
    }

    /** For the trip plan: what's ticked as packed, in list order, or null when nothing is. */
    fun packedLine(items: List<Item>, state: ChecklistStore.State): String? {
        val all = items.filter { it.id !in state.removed } + state.added.map { Item(it.first, YOURS, it.second) }
        val packed = all.filter { it.id in state.checked }.map { it.label }
        return if (packed.isEmpty()) null else packed.joinToString("; ") + "."
    }
}

/** What's ticked, removed and added on each route's checklist, kept in checklists.json. */
class ChecklistStore(context: Context) {
    data class State(
        val checked: Set<String> = emptySet(),
        /** Suggested items you took off the list. */
        val removed: Set<String> = emptySet(),
        /** Your own items, as (id, label). */
        val added: List<Pair<String, String>> = emptyList(),
    )

    private val file = File(context.filesDir, FILE)
    private val all: MutableMap<String, State> = load()

    /** Reads the file again, after a restore. */
    @Synchronized fun reload() { val fresh = load(); all.clear(); all.putAll(fresh) }

    @Synchronized fun get(routeId: String): State = all[routeId] ?: State()

    @Synchronized fun put(routeId: String, s: State) {
        if (s == State()) all.remove(routeId) else all[routeId] = s
        save()
    }

    @Synchronized fun drop(routeId: String) { if (all.remove(routeId) != null) save() }

    private fun save() {
        runCatching {
            val o = JSONObject()
            all.forEach { (id, s) ->
                o.put(id, JSONObject().put("checked", JSONArray(s.checked.toList())).put("removed", JSONArray(s.removed.toList()))
                    .put("added", JSONArray().apply { s.added.forEach { (i, l) -> put(JSONObject().put("id", i).put("label", l)) } }))
            }
            SafeFile.write(file, o.toString())
        }
    }

    private fun load(): MutableMap<String, State> = runCatching {
        val o = JSONObject(file.readText())
        val out = HashMap<String, State>()
        for (id in o.keys()) {
            val s = o.getJSONObject(id)
            fun strings(key: String) = s.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            val added = s.optJSONArray("added")?.let { a -> List(a.length()) { a.getJSONObject(it).let { x -> x.getString("id") to x.getString("label") } } }.orEmpty()
            out[id] = State(strings("checked").toSet(), strings("removed").toSet(), added)
        }
        out
    }.getOrDefault(HashMap())

    companion object {
        const val FILE = "checklists.json"
    }
}
