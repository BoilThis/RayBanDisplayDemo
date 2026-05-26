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

    // Task UI
    private lateinit var taskInput: EditText
    private lateinit var btnAddTask: Button
    private lateinit var taskListContainer: LinearLayout
    private lateinit var btnClearTasks: Button
    private lateinit var btnGenerateReport: Button

    private var activeSession: DeviceSession? = null
    private var activeStream: Stream? = null
    private var activeDisplay: Display? = null
    private var videoDecoder: MediaCodec? = null
    private var previewSurface: Surface? = null

    private var speechRecognizer: SpeechRecognizer? = null
    
    // Database integration
    private lateinit var database: AppDatabase
    private var taskList = mutableListOf<GlassTaskEntity>()
    private var currentTaskIndex = 0

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Permission>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Database
        database = AppDatabase.getDatabase(this)

        // Ensure Wearables are initialized
        try {
            Wearables.initialize(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Wearables initialization failed", e)
        }

        statusText = findViewById(R.id.statusText)
        devicesText = findViewById(R.id.devicesText)
        imuText = findViewById(R.id.imuText)
        gestureText = findViewById(R.id.gestureText)
        btnCamera = findViewById(R.id.btnCamera)
        btnSyncTasks = findViewById(R.id.btnDisplay)
        cameraPreview = findViewById(R.id.cameraPreview)

        // Task UI bindings
        taskInput = findViewById(R.id.taskInput)
        btnAddTask = findViewById(R.id.btnAddTask)
        taskListContainer = findViewById(R.id.taskListContainer)
        btnClearTasks = findViewById(R.id.btnClearTasks)
        btnGenerateReport = findViewById(R.id.btnGenerateReport)

        btnSyncTasks.text = "Sync GlassTasks"
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        requestPermissionLauncher = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { if (it == PermissionStatus.Granted) startCameraStream() }
        }

        findViewById<Button>(R.id.registerButton).setOnClickListener { Wearables.startRegistration(this) }
        findViewById<Button>(R.id.resetButton).setOnClickListener { forceReset() }
        btnCamera.setOnClickListener { checkCameraPermissionAndStart() }
        btnSyncTasks.setOnClickListener { 
            if (taskList.isEmpty()) {
                Toast.makeText(this, "Add some tasks first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pushActiveTaskToGlasses()
            startGlassesListening()
        }

        btnAddTask.setOnClickListener {
            val title = taskInput.text.toString()
            if (title.isNotBlank()) {
                saveTask(title)
                taskInput.setText("")
            }
        }

        btnClearTasks.setOnClickListener {
            lifecycleScope.launch {
                database.taskDao().deleteAllTasks()
                currentTaskIndex = 0
            }
        }

        btnGenerateReport.setOnClickListener {
            generatePdfReport()
        }

        setupCameraSurface()
        setupVoiceControl()
        startObservingStates()
        observeDatabase()
    }

    private fun observeDatabase() {
        lifecycleScope.launch {
            database.taskDao().getAllTasks().collect { tasks ->
                taskList.clear()
                taskList.addAll(tasks)
                refreshTaskListView()
            }
        }
    }

    private fun saveTask(title: String) {
        lifecycleScope.launch {
            database.taskDao().insertTask(GlassTaskEntity(title = title))
        }
    }

    private fun updateTask(task: GlassTaskEntity) {
        lifecycleScope.launch {
            database.taskDao().updateTask(task)
        }
    }

    private fun refreshTaskListView() {
        taskListContainer.removeAllViews()
        taskList.forEachIndexed { index, task ->
            val taskContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    if (task.evidencePath != null) {
                        showFullImage(task.evidencePath)
                    }
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(12, 12).apply { marginEnd = 16 }
                setBackgroundColor(if (index == currentTaskIndex) Color.BLUE else Color.LTGRAY)
            }

            val taskTitle = TextView(this).apply {
                text = "${task.title} [${task.status}]"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setTextColor(if (task.status == "COMPLETED" || task.status == "EVIDENCE CAPTURED") Color.parseColor("#2E7D32") else Color.BLACK)
            }
            
            // Priority Tag
            val priorityTag = TextView(this).apply {
                text = when(task.priority) {
                    3 -> "HIGH"
                    2 -> "MED"
                    else -> "LOW"
                }
                textSize = 10f
                setPadding(8, 2, 8, 2)
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 4f
                    setColor(when(task.priority) {
                        3 -> Color.RED
                        2 -> Color.parseColor("#FFA000")
                        else -> Color.GRAY
                    })
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
            }

            row.addView(indicator)
            row.addView(taskTitle)
            row.addView(priorityTag)
            
            taskContainer.addView(row)

            // Results Container
            val resultContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 8, 16, 8)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5"))
                    cornerRadius = 8f
                    setStroke(1, Color.LTGRAY)
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { 
                    topMargin = 4
                    leftMargin = 28
                }
            }

            val label = TextView(this).apply {
                text = "VOICE REPORT:"
                textSize = 10f
                setTextColor(Color.GRAY)
                setTypeface(null, Typeface.BOLD)
            }

            val resultBox = TextView(this).apply {
                text = task.results ?: "Waiting for report..."
                textSize = 14f
                setTextColor(if (task.results != null) Color.parseColor("#1976D2") else Color.LTGRAY)
            }
            
            resultContainer.addView(label)
            resultContainer.addView(resultBox)
            taskContainer.addView(resultContainer)

            if (task.evidencePath != null) {
                val preview = ImageView(this).apply {
                    val bitmap = BitmapFactory.decodeFile(task.evidencePath)
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        400
                    ).apply {
                        topMargin = 12
                        setMargins(28, 12, 0, 0)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                taskContainer.addView(preview)
            }

            taskListContainer.addView(taskContainer)
        }
    }

    private fun showFullImage(path: String) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(this).apply {
            val bitmap = BitmapFactory.decodeFile(path)
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(imageView)
        dialog.show()
    }

    private fun generatePdfReport() {
        if (taskList.isEmpty()) {
            Toast.makeText(this, "No tasks to report!", Toast.LENGTH_SHORT).show()
            return
        }

        val pdfDocument = PdfDocument()
        val paint = Paint()
        val titlePaint = Paint().apply {
            textSize = 24f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            textSize = 14f
        }

        var pageNumber = 1
        var myPageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
        var myPage = pdfDocument.startPage(myPageInfo)
        var canvas = myPage.canvas
        var y = 50f

        canvas.drawText("GLASSTASKS WORK REPORT", 50f, y, titlePaint)
        y += 40f

        taskList.forEach { task ->
            if (y > 750) {
                pdfDocument.finishPage(myPage)
                pageNumber++
                myPageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                myPage = pdfDocument.startPage(myPageInfo)
                canvas = myPage.canvas
                y = 50f
            }

            canvas.drawText("Task: ${task.title}", 50f, y, Paint(titlePaint).apply { textSize = 18f })
            y += 25f
            canvas.drawText("Status: ${task.status}", 50f, y, bodyPaint)
            y += 20f
            canvas.drawText("Voice Note: ${task.results ?: "None"}", 50f, y, bodyPaint)
            y += 30f

            if (task.evidencePath != null) {
                val bitmap = BitmapFactory.decodeFile(task.evidencePath)
                if (bitmap != null) {
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 300, 225, true)
                    if (y + 230 > 800) {
                        pdfDocument.finishPage(myPage)
                        pageNumber++
                        myPageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                        myPage = pdfDocument.startPage(myPageInfo)
                        canvas = myPage.canvas
                        y = 50f
                    }
                    canvas.drawBitmap(scaledBitmap, 50f, y, paint)
                    y += 250f
                }
            }
            y += 20f
        }

        pdfDocument.finishPage(myPage)

        val file = File(getExternalFilesDir(null), "WorkReport_${System.currentTimeMillis()}.pdf")
        try {
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(this, "Report Generated!", Toast.LENGTH_SHORT).show()
            sharePdf(file)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating PDF", e)
            Toast.makeText(this, "Error generating PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        pdfDocument.close()
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Work Report"))
    }

    private fun saveEvidenceToFile(bitmap: Bitmap, taskId: Int): String? {
        val file = File(getExternalFilesDir(null), "evidence_$taskId.jpg")
        return try {
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save evidence", e)
            null
        }
    }

    private fun setupVoiceControl() {
        Log.d(TAG, "Setting up voice control...")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        initializeSpeechRecognizer()
    }

    private fun initializeSpeechRecognizer() {
        runOnUiThread {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d(TAG, "Voice Results: $matches")
                    matches?.firstOrNull()?.let { processVoiceCommand(it.lowercase()) }
                    startGlassesListening()
                }
                override fun onReadyForSpeech(p0: Bundle?) { Log.d(TAG, "Mike: Ready") }
                override fun onBeginningOfSpeech() { Log.d(TAG, "Mike: Listening...") }
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() { Log.d(TAG, "Mike: Heard") }
                override fun onError(p0: Int) { 
                    if (p0 == SpeechRecognizer.ERROR_NO_MATCH || p0 == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || p0 == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        startGlassesListening() 
                    }
                }
                override fun onPartialResults(p0: Bundle?) {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
        }
    }

    private fun startGlassesListening() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        runOnUiThread { speechRecognizer?.startListening(intent) }
    }

    private fun processVoiceCommand(command: String) {
        Log.d(TAG, "Voice command: $command")
        runOnUiThread { 
            gestureText.text = "Glasses Heard: $command" 
            
            when {
                command.contains("next") -> {
                    if (taskList.isNotEmpty()) {
                        currentTaskIndex = (currentTaskIndex + 1) % taskList.size
                        refreshTaskListView()
                        pushActiveTaskToGlasses()
                    }
                }
                command.contains("previous") || command.contains("back") -> {
                    if (taskList.isNotEmpty()) {
                        currentTaskIndex = if (currentTaskIndex - 1 < 0) taskList.size - 1 else currentTaskIndex - 1
                        refreshTaskListView()
                        pushActiveTaskToGlasses()
                    }
                }
                command.contains("capture") || command.contains("photo") || command.contains("this") -> {
                    captureEvidence()
                }
                command.contains("complete") || command.contains("done") -> {
                    if (taskList.isNotEmpty()) {
                        val task = taskList[currentTaskIndex].copy(status = "COMPLETED")
                        updateTask(task)
                        pushActiveTaskToGlasses()
                    }
                }
                command.contains("result") || command.contains("report") || command.contains("note") -> {
                    if (taskList.isNotEmpty()) {
                        val resultText = when {
                            command.contains("results of the task are") -> command.substringAfter("results of the task are")
                            command.contains("results are") -> command.substringAfter("results are")
                            command.contains("result") -> command.substringAfter("result")
                            command.contains("report") -> command.substringAfter("report")
                            command.contains("note") -> command.substringAfter("note")
                            else -> ""
                        }.trim()
                        
                        if (resultText.isNotEmpty()) {
                            val task = taskList[currentTaskIndex].copy(results = resultText)
                            updateTask(task)
                            pushActiveTaskToGlasses()
                            Toast.makeText(this@MainActivity, "Reported: $resultText", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun captureEvidence() {
        val stream = activeStream ?: run {
            checkCameraPermissionAndStart()
            return
        }
        
        runOnUiThread { Toast.makeText(this, "Mike capturing...", Toast.LENGTH_SHORT).show() }
        lifecycleScope.launch {
            stream.capturePhoto().onSuccess { photo ->
                val bitmap = when (photo) {
                    is PhotoData.Bitmap -> photo.bitmap
                    is PhotoData.HEIC -> BitmapFactory.decodeStream(photo.data.array().inputStream())
                }

                if (bitmap != null) {
                    val path = saveEvidenceToFile(bitmap, taskList[currentTaskIndex].id)
                    val task = taskList[currentTaskIndex].copy(evidencePath = path, status = "EVIDENCE CAPTURED")
                    updateTask(task)
                    runOnUiThread { 
                        Toast.makeText(this@MainActivity, "Saved to Task #${task.id}", Toast.LENGTH_SHORT).show()
                        pushActiveTaskToGlasses()
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "Capture failed: $error")
            }
        }
    }

    private fun pushActiveTaskToGlasses() {
        if (taskList.isEmpty()) return
        val task = taskList[currentTaskIndex]
        Log.d(TAG, "Pushing task to HUD: ${task.title}")
        
        startSession { session ->
            lifecycleScope.launch {
                session.addDisplay(DisplayConfiguration()).onSuccess { display ->
                    activeDisplay = display
                    launch {
                        display.state.collect { state ->
                            if (state == DisplayState.STARTED) {
                                delay(300)
                                renderTaskHUD(display, task)
                                this.cancel()
                            }
                        }
                    }
                }.onFailure { error, _ ->
                    activeDisplay?.let { renderTaskHUD(it, task) }
                }
            }
        }
    }

    private fun renderTaskHUD(display: Display, task: GlassTaskEntity) {
        val nextTask = if (currentTaskIndex + 1 < taskList.size) taskList[currentTaskIndex + 1] else null

        lifecycleScope.launch {
            display.sendContent {
                flexBox(Direction.COLUMN, 8, Alignment.CENTER, Alignment.CENTER, false, 1, null, null, null, null, FlexBoxBackground.CARD, null) {
                    text("GLASSTASKS", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.START)
                    text("CURRENT:", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                    text(task.title.uppercase(), TextStyle.HEADING, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)

                    val statusText = if (task.status == "EVIDENCE CAPTURED") "✓ PHOTO SAVED" else task.status
                    val statusColor = if (task.status == "PENDING") TextColor.SECONDARY else TextColor.PRIMARY
                    text(statusText, TextStyle.BODY, statusColor, 0f, 0f, Alignment.CENTER)

                    if (task.results != null) {
                        text("REPORTED: ${task.results.uppercase()}", TextStyle.META, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                        text("SAY 'COMPLETE' TO FINISH", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                    } else {
                        text("SAY 'REPORT [MSG]'", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                    }

                    text("────────", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)

                    if (nextTask != null) {
                        text("UP NEXT:", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                        text(nextTask.title, TextStyle.BODY, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                    } else {
                        text("END OF LIST", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                    }
                }
            }
        }
    }

    private fun startSession(onSuccess: (DeviceSession) -> Unit) {
        val session = activeSession
        if (session?.state?.value == DeviceSessionState.STARTED) { onSuccess(session); return }
        lifecycleScope.launch {
            val deviceId = Wearables.devices.value.firstOrNull() ?: return@launch
            Wearables.createSession(SpecificDeviceSelector(deviceId)).onSuccess {
                activeSession = it
                it.start()
                launch {
                    it.state.collect { state ->
                        if (state == DeviceSessionState.STARTED) { runOnUiThread { onSuccess(it) }; this.cancel() }
                    }
                }
            }
        }
    }

    private fun startCameraStream() {
        runOnUiThread { cameraPreview.visibility = View.VISIBLE }
        startSession { session ->
            lifecycleScope.launch {
                val config = StreamConfiguration(VideoQuality.MEDIUM, 30, true)
                session.addStream(config).onSuccess { stream ->
                    activeStream = stream
                    stream.start().onSuccess {
                        launch { stream.videoStream.collect { renderFrame(it) } }
                    }
                }
            }
        }
    }

    private fun renderFrame(frame: VideoFrame) {
        val surface = previewSurface ?: return
        frame.buffer.rewind()
        if (frame.isCodecConfig) { initializeDecoder(frame, surface); return }
        val decoder = videoDecoder ?: return
        try {
            val inputIndex = decoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(frame.buffer)
                decoder.queueInputBuffer(inputIndex, 0, frame.buffer.remaining(), frame.presentationTimeUs, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outputIndex = decoder.dequeueOutputBuffer(info, 10000)
            while (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
                outputIndex = decoder.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {}
    }

    private fun initializeDecoder(frame: VideoFrame, surface: Surface) {
        releaseDecoder()
        try {
            val mime = if (isHEVC(frame)) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, frame.width, frame.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (frame.isCodecConfig) format.setByteBuffer("csd-0", frame.buffer)
            videoDecoder = MediaCodec.createDecoderByType(mime)
            videoDecoder?.configure(format, surface, null, 0)
            videoDecoder?.start()
        } catch (e: Exception) {}
    }

    private fun isHEVC(frame: VideoFrame): Boolean {
        if (frame.buffer.remaining() < 5) return false
        val b = frame.buffer.get(4).toInt()
        return (b and 0x7E) shr 1 == 32 || (b and 0x7E) shr 1 == 33
    }

    private fun releaseDecoder() {
        try { videoDecoder?.stop(); videoDecoder?.release() } catch (e: Exception) {}
        videoDecoder = null
    }

    private fun setupCameraSurface() {
        cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { previewSurface = Surface(s) }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                previewSurface?.release(); previewSurface = null; releaseDecoder(); return true
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun startObservingStates() {
        lifecycleScope.launch {
            launch { Wearables.registrationState.collect { statusText.text = "GlassTasks: $it" } }
            launch {
                Wearables.devices.collect { devices ->
                    devicesText.text = if (devices.isEmpty()) "Connect Mike..." else "Mike Ready for GlassTasks"
                }
            }
        }
    }

    private fun forceReset() {
        lifecycleScope.launch {
            activeSession?.stop(); Wearables.reset()
            delay(1000); Wearables.initialize(applicationContext); startObservingStates()
        }
    }

    private fun checkCameraPermissionAndStart() {
        lifecycleScope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess { status ->
                if (status == PermissionStatus.Granted) startCameraStream()
                else requestPermissionLauncher.launch(Permission.CAMERA)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rot = FloatArray(9); val orient = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rot, event.values)
            SensorManager.getOrientation(rot, orient)
            runOnUiThread { imuText.text = String.format(Locale.US, "Pitch: %.1f | Yaw: %.1f", 
                Math.toDegrees(orient[1].toDouble()), Math.toDegrees(orient[0].toDouble())) }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onResume() { super.onResume(); rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); speechRecognizer?.destroy(); releaseDecoder(); activeSession?.stop() }
}
