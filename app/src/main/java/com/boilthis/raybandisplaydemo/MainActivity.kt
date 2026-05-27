package com.boilthis.raybandisplaydemo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

data class GlassTask(
    val id: Int,
    val title: String,
    var status: String = "PENDING",
    var results: String? = null,
    var evidencePath: String? = null,
    var priority: Int = 1
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "GlassTasks"
    }

    private lateinit var statusText: TextView
    private lateinit var devicesText: TextView
    private lateinit var imuText: TextView
    private lateinit var gestureText: TextView
    private lateinit var btnCamera: Button
    private lateinit var btnSyncTasks: Button
    private lateinit var cameraPreview: TextureView

    private lateinit var taskInput: EditText
    private lateinit var btnAddTask: Button
    private lateinit var taskListContainer: LinearLayout
    private lateinit var btnClearTasks: Button
    private lateinit var btnGenerateReport: Button
    
    private lateinit var tasksSection: View
    private lateinit var glassSection: View
    private lateinit var reportSection: View
    private lateinit var fabAddTask: FloatingActionButton
    private lateinit var bottomNavigation: BottomNavigationView

    private var activeSession: DeviceSession? = null
    private var activeStream: Stream? = null
    private var activeDisplay: Display? = null
    private var videoDecoder: MediaCodec? = null
    private var previewSurface: Surface? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var taskList = mutableListOf<GlassTask>()
    private var currentTaskIndex = 0
    private var renderJob: Job? = null
    private var hasAutoSynced = false

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Permission>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Wearables
        try { Wearables.initialize(applicationContext) } catch (e: Exception) {}

        // Bind UI
        statusText = findViewById(R.id.statusText)
        devicesText = findViewById(R.id.devicesText)
        imuText = findViewById(R.id.imuText)
        gestureText = findViewById(R.id.gestureText)
        btnCamera = findViewById(R.id.btnCamera)
        btnSyncTasks = findViewById(R.id.btnDisplay)
        cameraPreview = findViewById(R.id.cameraPreview)
        taskInput = findViewById(R.id.taskInput)
        btnAddTask = findViewById(R.id.btnAddTask)
        taskListContainer = findViewById(R.id.taskListContainer)
        btnClearTasks = findViewById(R.id.btnClearTasks)
        btnGenerateReport = findViewById(R.id.btnGenerateReport)
        tasksSection = findViewById(R.id.tasksSection)
        glassSection = findViewById(R.id.glassSection)
        reportSection = findViewById(R.id.reportSection)
        fabAddTask = findViewById(R.id.fabAddTask)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        setupNavigation()
        setupFab()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        requestPermissionLauncher = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { if (it == PermissionStatus.Granted) startCameraStream() }
        }

        findViewById<Button>(R.id.registerButton).setOnClickListener { Wearables.startRegistration(this) }
        findViewById<Button>(R.id.resetButton).setOnClickListener { forceReset() }
        btnCamera.setOnClickListener { checkCameraPermissionAndStart() }
        btnSyncTasks.setOnClickListener { pushActiveTaskToGlasses(); startGlassesListening() }
        btnGenerateReport.setOnClickListener { generatePdfReport() }
        btnClearTasks.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("RESET SESSION").setMessage("Clear all current progress?")
                .setPositiveButton("CLEAR") { _, _ ->
                    taskList.clear(); currentTaskIndex = 0; saveTasks(); refreshTaskListView()
                }.setNegativeButton("CANCEL", null).show()
        }

        setupCameraSurface()
        setupVoiceControl()
        startObservingStates()
        
        loadTasks()
        if (taskList.isEmpty()) {
            taskList.add(GlassTask(1, "Restock Produce", priority = 3))
            taskList.add(GlassTask(2, "Expiring Items Audit", priority = 2))
            taskList.add(GlassTask(3, "Compliance Review", priority = 3))
            saveTasks()
        }
        refreshTaskListView()
    }

    private fun setupNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            tasksSection.visibility = if (item.itemId == R.id.nav_tasks) View.VISIBLE else View.GONE
            glassSection.visibility = if (item.itemId == R.id.nav_glass) View.VISIBLE else View.GONE
            reportSection.visibility = if (item.itemId == R.id.nav_report) View.VISIBLE else View.GONE
            if (item.itemId == R.id.nav_tasks) fabAddTask.show() else fabAddTask.hide()
            true
        }
    }

    private fun setupFab() {
        fabAddTask.setOnClickListener {
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 64, 64, 24) }
            val input = EditText(this).apply {
                hint = "ENTRY TITLE"; background = null; textSize = 22f; setTypeface(null, Typeface.BOLD)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            layout.addView(input)
            layout.addView(TextView(this).apply { text = "PRIORITY"; textSize = 10f; setPadding(0, 32, 0, 8) })
            val pSpinner = Spinner(this)
            pSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("LOW", "MEDIUM", "CRITICAL"))
            layout.addView(pSpinner)

            android.app.AlertDialog.Builder(this).setTitle("NEW WORK ITEM").setView(layout).setPositiveButton("CREATE") { _, _ ->
                val title = input.text.toString()
                if (title.isNotBlank()) {
                    taskList.add(GlassTask(System.currentTimeMillis().toInt(), title, priority = pSpinner.selectedItemPosition + 1))
                    saveTasks(); refreshTaskListView()
                }
            }.setNegativeButton("CANCEL", null).show()
        }
    }

    private fun refreshTaskListView() {
        taskListContainer.removeAllViews()
        taskList.forEachIndexed { index, task ->
            val card = androidx.cardview.widget.CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 4, 0, 12) }
                radius = 4f; elevation = 2f; setCardBackgroundColor(if (index == currentTaskIndex) Color.parseColor("#F5F5F5") else Color.WHITE)
            }
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val indicator = View(this).apply { 
                layoutParams = LinearLayout.LayoutParams(6, 40); setBackgroundColor(if (index == currentTaskIndex) Color.BLACK else Color.TRANSPARENT)
            }
            val title = TextView(this).apply {
                text = task.title.uppercase(); textSize = 16f; setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(24, 0, 0, 0) }; setTextColor(Color.BLACK)
            }
            val pDot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(14, 14)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(when(task.priority) { 3 -> Color.RED; 2 -> Color.parseColor("#FFA000"); else -> Color.LTGRAY })
                }
            }
            header.addView(indicator); header.addView(title); header.addView(pDot)
            container.addView(header)

            val status = TextView(this).apply {
                text = task.status; textSize = 10f; letterSpacing = 0.1f; setPadding(30, 4, 0, 0)
                setTextColor(if (task.status == "PENDING") Color.GRAY else Color.parseColor("#388E3C"))
            }
            container.addView(status)

            if (task.results != null) {
                container.addView(TextView(this).apply { text = "\"${task.results}\""; textSize = 14f; setPadding(30, 16, 0, 0); setTextColor(Color.DKGRAY); setTypeface(null, Typeface.ITALIC) })
            }
            if (task.evidencePath != null) {
                container.addView(ImageView(this).apply {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    setImageBitmap(BitmapFactory.decodeFile(task.evidencePath, opts))
                    adjustViewBounds = true; layoutParams = LinearLayout.LayoutParams(-1, 450).apply { setMargins(30, 20, 0, 0) }
                    scaleType = ImageView.ScaleType.CENTER_CROP; setOnClickListener { showFullImage(task.evidencePath!!) }
                })
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 16, 0, 0) }
            actions.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.LTGRAY)
                setOnClickListener { taskList.removeAt(index); saveTasks(); refreshTaskListView() }
            })
            container.addView(actions); card.addView(container); taskListContainer.addView(card)
        }
    }

    private fun renderTaskHUD(display: Display, task: GlassTask) {
        val next = if (currentTaskIndex + 1 in taskList.indices) taskList[currentTaskIndex + 1] else null
        lifecycleScope.launch {
            try {
                display.sendContent {
                    flexBox(Direction.COLUMN, 0, Alignment.CENTER, Alignment.CENTER, false, 1, null, null, null, null, FlexBoxBackground.CARD, null) {
                        text("TASK PRO", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                        text("${currentTaskIndex + 1} OF ${taskList.size}", TextStyle.META, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                        if (task.priority == 3) text("● CRITICAL", TextStyle.META, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                        text(task.title.uppercase(), TextStyle.HEADING, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                        val icon = when (task.status) {
                            "COMPLETED" -> IconName.CHECKMARK_CIRCLE
                            "EVIDENCE CAPTURED" -> IconName.VIDEO_CAMERA
                            else -> IconName.ARROW_RIGHT
                        }
                        flexBox(Direction.ROW, 12, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, null, null, FlexBoxBackground.NONE, null) {
                            icon(icon, IconStyle.FILLED, 0f, 0f, Alignment.CENTER)
                            text(task.status, TextStyle.BODY, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                        }
                        if (next != null) {
                            text("────────", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                            text("NEXT: ${next.title.uppercase()}", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "HUD Error", e) }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_TAB, android.view.KeyEvent.KEYCODE_VOLUME_UP -> { moveNext(); return true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { movePrevious(); return true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_CAMERA, android.view.KeyEvent.KEYCODE_HEADSETHOOK -> { captureEvidence(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveNext() { if (taskList.isNotEmpty()) { currentTaskIndex = (currentTaskIndex + 1) % taskList.size; runOnUiThread { refreshTaskListView(); pushActiveTaskToGlasses() } } }
    private fun movePrevious() { if (taskList.isNotEmpty()) { currentTaskIndex = if (currentTaskIndex - 1 < 0) taskList.size - 1 else currentTaskIndex - 1; runOnUiThread { refreshTaskListView(); pushActiveTaskToGlasses() } } }

    private fun captureEvidence() {
        val stream = activeStream ?: run { checkCameraPermissionAndStart(); return }
        activeDisplay?.let { display ->
            lifecycleScope.launch {
                try {
                    display.sendContent {
                        flexBox(Direction.COLUMN, 0, Alignment.CENTER, Alignment.CENTER, false, 1, null, null, null, null, FlexBoxBackground.CARD, null) {
                            icon(IconName.FOUR_CORNER_FRAME, IconStyle.FILLED, 0f, 0f, Alignment.CENTER)
                            text("HOLD STEADY", TextStyle.HEADING, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        lifecycleScope.launch {
            stream.capturePhoto().onSuccess { photo ->
                val bitmap = when (photo) {
                    is PhotoData.Bitmap -> photo.bitmap
                    is PhotoData.HEIC -> {
                        val buf = photo.data.duplicate().apply { rewind() }
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                if (bitmap != null) {
                    val path = saveEvidenceToFile(bitmap, taskList[currentTaskIndex].id, System.currentTimeMillis())
                    taskList[currentTaskIndex].apply { evidencePath = path; status = "EVIDENCE CAPTURED" }
                    saveTasks(); runOnUiThread { bottomNavigation.selectedItemId = R.id.nav_tasks; refreshTaskListView(); pushActiveTaskToGlasses() }
                } else pushActiveTaskToGlasses()
            }.onFailure { _, _ -> pushActiveTaskToGlasses() }
        }
    }

    private fun setupVoiceControl() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) initializeSpeechRecognizer()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    private fun initializeSpeechRecognizer() {
        runOnUiThread {
            speechRecognizer?.destroy(); speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { processVoiceCommand(it.lowercase()) }
                    lifecycleScope.launch { delay(800); startGlassesListening() }
                }
                override fun onError(e: Int) { if (e in listOf(5, 6, 7, 8)) lifecycleScope.launch { delay(1000); startGlassesListening() } }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
    }

    private fun startGlassesListening() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.startBluetoothSco(); am.isBluetoothScoOn = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        runOnUiThread { speechRecognizer?.startListening(intent) }
    }

    private fun processVoiceCommand(cmd: String) {
        runOnUiThread { 
            gestureText.text = "Heard: $cmd" 
            if (cmd.startsWith("report")) {
                val txt = cmd.replaceFirst("report", "").trim()
                if (txt.isNotEmpty()) { taskList[currentTaskIndex].results = txt; saveTasks(); refreshTaskListView(); pushActiveTaskToGlasses() }
            } else if (cmd.contains("next")) moveNext()
            else if (cmd.contains("previous") || cmd.contains("back")) movePrevious()
            else if (cmd.contains("capture") || cmd.contains("photo")) captureEvidence()
            else if (cmd.contains("complete") || cmd.contains("done")) { taskList[currentTaskIndex].status = "COMPLETED"; saveTasks(); refreshTaskListView(); pushActiveTaskToGlasses() }
        }
    }

    private fun pushActiveTaskToGlasses() {
        if (taskList.isEmpty()) return
        val task = taskList[currentTaskIndex]
        renderJob?.cancel(); renderJob = lifecycleScope.launch {
            startSession { session ->
                lifecycleScope.launch {
                    val disp = activeDisplay ?: session.addDisplay(DisplayConfiguration()).getOrNull()
                    if (disp != null) { activeDisplay = disp; disp.state.collect { if (it == DisplayState.STARTED) { delay(300); renderTaskHUD(disp, task); this.cancel() } } }
                }
            }
        }
    }

    private fun startSession(onSuccess: (DeviceSession) -> Unit) {
        val s = activeSession; if (s?.state?.value == DeviceSessionState.STARTED) { onSuccess(s); return }
        lifecycleScope.launch {
            val dId = Wearables.devices.value.firstOrNull() ?: return@launch
            Wearables.createSession(SpecificDeviceSelector(dId)).onSuccess { activeSession = it; it.start()
                launch { it.state.collect { if (it == DeviceSessionState.STARTED) { runOnUiThread { onSuccess(activeSession!!) }; this.cancel() } } }
            }
        }
    }

    private fun startCameraStream() {
        startSession { session -> 
            lifecycleScope.launch { 
                session.addStream(StreamConfiguration(VideoQuality.MEDIUM, 30, true)).onSuccess { stream -> 
                    activeStream = stream
                    stream.start().onSuccess { 
                        launch { 
                            stream.videoStream.collect { frame -> 
                                renderFrame(frame) 
                            } 
                        } 
                    } 
                } 
            } 
        }
    }

    private fun renderFrame(f: VideoFrame) {
        val s = previewSurface ?: return; f.buffer.rewind()
        if (f.isCodecConfig) { initializeDecoder(f, s); return }
        val d = videoDecoder ?: return
        try {
            val i = d.dequeueInputBuffer(10000)
            if (i >= 0) { d.getInputBuffer(i)?.apply { clear(); put(f.buffer) }; d.queueInputBuffer(i, 0, f.buffer.remaining(), f.presentationTimeUs, 0) }
            val info = MediaCodec.BufferInfo(); var out = d.dequeueOutputBuffer(info, 10000)
            while (out >= 0) { d.releaseOutputBuffer(out, true); out = d.dequeueOutputBuffer(info, 0) }
        } catch (e: Exception) {}
    }

    private fun initializeDecoder(f: VideoFrame, s: Surface) {
        releaseDecoder(); val mime = if (isHEVC(f)) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val fmt = MediaFormat.createVideoFormat(mime, f.width, f.height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (f.isCodecConfig) fmt.setByteBuffer("csd-0", f.buffer)
        try { videoDecoder = MediaCodec.createDecoderByType(mime); videoDecoder?.configure(fmt, s, null, 0); videoDecoder?.start() } catch (e: Exception) {}
    }

    private fun isHEVC(f: VideoFrame): Boolean { if (f.buffer.remaining() < 5) return false; val b = f.buffer.get(4).toInt(); return (b and 0x7E) shr 1 == 32 || (b and 0x7E) shr 1 == 33 }
    private fun releaseDecoder() { try { videoDecoder?.stop(); videoDecoder?.release() } catch (e: Exception) {}; videoDecoder = null }
    private fun setupCameraSurface() {
        cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { previewSurface = Surface(s) }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { previewSurface?.release(); previewSurface = null; releaseDecoder(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun startObservingStates() {
        lifecycleScope.launch {
            launch { Wearables.registrationState.collect { statusText.text = "GLASSTASKS" } }
            launch { 
                Wearables.devices.collect { devices ->
                    devicesText.text = if (devices.isEmpty()) "SEARCHING..." else "READY"
                    if (devices.isNotEmpty() && !hasAutoSynced) { hasAutoSynced = true; delay(1000); pushActiveTaskToGlasses(); startGlassesListening(); checkCameraPermissionAndStart() }
                }
            }
        }
    }

    private fun forceReset() { lifecycleScope.launch { activeSession?.stop(); Wearables.reset(); delay(1000); Wearables.initialize(applicationContext); startObservingStates() } }
    private fun checkCameraPermissionAndStart() { lifecycleScope.launch { Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess { if (it == PermissionStatus.Granted) startCameraStream() else requestPermissionLauncher.launch(Permission.CAMERA) } } }
    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val r = FloatArray(9); val o = FloatArray(3); SensorManager.getRotationMatrixFromVector(r, e.values); SensorManager.getOrientation(r, o)
            runOnUiThread { imuText.text = String.format(Locale.US, "Pitch: %.1f | Yaw: %.1f", Math.toDegrees(o[1].toDouble()), Math.toDegrees(o[0].toDouble())) }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onResume() { super.onResume(); rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); speechRecognizer?.destroy(); releaseDecoder(); activeSession?.stop() }

    private fun saveTasks() {
        try {
            val array = JSONArray()
            taskList.forEach { task ->
                val obj = JSONObject().apply {
                    put("id", task.id); put("title", task.title); put("status", task.status)
                    put("results", task.results ?: JSONObject.NULL); put("evidencePath", task.evidencePath ?: JSONObject.NULL); put("priority", task.priority)
                }
                array.put(obj)
            }
            FileOutputStream(File(filesDir, "tasks.json")).use { it.write(array.toString().toByteArray()) }
        } catch (e: Exception) {}
    }

    private fun loadTasks() {
        try {
            val f = File(filesDir, "tasks.json")
            if (!f.exists()) return
            val array = JSONArray(f.readText())
            taskList.clear()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                taskList.add(GlassTask(o.getInt("id"), o.getString("title"), o.getString("status"),
                    if (o.isNull("results")) null else o.getString("results"),
                    if (o.isNull("evidencePath")) null else o.getString("evidencePath"), o.getInt("priority")))
            }
        } catch (e: Exception) {}
    }

    private fun saveEvidenceToFile(b: Bitmap, id: Int, t: Long): String? {
        val f = File(getExternalFilesDir(null) ?: filesDir, "evidence_${id}_$t.jpg")
        return try { FileOutputStream(f).use { out -> b.compress(Bitmap.CompressFormat.JPEG, 90, out); out.flush() }; f.absolutePath } catch (e: Exception) { null }
    }

    private fun showFullImage(p: String) {
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val i = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(p)); scaleType = ImageView.ScaleType.FIT_CENTER; setOnClickListener { d.dismiss() } }
        d.setContentView(i); d.show()
    }

    private fun generatePdfReport() {
        if (taskList.isEmpty()) return
        val doc = PdfDocument()
        val p = Paint(); val tp = Paint().apply { textSize = 24f; isFakeBoldText = true }; val bp = Paint().apply { textSize = 14f }
        var pageNum = 1; var info = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
        var page = doc.startPage(info); var canvas = page.canvas; var y = 50f
        canvas.drawText("GLASSTASKS WORK REPORT", 50f, y, tp); y += 40f
        taskList.forEach { task ->
            if (y > 700) { doc.finishPage(page); pageNum++; info = PdfDocument.PageInfo.Builder(595, 842, pageNum).create(); page = doc.startPage(info); canvas = page.canvas; y = 50f }
            canvas.drawText("TASK: ${task.title.uppercase()}", 50f, y, Paint(tp).apply { textSize = 18f }); y += 25f
            canvas.drawText("STATUS: ${task.status}", 50f, y, bp); y += 20f
            canvas.drawText("NOTE: ${task.results ?: "NONE"}", 50f, y, bp); y += 30f
            if (task.evidencePath != null) {
                val b = BitmapFactory.decodeFile(task.evidencePath)
                if (b != null) { val s = Bitmap.createScaledBitmap(b, 300, 225, true); canvas.drawBitmap(s, 50f, y, p); y += 250f }
            }
            y += 30f
        }
        doc.finishPage(page)
        val file = File(getExternalFilesDir(null), "WorkReport_${System.currentTimeMillis()}.pdf")
        try { doc.writeTo(FileOutputStream(file)); sharePdf(file) } catch (e: Exception) {}
        doc.close()
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(intent, "Share Report"))
    }
}
