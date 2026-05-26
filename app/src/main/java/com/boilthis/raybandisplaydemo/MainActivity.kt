package com.boilthis.raybandisplaydemo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var statusText: TextView
    private lateinit var devicesText: TextView
    private lateinit var registerButton: Button
    private lateinit var resetButton: Button
    private lateinit var updateGlassesButton: Button
    private lateinit var btnCamera: Button
    private lateinit var btnDisplay: Button
    private lateinit var cameraPreview: TextureView

    private var activeSession: DeviceSession? = null
    private var activeStream: Stream? = null
    private var activeDisplay: Display? = null
    private var videoDecoder: MediaCodec? = null
    private var previewSurface: Surface? = null

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Permission>
    private var observationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        devicesText = findViewById(R.id.devicesText)
        registerButton = findViewById(R.id.registerButton)
        resetButton = findViewById(R.id.resetButton)
        updateGlassesButton = findViewById(R.id.updateGlassesButton)
        btnCamera = findViewById(R.id.btnCamera)
        btnDisplay = findViewById(R.id.btnDisplay)
        cameraPreview = findViewById(R.id.cameraPreview)

        requestPermissionLauncher = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { status ->
                if (status == PermissionStatus.Granted) {
                    Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
                    startCameraStream()
                } else {
                    Toast.makeText(this, "Permission denied!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error, _ ->
                Toast.makeText(this, "Permission request failed: $error", Toast.LENGTH_SHORT).show()
            }
        }

        registerButton.setOnClickListener {
            if (hasBluetoothPermission()) {
                startRegistration()
            } else {
                requestBluetoothPermission()
            }
        }

        resetButton.setOnClickListener {
            forceReset()
        }

        updateGlassesButton.setOnClickListener {
            Wearables.openDATGlassesAppUpdate(this)
            Toast.makeText(this, "Opening Meta View to update glasses...", Toast.LENGTH_SHORT).show()
        }

        btnCamera.setOnClickListener {
            checkCameraPermissionAndStart()
        }

        btnDisplay.setOnClickListener {
            sendDashboardToGlass()
        }

        cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                previewSurface = Surface(surface)
                Log.d(TAG, "Surface texture available")
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurface?.release()
                previewSurface = null
                releaseDecoder()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        if (!hasBluetoothPermission()) {
            requestBluetoothPermission()
        }

        startObservingStates()
    }

    private fun startObservingStates() {
        observationJob?.cancel()
        observationJob = lifecycleScope.launch {
            // Observe registration state
            launch {
                Wearables.registrationState.collect { state ->
                    runOnUiThread {
                        statusText.text = "Registration State: $state"
                    }
                }
            }

            // Observe connected devices and their metadata (names)
            launch {
                val deviceInfos = mutableMapOf<String, String>()
                Wearables.devices.collect { devices ->
                    if (devices.isEmpty()) {
                        runOnUiThread { devicesText.text = "Looking for Mike..." }
                        deviceInfos.clear()
                    } else {
                        devices.forEach { deviceId ->
                            val deviceStateFlow = Wearables.devicesMetadata[deviceId]
                            launch {
                                deviceStateFlow?.collect { device ->
                                    val capability = if (device.isDisplayCapable()) "[Display Capable]" else "[No Display]"
                                    // If name hasn't updated yet, keep lookin
                                    val displayName = if (device.name.startsWith("Meta RB")) "Mike (Identifying...)" else device.name
                                    val info = "$displayName $capability"
                                    
                                    Log.d(TAG, "Device metadata updated: ID=${deviceId.identifier}, Name=${device.name}")
                                    deviceInfos[deviceId.identifier] = info
                                    runOnUiThread {
                                        devicesText.text = "Connected Device:\n" + deviceInfos.values.joinToString("\n")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun forceReset() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Resetting connection to Mike...", Toast.LENGTH_SHORT).show()
            activeSession?.stop()
            activeSession = null
            activeStream = null
            activeDisplay = null
            releaseDecoder()
            
            Wearables.reset()
            delay(1000) // Give the system a second to clear
            Wearables.initialize(applicationContext)
            
            startObservingStates()
        }
    }

    private fun checkCameraPermissionAndStart() {
        lifecycleScope.launch {
            val result = Wearables.checkPermissionStatus(Permission.CAMERA)
            result.onSuccess { status ->
                if (status == PermissionStatus.Granted) {
                    startCameraStream()
                } else {
                    requestPermissionLauncher.launch(Permission.CAMERA)
                }
            }.onFailure { _, _ ->
                requestPermissionLauncher.launch(Permission.CAMERA)
            }
        }
    }

    private fun startSession(onSuccess: (DeviceSession) -> Unit) {
        val session = activeSession
        if (session != null && session.state.value == DeviceSessionState.STARTED) {
            Log.d(TAG, "Using existing session")
            onSuccess(session)
            return
        }

        lifecycleScope.launch {
            val devices = Wearables.devices.value
            val deviceId = devices.firstOrNull()
            if (deviceId == null) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Mike not found. Is he nearby?", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            Log.d(TAG, "Creating session for device: ${deviceId.identifier}")
            val result = Wearables.createSession(SpecificDeviceSelector(deviceId))
            result.onSuccess { session ->
                activeSession = session
                Log.d(TAG, "Session created, starting...")
                
                // Observe errors to detect update requirement
                launch {
                    session.errors.collect { error ->
                        Log.e(TAG, "Session error: $error")
                        if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                            runOnUiThread {
                                updateGlassesButton.visibility = View.VISIBLE
                                Toast.makeText(this@MainActivity, "Mike needs a toolkit update!", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                session.start()
                launch {
                    session.state.collect { state ->
                        Log.d(TAG, "Session state: $state")
                        if (state == DeviceSessionState.STARTED) {
                            runOnUiThread {
                                onSuccess(session)
                            }
                            this.cancel()
                        }
                    }
                }
            }.onFailure { error, cause ->
                Log.e(TAG, "Session failed: $error", cause)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Session failed: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startCameraStream() {
        Toast.makeText(this, "Starting camera POV...", Toast.LENGTH_SHORT).show()
        runOnUiThread {
            cameraPreview.visibility = View.VISIBLE
        }
        
        startSession { session ->
            Log.d(TAG, "Adding camera stream...")
            lifecycleScope.launch {
                val config = StreamConfiguration(VideoQuality.MEDIUM, 30, true)
                session.addStream(config).onSuccess { stream ->
                    activeStream = stream
                    Log.d(TAG, "Stream added, starting...")
                    stream.start().onSuccess {
                        Log.d(TAG, "Stream started")
                        launch {
                            stream.videoStream.collect { frame ->
                                renderFrame(frame)
                            }
                        }
                    }.onFailure { error, _ ->
                        Log.e(TAG, "Stream start failed: $error")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Stream start failed: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure { error, _ ->
                    Log.e(TAG, "Add stream failed: $error")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Add stream failed: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private var frameCount = 0
    private fun renderFrame(frame: VideoFrame) {
        val surface = previewSurface ?: return
        
        frame.buffer.rewind()
        frameCount++

        if (frame.isCodecConfig) {
            val size = frame.buffer.remaining()
            Log.d(TAG, "Received codec config frame, size: $size")
            if (size > 0) {
                initializeDecoder(frame, surface)
            }
            return
        }

        if (videoDecoder == null) {
            val size = frame.buffer.remaining()
            if (size > 0) {
                Log.d(TAG, "Initializing decoder from data frame, size: $size")
                initializeDecoder(frame, surface)
            }
            return
        }
        
        val decoder = videoDecoder!!
        try {
            val inputIndex = decoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                val size = frame.buffer.remaining()
                inputBuffer?.put(frame.buffer)
                decoder.queueInputBuffer(inputIndex, 0, size, frame.presentationTimeUs, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = decoder.dequeueOutputBuffer(info, 10000)
            
            while (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
                outputIndex = decoder.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode frame failed", e)
        }

        if (frameCount % 30 == 0) {
            runOnUiThread {
                statusText.text = "Mike POV Active: $frameCount frames"
            }
        }
    }

    private fun initializeDecoder(frame: VideoFrame, surface: Surface) {
        releaseDecoder()
        try {
            val mime = if (isHEVC(frame)) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            Log.d(TAG, "Initializing $mime decoder with ${frame.width}x${frame.height}")
            
            val format = MediaFormat.createVideoFormat(mime, frame.width, frame.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            
            if (frame.isCodecConfig) {
                format.setByteBuffer("csd-0", frame.buffer)
            }
            
            videoDecoder = MediaCodec.createDecoderByType(mime)
            videoDecoder?.configure(format, surface, null, 0)
            videoDecoder?.start()
            Log.d(TAG, "Video decoder started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Decoder initialization failed", e)
        }
    }

    private fun isHEVC(frame: VideoFrame): Boolean {
        if (frame.buffer.remaining() < 5) return false
        val b = frame.buffer.get(4).toInt()
        return (b and 0x7E) shr 1 == 32 || (b and 0x7E) shr 1 == 33
    }

    private fun releaseDecoder() {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()
        } catch (e: Exception) {}
        videoDecoder = null
    }

    private fun sendDashboardToGlass() {
        Toast.makeText(this, "Sending dashboard to Mike...", Toast.LENGTH_SHORT).show()
        startSession { session ->
            lifecycleScope.launch {
                session.addDisplay(DisplayConfiguration()).onSuccess { display ->
                    activeDisplay = display
                    display.sendContent {
                        flexBox(
                            Direction.COLUMN,
                            10,
                            Alignment.CENTER,
                            Alignment.CENTER,
                            false,
                            1,
                            null, null, null, null,
                            FlexBoxBackground.NONE,
                            null
                        ) {
                            text("Mike's Dashboard", TextStyle.HEADING, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                            text("Connection: Secure", TextStyle.BODY, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                        }
                    }.onSuccess {
                        Log.d(TAG, "Content sent to Mike")
                    }.onFailure { error, _ ->
                        Log.e(TAG, "Send content failed: $error")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Send content failed: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure { error, _ ->
                    Log.e(TAG, "Add display failed: $error")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Add display failed: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startRegistration() {
        try {
            Wearables.startRegistration(this)
            Toast.makeText(this, "Syncing with Mike...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                1
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseDecoder()
        activeSession?.stop()
    }
}
