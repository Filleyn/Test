package com.offerscorer.core

import kotlin.math.max
import kotlin.math.roundToInt

enum class JobType { RIDE, DELIVERY }

enum class Verdict(val label: String) { GOOD("Take it"), BORDERLINE("Borderline"), SKIP("Skip") }

/** One ride or delivery offer as read from the screen or typed in. Times in minutes, distances in miles. */
data class Offer(
    val type: JobType,
    val fare: Double,
    val pickupMin: Double = 0.0,
    val pickupMi: Double = 0.0,
    val tripMin: Double = 0.0,
    val tripMi: Double = 0.0,
    val totalMin: Double = 0.0,
    val totalMi: Double = 0.0,
    val pickups: Int = 1,
    val dropoffs: Int = 1,
) {
    /** Used to recognise the same offer across repeated screen events. */
    val signature: String
        get() = listOf(type, fare, pickupMin, pickupMi, tripMin, tripMi, totalMin, totalMi, pickups, dropoffs)
            .joinToString("|")
}

data class ScoreSettings(
    val gasPrice: Double = 4.75,
    val mpg: Double = 38.0,
    val maintPerMile: Double = 0.07,
    val deprPerMile: Double = 0.12,
    val rideTargetHourly: Double = 25.0,
    val deliveryTargetHourly: Double = 22.0,
    val maxPickupMin: Double = 10.0,
    val yellowBandPct: Double = 15.0,
    val restaurantWaitMin: Double = 5.0,
    val extraStopMin: Double = 3.0,
) {
    val fuelPerMile: Double get() = if (mpg > 0) gasPrice / mpg else 0.0
    val costPerMile: Double get() = fuelPerMile + maintPerMile + deprPerMile
}

data class Score(
    val miles: Double,
    val minutes: Double,
    val cost: Double,
    val net: Double,
    val hourly: Double,
    /** Pay this offer would need to hit the target. */
    val needed: Double,
    val grossPerMile: Double,
    val target: Double,
    val verdict: Verdict,
    val reason: String,
)

object Scorer {
    fun score(o: Offer, s: ScoreSettings): Score? {
        val miles: Double
        var minutes: Double
        val target: Double
        var extraNote = ""
        if (o.type == JobType.RIDE) {
            miles = o.pickupMi + o.tripMi
            minutes = o.pickupMin + o.tripMin
            target = s.rideTargetHourly
        } else {
            miles = if (o.totalMi > 0) o.totalMi else o.pickupMi + o.tripMi
            minutes = if (o.totalMin > 0) o.totalMin else o.pickupMin + o.tripMin
            val extra = o.pickups * s.restaurantWaitMin + max(0, o.pickups + o.dropoffs - 2) * s.extraStopMin
            minutes += extra
            target = s.deliveryTargetHourly
            if (extra > 0) extraNote = " Includes ${fmt0(extra)} min for waits and stops."
        }
        if (miles <= 0 || minutes <= 0 || o.fare < 0) return null

        val cost = miles * s.costPerMile
        val net = o.fare - cost
        val hourly = net / minutes * 60.0
        val needed = target * minutes / 60.0 + cost

        val verdict: Verdict
        var reason: String
        if (o.type == JobType.RIDE && o.pickupMin > s.maxPickupMin) {
            verdict = Verdict.SKIP
            reason = "Pickup is ${fmt0(o.pickupMin)} min away, over your ${fmt0(s.maxPickupMin)} min limit."
        } else if (hourly >= target) {
            verdict = Verdict.GOOD
            reason = "Meets your $${fmt0(target)}/hr target."
        } else if (hourly >= target * (1 - s.yellowBandPct / 100.0)) {
            verdict = Verdict.BORDERLINE
            reason = "Within ${fmt0(s.yellowBandPct)}% of your $${fmt0(target)}/hr target."
        } else {
            verdict = Verdict.SKIP
            reason = "Below your $${fmt0(target)}/hr target."
        }
        reason += " ${fmt1(miles)} mi costs ${money(cost)}; ${fmt0(minutes)} min total.$extraNote"
        return Score(miles, minutes, cost, net, hourly, needed, o.fare / miles, target, verdict, reason)
    }

    fun money(v: Double): String = (if (v < 0) "-$" else "$") + String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(v))
    fun fmt0(v: Double): String = v.roundToInt().toString()
    fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
