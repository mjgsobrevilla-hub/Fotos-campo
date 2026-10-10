package pe.campo.camara

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** GPS: guarda la mejor ubicación reciente. Funciona sin internet. */
class LocationTracker(ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var best: Location? = null

    val gpsEnabled: Boolean
        get() = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.allProviders.contains(p)) continue
                lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { consider(it) }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        runCatching { lm.removeUpdates(this) }
    }

    /** Ubicación de los últimos 2 minutos, o null si no hay señal. */
    fun current(): Location? = best?.takeIf { ageMs(it) < 120_000 }

    private fun ageMs(l: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    private fun consider(l: Location) {
        val cur = best
        if (cur == null) {
            best = l; return
        }
        val newer = ageMs(l) + 10_000 < ageMs(cur)
        val moreAccurate = l.accuracy <= cur.accuracy
        val stale = ageMs(cur) > 5_000
        if (newer || moreAccurate || (stale && l.accuracy < cur.accuracy * 2)) best = l
    }

    override fun onLocationChanged(location: Location) = consider(location)

    // Se implementan explícitamente: en Android 10 no tienen implementación por defecto.
    @Deprecated("Requerido en Android 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/** Brújula: hacia dónde apunta la cámara trasera. */
class CompassTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetic: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val r = FloatArray(9)
    private var sx = 0.0
    private var sy = 0.0
    private var hasValue = false

    val available: Boolean get() = rotation != null

    @Volatile
    var needsCalibration = false
        private set

    @Volatile
    private var magneticHeading: Float? = null

    fun start() {
        rotation?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetic?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = sm.unregisterListener(this)

    /** Rumbo respecto al norte verdadero si hay GPS; si no, norte magnético. */
    fun heading(loc: Location?): Heading? {
        val h = magneticHeading ?: return null
        if (loc == null) return Heading(h, trueNorth = false)
        val decl = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(),
            loc.altitude.toFloat(), System.currentTimeMillis(),
        ).declination
        return Heading(((h + decl) % 360f + 360f) % 360f, trueNorth = true)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // La cámara trasera mira hacia -Z del teléfono. Proyectamos ese eje al plano horizontal
        // (mundo: X = este, Y = norte). Así funciona en vertical u horizontal.
        var east = -r[2].toDouble()
        var north = -r[5].toDouble()
        if (hypot(east, north) < 0.35) {
            // Teléfono casi horizontal (cámara al suelo/cielo): usar el borde superior.
            east = r[1].toDouble()
            north = r[4].toDouble()
        }
        val az = atan2(east, north)
        if (!hasValue) {
            sx = cos(az); sy = sin(az); hasValue = true
        } else {
            val k = 0.15
            sx += k * (cos(az) - sx)
            sy += k * (sin(az) - sy)
        }
        val deg = Math.toDegrees(atan2(sy, sx))
        magneticHeading = (((deg % 360) + 360) % 360).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            needsCalibration = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }
    }
}
