package com.boilthis.raybandisplaydemo

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlassesManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private var activeSession: DeviceSession? = null
    private var activeDisplay: Display? = null
    private var activeStream: Stream? = null
    private var renderJob: Job? = null
    private var isConnecting = false
    private var connectionRetryCount = 0

    var onStatusChanged: ((String) -> Unit)? = null
    
    // Callbacks for HUD interactions
    var onCaptureClick: (() -> Unit)? = null
    var onReviewClick: (() -> Unit)? = null
    var onCompleteClick: (() -> Unit)? = null
    var onPrevClick: (() -> Unit)? = null
    var onNextClick: (() -> Unit)? = null

    fun startRegistration(activity: Activity) {
        try {
            updateStatus("STARTING REGISTRATION")
            Wearables.startRegistration(activity)
        } catch (e: Exception) {
            Log.e("GlassesManager", "Registration failed", e)
            updateStatus("REGISTRATION FAILED")
        }
    }

    fun observeDevices(onChanged: (List<DeviceIdentifier>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch {
            Wearables.devices.collect { deviceSet ->
                Log.d("GlassesManager", "Devices observed: ${deviceSet.size}")
                onChanged(deviceSet.toList())
            }
        }
    }

    fun connect(deviceId: DeviceIdentifier, onConnected: () -> Unit) {
        if (isConnecting) return
        
        if (activeSession?.state?.value == DeviceSessionState.STARTED && 
            activeDisplay?.state?.value == DisplayState.STARTED) {
            onConnected()
            return
        }

        isConnecting = true
        updateStatus("SYNCING...")
        lifecycleOwner.lifecycleScope.launch {
            try {
                if (activeSession == null || activeSession?.state?.value == DeviceSessionState.STOPPED) {
                    Wearables.createSession(SpecificDeviceSelector(deviceId)).onSuccess { session ->
                        activeSession = session
                        session.start()
                        
                        launch {
                            session.state.collect { state ->
                                if (state == DeviceSessionState.STARTED) {
                                    setupCapabilities(session, onConnected)
                                    this.cancel()
                                }
                            }
                        }
                    }.onFailure { error ->
                        if (error.toString().contains("No eligible device") && connectionRetryCount < 1) {
                            connectionRetryCount++
                            updateStatus("RECONNECTING...")
                            lifecycleOwner.lifecycleScope.launch {
                                delay(2000)
                                isConnecting = false
                                connect(deviceId, onConnected)
                            }
                        } else {
                            updateStatus("ERR: $error")
                            isConnecting = false
                        }
                    }
                } else {
                    setupCapabilities(activeSession!!, onConnected)
                }
            } catch (e: Exception) {
                updateStatus("LINK FAIL")
                isConnecting = false
            }
        }
    }

    private fun setupCapabilities(session: DeviceSession, onConnected: () -> Unit) {
        connectionRetryCount = 0
        lifecycleOwner.lifecycleScope.launch {
            updateStatus("PREPARING HUD...")
            val display = session.addDisplay(DisplayConfiguration()).getOrNull()
            activeDisplay = display
            
            // Explicitly request camera stream capability
            val streamResult = session.addStream(StreamConfiguration(videoQuality = VideoQuality.HIGH))
            activeStream = streamResult.getOrNull()
            Log.d("GlassesManager", "Stream capability added: ${activeStream != null}, error: ${streamResult.exceptionOrNull()}")

            if (display == null) {
                updateStatus("HUD ERR")
                isConnecting = false
                return@launch
            }

            launch {
                display.state.collect { state ->
                    if (state == DisplayState.STARTED) {
                        isConnecting = false
                        delay(1500)
                        updateStatus("LINK ACTIVE")
                        onConnected()
                        this.cancel()
                    }
                }
            }
        }
    }

    fun renderTaskHUD(task: GlassTask, currentIndex: Int, totalTasks: Int) {
        if (activeDisplay?.state?.value != DisplayState.STARTED) return

        renderJob?.cancel()
        renderJob = lifecycleOwner.lifecycleScope.launch {
            activeDisplay?.let { display ->
                try {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val deptName = task.department.uppercase()
                    
                    display.sendContent {
                        // ULTRA-ELITE COMMAND HUD LAYOUT
                        flexBox(Direction.COLUMN, 8, Alignment.CENTER, Alignment.CENTER, false, 1, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                            
                            // Top Meta-Data Row
                            flexBox(Direction.ROW, 100, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                text("W-ELITE v1.0", TextStyle.META, TextColor.PRIMARY, 0f, 0f, Alignment.START)
                                text(time, TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.END)
                            }

                            text(deptName, TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                            
                            if (task.priority == 3) {
                                flexBox(Direction.ROW, 6, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                    icon(IconName.EYE, IconStyle.FILLED, 0f, 0f, Alignment.CENTER)
                                    text("HIGH PRIORITY AUDIT", TextStyle.META, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                                }
                            } else {
                                text("STANDARD LOG", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                            }

                            // Primary Task Objective
                            text(task.title.uppercase(), TextStyle.HEADING, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)

                            val statusIcon = when (task.status) {
                                "COMPLETED" -> IconName.CHECKMARK_CIRCLE
                                "EVIDENCE CAPTURED" -> IconName.LIGHT_BULB
                                else -> IconName.CIRCLE_HANDLE
                            }

                            flexBox(Direction.ROW, 10, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                icon(statusIcon, IconStyle.FILLED, 0f, 0f, Alignment.CENTER)
                                text(task.status, TextStyle.BODY, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                            }

                            if (task.voiceNote != null) {
                                text("“${task.voiceNote}”", TextStyle.BODY, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                            }
                            
                            text("━━━━━━━━━━━━━━━━", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                            
                            // High-Tech Action Center
                            flexBox(Direction.ROW, 12, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                button("PHOTO", ButtonStyle.PRIMARY, IconName.VIDEO_CAMERA, { onCaptureClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                button("LOG", ButtonStyle.SECONDARY, IconName.BULLHORN, { onReviewClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                button("CLOSE", ButtonStyle.PRIMARY, IconName.CHECKMARK_CIRCLE, { onCompleteClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                            }
                            
                            text("━━━━━━━━━━━━━━━━", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                            
                            // Dynamic Navigation Map
                            flexBox(Direction.ROW, 60, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                button("PREV", ButtonStyle.SECONDARY, null, { onPrevClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                text("${currentIndex + 1}/${totalTasks}", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                                button("NEXT", ButtonStyle.SECONDARY, null, { onNextClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GlassesManager", "HUD Render failed", e)
                }
            }
        }
    }

    suspend fun takePhoto(): PhotoData? {
        val stream = activeStream
        if (stream == null) {
            Log.e("GlassesManager", "Cannot take photo: activeStream is null")
            return null
        }

        try {
            Log.d("GlassesManager", "Current stream state: ${stream.state.value}")
            
            // PRE-CAPTURE WARMUP: Ensure stream is active and stable
            if (stream.state.value != StreamState.STARTED && stream.state.value != StreamState.STREAMING) {
                Log.d("GlassesManager", "Initiating camera warmup...")
                stream.start()
                
                // Increased polling with better condition checks
                var waitCount = 0
                while (waitCount < 15) { // Wait up to 4.5 seconds (300ms * 15)
                    val state = stream.state.value
                    Log.d("GlassesManager", "Warmup poll $waitCount: State=$state")
                    if (state == StreamState.STARTED || state == StreamState.STREAMING) {
                        break
                    }
                    delay(300)
                    waitCount++
                }
            }
            
            // Secondary delay for hardware stabilization after state change
            delay(500)
            
            Log.d("GlassesManager", "Triggering capture. Final state: ${stream.state.value}")
            val captureResult = stream.capturePhoto()
            
            if (captureResult.isFailure) {
                Log.e("GlassesManager", "Capture failed: ${captureResult.exceptionOrNull()}")
            }

            return captureResult.getOrNull()
        } catch (e: Exception) {
            Log.e("GlassesManager", "Exception during takePhoto", e)
            return null
        }
    }

    fun showListeningState() {
        lifecycleOwner.lifecycleScope.launch {
            activeDisplay?.let { display ->
                display.sendContent {
                    flexBox(Direction.COLUMN, 10, Alignment.CENTER, Alignment.CENTER, false, 1, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                        icon(IconName.BULLHORN, IconStyle.FILLED, 0f, 0f, Alignment.CENTER)
                        text("TRANSCRIPTION ACTIVE", TextStyle.HEADING, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                        text("Speak audit notes clearly...", TextStyle.BODY, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                    }
                }
            }
        }
    }

    private fun updateStatus(msg: String) {
        onStatusChanged?.invoke(msg)
    }

    fun cleanup() {
        renderJob?.cancel()
        activeDisplay = null
        activeStream = null
        try {
            activeSession?.stop()
        } catch (e: Exception) {
            Log.e("GlassesManager", "Error stopping session", e)
        }
        activeSession = null
        isConnecting = false
    }

    fun resetAndConnect(deviceId: DeviceIdentifier, onConnected: () -> Unit) {
        cleanup()
        connect(deviceId, onConnected)
    }
}
