package pe.campo.camara

import android.content.Context
import android.location.Location
import java.text.Normalizer

/** Los cinco grupos de fotos por unidad muestral. */
enum class PhotoType(val label: String, val folder: String, val code: String) {
    PANORAMICA("Panorámica", "1_Panoramicas", "PAN"),
    METODOLOGIA("Metodología", "2_Metodologia", "MET"),
    AFECTACIONES("Afectaciones", "3_Afectaciones", "AFE"),
    ESPECIES("Especies", "4_Especies", "ESP"),
    INDICADORES("Indicadores", "5_Indicadores", "IND"),
}

/** Qué hacer con la marca de agua en las fotos de especies. */
enum class SpeciesMode(val label: String) {
    DOBLE("Doble: original sin marca + copia con marca"),
    FRANJA("Franja: datos en una banda debajo (no tapa la foto)"),
    SIN_MARCA("Sin marca (datos solo en EXIF y base de datos)"),
}

data class Heading(val degrees: Float, val trueNorth: Boolean) {
    fun label(): String = "${Math.round(degrees) % 360}° ${cardinal(degrees)}"

    companion object {
        private val POINTS = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO",
        )

        fun cardinal(deg: Float): String {
            val d = ((deg % 360f) + 360f) % 360f
            return POINTS[((d + 11.25f) / 22.5f).toInt() % 16]
        }
    }
}

data class ProjectConfig(
    val project: String,
    val consultant: String,
    val stations: List<String>,
    val units: Int,
    val speciesMode: SpeciesMode,
) {
    companion object {
        private const val PREFS = "config"

        fun load(ctx: Context): ProjectConfig? {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val project = p.getString("project", null) ?: return null
            return ProjectConfig(
                project = project,
                consultant = p.getString("consultant", "") ?: "",
                stations = parseStations(p.getString("stations", "A,B,C") ?: "A,B,C"),
                units = p.getInt("units", 4).coerceIn(1, 99),
                speciesMode = runCatching {
                    SpeciesMode.valueOf(p.getString("speciesMode", SpeciesMode.DOBLE.name)!!)
                }.getOrDefault(SpeciesMode.DOBLE),
            )
        }

        fun save(ctx: Context, c: ProjectConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("project", c.project)
                .putString("consultant", c.consultant)
                .putString("stations", c.stations.joinToString(","))
                .putInt("units", c.units)
                .putString("speciesMode", c.speciesMode.name)
                .apply()
        }

        fun parseStations(raw: String): List<String> =
            raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

/** Todo lo que se sabe en el instante en que se presiona el disparador. */
data class CaptureContext(
    val config: ProjectConfig,
    val station: String,
    val unit: Int,
    val type: PhotoType,
    val customName: String?,
    val timeMs: Long,
    val location: Location?,
    val heading: Heading?,
) {
    /** Carpeta lógica: Proyecto/Estación/UMn/Tipo */
    val folder: String
        get() = "${safe(config.project)}/${safe(station)}/UM$unit/${type.folder}"

    /** Ruta dentro del teléfono (memoria interna → Pictures/CampoFotos/...). */
    val relativePath: String
        get() = "Pictures/CampoFotos/$folder/"

    val defaultPrefix: String
        get() = "${safe(station)}_UM${unit}_${type.code}"
}

/** Quita tildes y caracteres no válidos para nombres de archivo. */
fun safe(s: String): String {
    val noAccents = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    val cleaned = noAccents.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
    return cleaned.ifEmpty { "sin_nombre" }
}
