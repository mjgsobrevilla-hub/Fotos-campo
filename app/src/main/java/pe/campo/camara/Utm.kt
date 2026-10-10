package pe.campo.camara

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class UtmCoord(val zone: Int, val south: Boolean, val easting: Double, val northing: Double) {
    val zoneLabel: String get() = "$zone${if (south) "S" else "N"}"
}

/** Conversión latitud/longitud (WGS84) → UTM. Precisión sub-métrica. */
object Utm {
    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val E2 = F * (2 - F)
    private const val EP2 = E2 / (1 - E2)

    fun fromLatLon(lat: Double, lon: Double): UtmCoord {
        val zone = (floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val phi = Math.toRadians(lat)
        val lam = Math.toRadians(lon)

        val sinP = sin(phi)
        val cosP = cos(phi)
        val n = A / sqrt(1 - E2 * sinP * sinP)
        val t = tan(phi) * tan(phi)
        val c = EP2 * cosP * cosP
        val a = cosP * (lam - lon0)

        val e4 = E2 * E2
        val e6 = e4 * E2
        val m = A * (
            (1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )

        val a2 = a * a
        val a3 = a2 * a
        val a4 = a3 * a
        val a5 = a4 * a
        val a6 = a5 * a

        val easting = K0 * n * (
            a + (1 - t + c) * a3 / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a5 / 120
            ) + 500000.0

        var northing = K0 * (
            m + n * tan(phi) * (
                a2 / 2 + (5 - t + 9 * c + 4 * c * c) * a4 / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a6 / 720
                )
            )
        val south = lat < 0
        if (south) northing += 10000000.0
        return UtmCoord(zone, south, easting, northing)
    }
}
