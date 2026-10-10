package pe.campo.camara

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Procesa y guarda fotos: marca de agua, EXIF, carpeta y base de datos. */
object Saver {
    private const val JPEG_QUALITY = 97   // prácticamente sin pérdida visible

    private val exifCopyTags = arrayOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    )

    // ---------- Textos de la marca de agua ----------

    fun watermarkLines(cc: CaptureContext, includeHeading: Boolean): List<String> {
        val date = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.US).format(Date(cc.timeMs))
        val lines = mutableListOf("Fecha: $date")
        val loc = cc.location
        if (loc != null) {
            val u = Utm.fromLatLon(loc.latitude, loc.longitude)
            lines += "UTM WGS84 ${u.zoneLabel}   E: ${u.easting.roundToInt()}   N: ${u.northing.roundToInt()}"
        } else {
            lines += "UTM: sin señal GPS"
        }
        if (includeHeading) {
            lines += cc.heading?.let { "Dirección: ${it.label()}" } ?: "Dirección: sin brújula"
        }
        return lines
    }

    // ---------- Guardado de fotos ----------

    /** Devuelve un mensaje corto para mostrar en pantalla. */
    fun savePhoto(ctx: Context, db: Db, captured: File, cc: CaptureContext): String {
        val base = uniqueBase(db, cc, "jpg")
        val saved = mutableListOf<String>()
        val isPano = cc.type == PhotoType.PANORAMICA

        if (cc.type == PhotoType.ESPECIES) {
            when (cc.config.speciesMode) {
                SpeciesMode.SIN_MARCA -> {
                    saveOriginal(ctx, db, captured, cc, "$base.jpg", "sin marca"); saved += "$base.jpg"
                }
                SpeciesMode.DOBLE -> {
                    saveOriginal(ctx, db, captured, cc, "$base.jpg", "original"); saved += "$base.jpg"
                    saveStamped(ctx, db, captured, cc, "${base}_MA.jpg", band = false, heading = false)
                    saved += "${base}_MA.jpg"
                }
                SpeciesMode.FRANJA -> {
                    saveStamped(ctx, db, captured, cc, "$base.jpg", band = true, heading = false)
                    saved += "$base.jpg"
                }
            }
        } else {
            saveStamped(ctx, db, captured, cc, "$base.jpg", band = false, heading = isPano)
            saved += "$base.jpg"
        }
        val gpsNote = if (cc.location == null) "  ⚠ sin GPS" else ""
        return "✓ ${cc.folder.substringAfter('/')}/${saved.joinToString(" + ")}$gpsNote"
    }

    /** Copia exacta de lo que capturó la cámara (sin recomprimir), con datos en EXIF. */
    private fun saveOriginal(
        ctx: Context, db: Db, captured: File, cc: CaptureContext, name: String, variant: String,
    ) {
        val tmp = File(ctx.cacheDir, "orig_${System.nanoTime()}.jpg")
        try {
            captured.copyTo(tmp, overwrite = true)
            writeMetadata(tmp, cc)
            val uri = publish(ctx, tmp, cc, name, "image/jpeg")
            db.insert(record(cc, name, variant, "foto", uri))
        } finally {
            tmp.delete()
        }
    }

    private fun saveStamped(
        ctx: Context, db: Db, captured: File, cc: CaptureContext, name: String,
        band: Boolean, heading: Boolean,
    ) {
        val tmp = File(ctx.cacheDir, "ma_${System.nanoTime()}.jpg")
        try {
            val lines = watermarkLines(cc, heading)
            val ok = try {
                val src = decodeUpright(captured)
                val out: Bitmap = if (band) {
                    addBand(src, lines).also { src.recycle() }
                } else {
                    drawBox(src, lines); src
                }
                try {
                    FileOutputStream(tmp).use { s ->
                        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, s)
                    }
                } finally {
                    out.recycle()
                }
                true
            } catch (oom: OutOfMemoryError) {
                false
            }
            if (!ok) {
                // Teléfono sin memoria suficiente: guardamos la foto original para no perderla.
                saveOriginal(ctx, db, captured, cc, name, "sin marca (memoria)")
                return
            }
            copyCameraExif(captured, tmp)
            writeMetadata(tmp, cc)
            val uri = publish(ctx, tmp, cc, name, "image/jpeg")
            db.insert(record(cc, name, if (band) "franja" else "con marca", "foto", uri))
        } finally {
            tmp.delete()
        }
    }

    // ---------- Nombres ----------

    /** Nombre base único dentro de la carpeta (sin extensión). */
    fun uniqueBase(db: Db, cc: CaptureContext, ext: String, videoTag: String = ""): String {
        val custom = cc.customName?.trim()?.takeIf { it.isNotEmpty() }?.let { safe(it) }
        fun taken(b: String) = db.exists(cc.folder, "$b.$ext") || db.exists(cc.folder, "${b}_MA.$ext")
        if (custom != null) {
            if (!taken(custom)) return custom
            var i = 2
            while (taken("${custom}_$i")) i++
            return "${custom}_$i"
        }
        var n = 1
        while (taken("${cc.defaultPrefix}${videoTag}_%04d".format(n))) n++
        return "${cc.defaultPrefix}${videoTag}_%04d".format(n)
    }

    // ---------- Imagen ----------

    private fun decodeUpright(file: File): Bitmap {
        val rot = ExifInterface(file.absolutePath).rotationDegrees
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("No se pudo leer la foto")
        if (rot % 360 == 0) return src
        val swap = rot % 180 != 0
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            translate(w / 2f, h / 2f)
            rotate(rot.toFloat())
            translate(-src.width / 2f, -src.height / 2f)
            drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        return out
    }

    private fun textPaint(bmp: Bitmap): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = max(18f, minOf(bmp.width, bmp.height) * 0.032f)
        isFakeBoldText = true
        setShadowLayer(textSize * 0.12f, 0f, 0f, Color.BLACK)
    }

    /** Recuadro semitransparente en la esquina inferior derecha (estilo Timestamp). */
    private fun drawBox(bmp: Bitmap, lines: List<String>) {
        val p = textPaint(bmp)
        val lineH = p.fontSpacing
        val pad = p.textSize * 0.5f
        val margin = p.textSize * 0.6f
        val textW = lines.maxOf { p.measureText(it) }
        val boxW = textW + pad * 2
        val boxH = lineH * lines.size + pad * 2
        val left = bmp.width - margin - boxW
        val top = bmp.height - margin - boxH
        val c = Canvas(bmp)
        c.drawRoundRect(
            RectF(left, top, left + boxW, top + boxH), pad, pad,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) },
        )
        var y = top + pad - p.ascent()
        for (l in lines) {
            c.drawText(l, left + pad, y, p); y += lineH
        }
    }

    /** Banda negra añadida debajo: no tapa ningún píxel de la foto. */
    private fun addBand(src: Bitmap, lines: List<String>): Bitmap {
        val p = textPaint(src)
        p.clearShadowLayer()
        val pad = p.textSize * 0.6f
        val bandH = (p.fontSpacing * lines.size + pad * 2).roundToInt()
        val out = Bitmap.createBitmap(src.width, src.height + bandH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)
        c.drawRect(0f, src.height.toFloat(), src.width.toFloat(), out.height.toFloat(),
            Paint().apply { color = Color.BLACK })
        var y = src.height + pad - p.ascent()
        for (l in lines) {
            c.drawText(l, pad, y, p); y += p.fontSpacing
        }
        return out
    }

    // ---------- EXIF ----------

    private fun copyCameraExif(from: File, to: File) {
        runCatching {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (t in exifCopyTags) src.getAttribute(t)?.let { dst.setAttribute(t, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        }
    }

    private fun writeMetadata(file: File, cc: CaptureContext) {
        val ex = ExifInterface(file.absolutePath)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(cc.timeMs))
        ex.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        cc.location?.let { ex.setGpsInfo(it) }
        cc.heading?.let {
            ex.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "${(it.degrees * 100).roundToInt()}/100")
            ex.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, if (it.trueNorth) "T" else "M")
        }
        ex.setAttribute(
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            "${cc.config.project} | Est. ${cc.station} | UM${cc.unit} | ${cc.type.label}",
        )
        if (cc.config.consultant.isNotBlank()) ex.setAttribute(ExifInterface.TAG_ARTIST, cc.config.consultant)
        ex.saveAttributes()
    }

    // ---------- Almacenamiento ----------

    /** Copia el archivo a la galería del teléfono en Pictures/CampoFotos/... */
    private fun publish(ctx: Context, file: File, cc: CaptureContext, name: String, mime: String): Uri {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, cc.relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, cc.timeMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("No se pudo crear el archivo en la galería")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun record(cc: CaptureContext, name: String, variant: String, media: String, uri: Uri): Record {
        val loc = cc.location
        val utm = loc?.let { Utm.fromLatLon(it.latitude, it.longitude) }
        return Record(
            project = cc.config.project, consultant = cc.config.consultant,
            station = cc.station, unit = cc.unit, type = cc.type.label,
            fileName = name, folder = cc.folder, timeMs = cc.timeMs,
            lat = loc?.latitude, lon = loc?.longitude,
            alt = loc?.takeIf { it.hasAltitude() }?.altitude,
            accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy,
            utmZone = utm?.zoneLabel, utmE = utm?.easting, utmN = utm?.northing,
            heading = cc.heading?.degrees,
            headingRef = cc.heading?.let { if (it.trueNorth) "Verdadero" else "Magnético" },
            media = media, variant = variant, uri = uri.toString(),
        )
    }
}
