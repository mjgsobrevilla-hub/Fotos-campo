package pe.campo.camara

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exporta la base de datos del proyecto a Excel (CSV) y Google Earth (KML). */
object Exporter {

    data class Result(val uris: List<Uri>, val count: Int, val folder: String)

    fun export(ctx: Context, db: Db, project: String): Result {
        val rows = db.all(project)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val base = "${safe(project)}_$stamp"
        val folder = "Download/CampoFotos/"
        if (rows.isEmpty()) return Result(emptyList(), 0, folder)
        val csv = save(ctx, folder, "${base}_registro.csv", "text/csv", buildCsv(rows))
        val kml = save(ctx, folder, "${base}_mapa.kml", "application/vnd.google-earth.kml+xml", buildKml(project, rows))
        return Result(listOf(csv, kml), rows.size, folder)
    }

    private val dateFmt get() = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private val timeFmt get() = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun buildCsv(rows: List<Record>): String {
        val header = listOf(
            "id", "proyecto", "consultor", "estacion", "unidad_muestral", "tipo", "medio", "variante",
            "carpeta", "archivo", "fecha", "hora", "latitud", "longitud", "altitud_m", "precision_m",
            "utm_zona", "utm_este", "utm_norte", "direccion_grados", "direccion_cardinal", "direccion_norte",
        )
        val sb = StringBuilder("﻿") // BOM: para que Excel lea bien las tildes
        sb.append(header.joinToString(",")).append("\r\n")
        val df = dateFmt
        val tf = timeFmt
        for (r in rows) {
            val d = Date(r.timeMs)
            val cells = listOf(
                r.id.toString(), r.project, r.consultant, r.station, r.unit.toString(), r.type,
                r.media, r.variant, "CampoFotos/${r.folder}", r.fileName, df.format(d), tf.format(d),
                r.lat?.let { "%.7f".format(Locale.US, it) } ?: "",
                r.lon?.let { "%.7f".format(Locale.US, it) } ?: "",
                r.alt?.let { "%.1f".format(Locale.US, it) } ?: "",
                r.accuracy?.let { "%.1f".format(Locale.US, it) } ?: "",
                r.utmZone ?: "",
                r.utmE?.let { "%.0f".format(Locale.US, it) } ?: "",
                r.utmN?.let { "%.0f".format(Locale.US, it) } ?: "",
                r.heading?.let { Math.round(it).toString() } ?: "",
                r.heading?.let { Heading.cardinal(it) } ?: "",
                r.headingRef ?: "",
            )
            sb.append(cells.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\""
        else s

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildKml(project: String, rows: List<Record>): String {
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""").append('\n')
        sb.append("<name>").append(xml(project)).append("</name>\n")
        // Una carpeta por estación
        for ((station, list) in rows.filter { it.lat != null && it.lon != null }.groupBy { it.station }) {
            sb.append("<Folder><name>Estación ").append(xml(station)).append("</name>\n")
            for (r in list) {
                val desc = buildString {
                    append("Carpeta: CampoFotos/").append(r.folder).append("<br/>")
                    append("Fecha: ").append(df.format(Date(r.timeMs))).append("<br/>")
                    if (r.utmZone != null) append("UTM ${r.utmZone}  E ${"%.0f".format(Locale.US, r.utmE)}  N ${"%.0f".format(Locale.US, r.utmN)}<br/>")
                    r.heading?.let { append("Dirección: ${Math.round(it)}° ${Heading.cardinal(it)}<br/>") }
                    append("Tipo: ").append(r.type).append(" (").append(r.media).append(", ").append(r.variant).append(")")
                }
                sb.append("<Placemark><name>").append(xml(r.fileName)).append("</name>")
                sb.append("<description><![CDATA[").append(desc).append("]]></description>")
                sb.append("<Point><coordinates>")
                    .append("%.7f,%.7f,0".format(Locale.US, r.lon, r.lat))
                    .append("</coordinates></Point></Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document></kml>\n")
        return sb.toString()
    }

    private fun save(ctx: Context, folder: String, name: String, mime: String, content: String): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear $name")
        resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return uri
    }
}
