package pe.campo.camara

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Record(
    val id: Long = 0,
    val project: String,
    val consultant: String,
    val station: String,
    val unit: Int,
    val type: String,
    val fileName: String,
    val folder: String,
    val timeMs: Long,
    val lat: Double?,
    val lon: Double?,
    val alt: Double?,
    val accuracy: Float?,
    val utmZone: String?,
    val utmE: Double?,
    val utmN: Double?,
    val heading: Float?,
    val headingRef: String?,   // "Verdadero" / "Magnético"
    val media: String,         // "foto" / "video"
    val variant: String,       // "con marca", "franja", "original", "sin marca"
    val uri: String,
)

/** Base de datos local (SQLite) con un registro por archivo guardado. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "campo.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE fotos (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              proyecto TEXT, consultor TEXT, estacion TEXT, unidad INTEGER, tipo TEXT,
              archivo TEXT, carpeta TEXT, fecha_ms INTEGER,
              lat REAL, lon REAL, alt REAL, precision_m REAL,
              utm_zona TEXT, utm_e REAL, utm_n REAL,
              direccion REAL, direccion_ref TEXT,
              medio TEXT, variante TEXT, uri TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_carpeta ON fotos(carpeta, archivo)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun insert(r: Record): Long {
        val v = ContentValues().apply {
            put("proyecto", r.project); put("consultor", r.consultant)
            put("estacion", r.station); put("unidad", r.unit); put("tipo", r.type)
            put("archivo", r.fileName); put("carpeta", r.folder); put("fecha_ms", r.timeMs)
            put("lat", r.lat); put("lon", r.lon); put("alt", r.alt)
            put("precision_m", r.accuracy?.toDouble())
            put("utm_zona", r.utmZone); put("utm_e", r.utmE); put("utm_n", r.utmN)
            put("direccion", r.heading?.toDouble()); put("direccion_ref", r.headingRef)
            put("medio", r.media); put("variante", r.variant); put("uri", r.uri)
        }
        return writableDatabase.insert("fotos", null, v)
    }

    @Synchronized
    fun exists(folder: String, fileName: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM fotos WHERE carpeta = ? AND archivo = ? COLLATE NOCASE LIMIT 1",
            arrayOf(folder, fileName),
        ).use { it.moveToFirst() }

    @Synchronized
    fun count(project: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM fotos WHERE proyecto = ?", arrayOf(project))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun all(project: String): List<Record> =
        readableDatabase.rawQuery(
            "SELECT * FROM fotos WHERE proyecto = ? ORDER BY fecha_ms",
            arrayOf(project),
        ).use { c ->
            val out = ArrayList<Record>()
            while (c.moveToNext()) out += c.toRecord()
            out
        }

    private fun Cursor.str(n: String): String = getString(getColumnIndexOrThrow(n)) ?: ""
    private fun Cursor.dbl(n: String): Double? =
        getColumnIndexOrThrow(n).let { if (isNull(it)) null else getDouble(it) }

    private fun Cursor.toRecord() = Record(
        id = getLong(getColumnIndexOrThrow("id")),
        project = str("proyecto"), consultant = str("consultor"),
        station = str("estacion"), unit = getInt(getColumnIndexOrThrow("unidad")),
        type = str("tipo"), fileName = str("archivo"), folder = str("carpeta"),
        timeMs = getLong(getColumnIndexOrThrow("fecha_ms")),
        lat = dbl("lat"), lon = dbl("lon"), alt = dbl("alt"),
        accuracy = dbl("precision_m")?.toFloat(),
        utmZone = getColumnIndexOrThrow("utm_zona").let { if (isNull(it)) null else getString(it) },
        utmE = dbl("utm_e"), utmN = dbl("utm_n"),
        heading = dbl("direccion")?.toFloat(),
        headingRef = getColumnIndexOrThrow("direccion_ref").let { if (isNull(it)) null else getString(it) },
        media = str("medio"), variant = str("variante"), uri = str("uri"),
    )
}
