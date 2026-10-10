package pe.campo.camara

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.content.DialogInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashView: View
    private lateinit var focusRing: View
    private lateinit var statusText: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var rowStations: LinearLayout
    private lateinit var rowUnits: LinearLayout
    private lateinit var rowTypes: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var zoomText: TextView
    private lateinit var lastSavedText: TextView
    private lateinit var shutter: TextView
    private lateinit var btnMode: TextView

    private lateinit var db: Db
    private lateinit var location: LocationTracker
    private lateinit var compass: CompassTracker

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var videoMode = false
    private var targetRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null
    private var scaling = false

    private var config: ProjectConfig? = null
    private var selStation: String? = null
    private var selUnit: Int? = null
    private var selType: PhotoType? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerm(Manifest.permission.CAMERA)) startCamera() else showPermissionHelp()
            if (hasLocationPerm()) location.start()
        }

    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        flashView = findViewById(R.id.flashView)
        focusRing = findViewById(R.id.focusRing)
        statusText = findViewById(R.id.statusText)
        rowsContainer = findViewById(R.id.rowsContainer)
        rowStations = findViewById(R.id.rowStations)
        rowUnits = findViewById(R.id.rowUnits)
        rowTypes = findViewById(R.id.rowTypes)
        nameInput = findViewById(R.id.nameInput)
        zoomText = findViewById(R.id.zoomText)
        lastSavedText = findViewById(R.id.lastSavedText)
        shutter = findViewById(R.id.shutter)
        btnMode = findViewById(R.id.btnMode)

        db = Db(this)
        location = LocationTracker(this)
        compass = CompassTracker(this)

        focusRing.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.WHITE)
        }

        config = ProjectConfig.load(this)
        loadSelections()
        buildRows()
        updateShutter()
        setupTouch()

        shutter.setOnClickListener { if (videoMode) toggleRecording() else takePhoto() }
        btnMode.setOnClickListener { switchMode() }
        findViewById<View>(R.id.btnConfig).setOnClickListener {
            if (recording == null) showConfigDialog(firstTime = false)
        }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportData() }
        findViewById<View>(R.id.btnGallery).setOnClickListener { showSummary() }
        statusText.setOnClickListener {
            rowsContainer.isVisible = !rowsContainer.isVisible
            updateStatus()
        }
        zoomText.setOnClickListener { camera?.cameraControl?.setZoomRatio(1f) }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (r != targetRotation) {
                    targetRotation = r
                    imageCapture?.targetRotation = r
                    videoCapture?.targetRotation = r
                }
            }
        }

        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        ).filterNot { hasPerm(it) }
        if (needed.isEmpty()) startCamera() else permLauncher.launch(needed.toTypedArray())

        if (config == null) showConfigDialog(firstTime = true)
    }

    override fun onResume() {
        super.onResume()
        compass.start()
        if (hasLocationPerm()) location.start()
        orientationListener?.enable()
        handler.post(statusTick)
    }

    override fun onPause() {
        super.onPause()
        recording?.stop()
        compass.stop()
        location.stop()
        orientationListener?.disable()
        handler.removeCallbacks(statusTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ioExecutor.shutdown()
    }

    // ------------------------------------------------------------------ cámara

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val p = provider ?: return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        p.unbindAll()
        imageCapture = null
        videoCapture = null

        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio43).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            camera = if (videoMode) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HIGHEST,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        )
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                vc.targetRotation = targetRotation
                videoCapture = vc
                p.bindToLifecycle(this, selector, preview, vc)
            } else {
                // Hasta ~12 MP (4032x3024): calidad completa sin agotar la memoria al poner la marca.
                val photoRes = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(4032, 3024),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setResolutionSelector(photoRes)
                    .setTargetRotation(targetRotation)
                    .build()
                imageCapture = ic
                p.bindToLifecycle(this, selector, preview, ic)
            }
            camera?.cameraInfo?.zoomState?.observe(this) {
                zoomText.text = String.format(Locale.US, "%.1fx", it.zoomRatio)
            }
        } catch (e: Exception) {
            toast("No se pudo iniciar la cámara: ${e.message}")
        }
    }

    private fun switchMode() {
        if (recording != null) return
        videoMode = !videoMode
        btnMode.text = if (videoMode) "◉\nFoto" else "●\nVideo"
        updateShutter()
        bindUseCases()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scaling = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val zs = cam.cameraInfo.zoomState.value ?: return false
                    val z = (zs.zoomRatio * detector.scaleFactor)
                        .coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(z)
                    return true
                }
            })

        previewView.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> scaling = false
                MotionEvent.ACTION_UP -> {
                    if (!scaling && e.eventTime - e.downTime < 400) {
                        hideKeyboard()
                        focusAt(e.x, e.y)
                        v.performClick()
                    }
                }
            }
            true
        }
    }

    /** Enfocar y medir la luz en el punto tocado. */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        val half = dp(36)
        focusRing.translationX = x - half
        focusRing.translationY = y - half
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction {
                focusRing.visibility = View.GONE
                focusRing.animate().setStartDelay(0)
            }
        }
    }

    // ------------------------------------------------------------------ captura

    private fun buildContext(): CaptureContext? {
        val c = config ?: run {
            showConfigDialog(firstTime = false); return null
        }
        val st = selStation
        val um = selUnit
        val ty = selType
        if (st == null || um == null || ty == null) {
            toast("Elige estación, unidad muestral y tipo")
            if (!rowsContainer.isVisible) rowsContainer.isVisible = true
            return null
        }
        val loc = location.current()
        return CaptureContext(
            config = c, station = st, unit = um, type = ty,
            customName = nameInput.text?.toString(),
            timeMs = System.currentTimeMillis(),
            location = loc, heading = compass.heading(loc),
        )
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val cc = buildContext() ?: return
        val tmp = File(cacheDir, "cap_${System.nanoTime()}.jpg")

        shutter.isEnabled = false
        flashView.alpha = 0.7f
        flashView.animate().alpha(0f).setDuration(220)
        nameInput.text?.clear()
        hideKeyboard()

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread { shutter.isEnabled = true }
                    ioExecutor.execute {
                        val msg = try {
                            Saver.savePhoto(applicationContext, db, tmp, cc)
                        } catch (t: Throwable) {
                            "✗ Error al guardar: ${t.message}"
                        } finally {
                            tmp.delete()
                        }
                        runOnUiThread { lastSavedText.text = msg }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    tmp.delete()
                    runOnUiThread {
                        shutter.isEnabled = true
                        toast("No se pudo tomar la foto: ${exc.message}")
                    }
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        recording?.let {
            it.stop(); return
        }
        val vc = videoCapture ?: return
        val cc = buildContext() ?: return
        val name = Saver.uniqueBase(db, cc, "mp4", "_VID") + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, cc.relativePath)
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = vc.output.prepareRecording(this, opts)
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        nameInput.text?.clear()
        hideKeyboard()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> updateShutter()
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    shutter.text = String.format(Locale.US, "■\n%d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    updateShutter()
                    val uri = ev.outputResults.outputUri
                    if (uri != Uri.EMPTY) {
                        ioExecutor.execute { db.insert(Saver.record(cc, name, "sin marca", "video", uri)) }
                        val gps = if (cc.location == null) "  ⚠ sin GPS" else ""
                        lastSavedText.text = "✓ ${cc.folder.substringAfter('/')}/$name$gps"
                    } else {
                        toast("No se pudo guardar el video (error ${ev.error})")
                    }
                }
                else -> {}
            }
        }
        updateShutter()
    }

    private fun updateShutter() {
        val recordingNow = recording != null
        shutter.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                !videoMode -> {
                    setColor(Color.WHITE); setStroke(dp(4), Color.parseColor("#9E9E9E"))
                }
                recordingNow -> {
                    setColor(Color.parseColor("#B71C1C")); setStroke(dp(4), Color.WHITE)
                }
                else -> {
                    setColor(Color.parseColor("#E53935")); setStroke(dp(4), Color.WHITE)
                }
            }
        }
        if (!recordingNow) shutter.text = ""
        btnMode.alpha = if (recordingNow) 0.4f else 1f
    }

    // ------------------------------------------------------------------ filas de selección

    private fun buildRows() {
        val c = config
        rowStations.removeAllViews()
        rowUnits.removeAllViews()
        rowTypes.removeAllViews()
        if (c == null) return

        if (selStation?.let { it in c.stations } != true) selStation = null
        if ((selUnit ?: 0) > c.units) selUnit = null

        rowStations.addView(rowLabel("Est."))
        for (s in c.stations) {
            rowStations.addView(chip(s, s == selStation) { selStation = s; onSelectionChanged() })
        }
        rowUnits.addView(rowLabel("UM"))
        for (u in 1..c.units) {
            rowUnits.addView(chip(u.toString(), u == selUnit) { selUnit = u; onSelectionChanged() })
        }
        rowTypes.addView(rowLabel("Tipo"))
        for (t in PhotoType.values()) {
            rowTypes.addView(chip(t.label, t == selType) { selType = t; onSelectionChanged() })
        }
    }

    private fun onSelectionChanged() {
        if (recording != null) return
        saveSelections()
        buildRows()
    }

    private fun rowLabel(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#CCFFFFFF"))
        textSize = 11f
        minWidth = dp(34)
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        if (selected) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (selected) {
                setColor(Color.parseColor("#E62E7D32"))
                setStroke(dp(1), Color.parseColor("#A5D6A7"))
            } else {
                setColor(Color.parseColor("#40000000"))
                setStroke(dp(1), Color.parseColor("#80FFFFFF"))
            }
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { onClick() }
    }

    private fun loadSelections() {
        val p = getSharedPreferences("sel", MODE_PRIVATE)
        selStation = p.getString("station", null)
        selUnit = p.getInt("unit", 0).takeIf { it > 0 }
        selType = p.getString("type", null)?.let { n -> PhotoType.values().firstOrNull { it.name == n } }
    }

    private fun saveSelections() {
        getSharedPreferences("sel", MODE_PRIVATE).edit()
            .putString("station", selStation)
            .putInt("unit", selUnit ?: 0)
            .putString("type", selType?.name)
            .apply()
    }

    // ------------------------------------------------------------------ estado (GPS / brújula)

    private fun updateStatus() {
        val loc = location.current()
        val h = compass.heading(loc)
        val gps = when {
            !hasLocationPerm() -> "Sin permiso de ubicación"
            !location.gpsEnabled -> "GPS apagado"
            loc == null -> "Buscando GPS…"
            else -> "GPS ±${loc.accuracy.roundToInt()} m"
        }
        val utm = loc?.let {
            val u = Utm.fromLatLon(it.latitude, it.longitude)
            " · ${u.zoneLabel} ${u.easting.roundToInt()} / ${u.northing.roundToInt()}"
        } ?: ""
        val dir = when {
            !compass.available -> " · sin brújula"
            h != null -> " · ${h.label()}"
            else -> ""
        }
        val cal = if (compass.needsCalibration) " · calibra la brújula (mueve en ∞)" else ""
        val arrow = if (rowsContainer.isVisible) "▲" else "▼"
        statusText.text = "$arrow ${config?.project ?: "Sin proyecto"} · $gps$utm$dir$cal"
        statusText.setTextColor(
            Color.parseColor(
                when {
                    loc == null -> "#FF8A80"
                    loc.accuracy <= 10f -> "#B9F6CA"
                    loc.accuracy <= 30f -> "#FFE57F"
                    else -> "#FF8A80"
                }
            )
        )
    }

    // ------------------------------------------------------------------ proyecto

    private fun showConfigDialog(firstTime: Boolean) {
        val c = config
        val pad = dp(22)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun caption(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setPadding(0, dp(10), 0, 0)
        }

        fun field(t: String, value: String, type: Int): EditText {
            box.addView(caption(t))
            return EditText(this).apply {
                setText(value); inputType = type; maxLines = 1
            }.also { box.addView(it) }
        }

        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val fProject = field("Proyecto / salida de campo", c?.project ?: "", text)
        val fConsultant = field("Consultor (opcional)", c?.consultant ?: "", text)
        val fStations = field(
            "Estaciones, separadas por comas",
            c?.stations?.joinToString(", ") ?: "A, B, C",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
        )
        val fUnits = field(
            "N° de unidades muestrales por estación",
            (c?.units ?: 4).toString(), InputType.TYPE_CLASS_NUMBER,
        )

        box.addView(caption("Fotos de especies"))
        val group = RadioGroup(this)
        val current = c?.speciesMode ?: SpeciesMode.DOBLE
        for (m in SpeciesMode.values()) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                this.text = m.label
                tag = m
                isChecked = m == current
            })
        }
        box.addView(group)

        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (firstTime) "Configura tu proyecto" else "Proyecto")
            .setView(scroll)
            .setPositiveButton("Guardar", null)
            .apply { if (!firstTime) setNegativeButton("Cancelar", null) }
            .setCancelable(!firstTime)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val project = fProject.text.toString().trim()
                val stations = ProjectConfig.parseStations(fStations.text.toString())
                val units = fUnits.text.toString().toIntOrNull() ?: 0
                when {
                    project.isEmpty() -> fProject.error = "Escribe un nombre"
                    stations.isEmpty() -> fStations.error = "Al menos una estación"
                    units !in 1..99 -> fUnits.error = "Entre 1 y 99"
                    else -> {
                        val mode = group.findViewById<RadioButton>(group.checkedRadioButtonId)
                            ?.tag as? SpeciesMode ?: SpeciesMode.DOBLE
                        val newConfig = ProjectConfig(
                            project = project,
                            consultant = fConsultant.text.toString().trim(),
                            stations = stations, units = units, speciesMode = mode,
                        )
                        ProjectConfig.save(this, newConfig)
                        config = newConfig
                        buildRows()
                        saveSelections()
                        updateStatus()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ exportar y resumen

    private fun exportData() {
        val c = config ?: return
        toast("Exportando…")
        ioExecutor.execute {
            val res = runCatching { Exporter.export(applicationContext, db, c.project) }
            runOnUiThread {
                res.onSuccess { r ->
                    if (r.count == 0) {
                        toast("Aún no hay fotos en este proyecto")
                        return@onSuccess
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Registro exportado")
                        .setMessage(
                            "${r.count} archivos registrados.\n\n" +
                                "Se guardó en Descargas/CampoFotos:\n" +
                                "• Registro para Excel (.csv)\n" +
                                "• Mapa para Google Earth (.kml)\n\n" +
                                "Las fotos están en Imágenes/CampoFotos/${safe(c.project)}"
                        )
                        .setPositiveButton("Compartir") { _, _ ->
                            val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "*/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(r.uris))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(i, "Compartir registro"))
                        }
                        .setNegativeButton("Listo", null)
                        .show()
                }.onFailure { toast("Error al exportar: ${it.message}") }
            }
        }
    }

    /** Cuántas fotos hay por estación / UM / tipo, para ver qué falta. */
    private fun showSummary() {
        val c = config ?: return
        ioExecutor.execute {
            val rows = db.all(c.project).filter { it.variant != "original" }
            val sb = StringBuilder()
            val codes = PhotoType.values()
            sb.append("UM   ").append(codes.joinToString(" ") { it.code }).append('\n')
            for (st in c.stations) {
                sb.append("\nEstación ").append(st).append('\n')
                for (u in 1..c.units) {
                    sb.append(String.format(Locale.US, "UM%-3d", u))
                    for (t in codes) {
                        val n = rows.count { it.station == st && it.unit == u && it.type == t.label }
                        sb.append(String.format(Locale.US, "%4s", if (n == 0) "—" else n.toString()))
                    }
                    sb.append('\n')
                }
            }
            sb.append("\nTotal de archivos: ").append(rows.size)
            runOnUiThread {
                val tv = TextView(this).apply {
                    text = sb.toString()
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                    setPadding(dp(22), dp(8), dp(22), dp(8))
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Resumen · ${c.project}")
                    .setView(ScrollView(this).apply { addView(tv) })
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ utilidades

    private fun showPermissionHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permiso de cámara")
            .setMessage("La app necesita la cámara y la ubicación para funcionar. Actívalos en Ajustes.")
            .setPositiveButton("Abrir ajustes") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPerm() =
        hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(nameInput.windowToken, 0)
        nameInput.clearFocus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
