package com.trailguidegps.app.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which way the top of the phone points, in degrees from true north (corrected
 * for magnetic declination, about 15° east in Oregon). Uses the phone's rotation
 * sensor; works offline. Hold the phone flat for the best reading.
 */
class Compass(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    private var declination = 0f
    private var users = 0
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)

    val available get() = sensor != null

    fun setPosition(lat: Double, lon: Double, altM: Double?) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), (altM ?: 0.0).toFloat(), System.currentTimeMillis()).declination
    }

    fun start() {
        if (users++ == 0 && sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (users > 0 && --users == 0) { sm.unregisterListener(this); _heading.value = null }
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, orient)
        val magnetic = Math.toDegrees(orient[0].toDouble()).toFloat()
        val trueNorth = (magnetic + declination + 360f) % 360f
        // Smooth out jitter, taking the short way around north.
        val prev = _heading.value
        _heading.value = if (prev == null) trueNorth else {
            var d = trueNorth - prev
            if (d > 180) d -= 360
            if (d < -180) d += 360
            (prev + d * 0.25f + 360f) % 360f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
