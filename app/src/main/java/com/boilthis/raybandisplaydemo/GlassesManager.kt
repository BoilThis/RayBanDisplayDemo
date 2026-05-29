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
// No longer needed

import java.util.Date
// No longer needed


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
    
    // Subtask navigation
    var onSubPrevClick: (() -> Unit)? = null
    var onSubNextClick: (() -> Unit)? = null

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
            
            // Explicitly request camera stream capability and keep it warm
            val streamResult = session.addStream(StreamConfiguration(videoQuality = VideoQuality.HIGH))
            val stream = streamResult.getOrNull()
            activeStream = stream
            Log.d("GlassesManager", "Stream capability added: ${activeStream != null}")
            
            // Warm up the stream immediately for instant capture
            stream?.start()

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

    private var isCapturing = false

    fun renderTaskHUD(task: GlassTask, currentIndex: Int, totalTasks: Int, focusedSubTaskIndex: Int = -1) {
        if (activeDisplay?.state?.value != DisplayState.STARTED) return
        if (activeSession?.state?.value != DeviceSessionState.STARTED) return
        
        // SUSPEND UPDATES DURING CAPTURE TO PREVENT BLUETOOTH CONGESTION
        if (isCapturing) return

        renderJob?.cancel()
        renderJob = lifecycleOwner.lifecycleScope.launch {
            activeDisplay?.let { display ->
                try {
                        // No longer needed
                    // No longer needed
                    
                    display.sendContent {
                        // STRUCTURED CARD-BASED HUD
                        flexBox(Direction.COLUMN, 4, Alignment.CENTER, Alignment.CENTER, false, 1, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                            
                            // 1. Objective Card
                            flexBox(Direction.COLUMN, 4, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.CARD, null) {
                                text("OBJECTIVE", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                                text(task.title.uppercase(), TextStyle.HEADING, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                                flexBox(Direction.ROW, 20, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                    button("PREV", ButtonStyle.SECONDARY, null, { onPrevClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                    text("${currentIndex + 1}/${totalTasks}", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                                    button("NEXT", ButtonStyle.SECONDARY, null, { onNextClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                }
                            }

                            // 2. Action Card (Subtask + Actions)
                            flexBox(Direction.COLUMN, 4, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.CARD, null) {
                                val focusIdx = if (focusedSubTaskIndex == -1) 0 else focusedSubTaskIndex
                                val sub = task.subtasks.getOrNull(focusIdx)
                                if (sub != null) {
                                    text("SUBTASK ${focusIdx + 1}/${task.subtasks.size}", TextStyle.META, TextColor.SECONDARY, 0f, 0f, Alignment.CENTER)
                                    text(sub.title.uppercase(), TextStyle.BODY, TextColor.PRIMARY, 0f, 0f, Alignment.CENTER)
                                }
                                
                                // Action Center
                                flexBox(Direction.ROW, 10, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                    val hasPhoto = sub?.capturedImagePath != null
                                    val hasVoice = sub?.voiceNote != null
                                    
                                    button("PHOTO", if (hasPhoto) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY, IconName.VIDEO_CAMERA, { onCaptureClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                    button("LOG", if (hasVoice) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY, IconName.BULLHORN, { onReviewClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                    button("DONE", ButtonStyle.PRIMARY, IconName.CHECKMARK_CIRCLE, { onCompleteClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                }
                                
                                // Subtask Nav
                                flexBox(Direction.ROW, 20, Alignment.CENTER, Alignment.CENTER, false, 0, null, null, 0, 0, FlexBoxBackground.NONE, null) {
                                    button("SUB-PREV", ButtonStyle.SECONDARY, null, { onSubPrevClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                    button("SUB-NEXT", ButtonStyle.SECONDARY, null, { onSubNextClick?.invoke() }, 0f, 0f, Alignment.CENTER)
                                }
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
        val session = activeSession ?: run { Log.e("GlassesManager", "No active session"); return null }
        if (session.state.value != DeviceSessionState.STARTED) { Log.e("GlassesManager", "Session not started"); return null }

        isCapturing = true // HALT HUD UPDATES

        // Try to re-use existing stream
        var stream = activeStream
        if (stream == null || stream.state.value == StreamState.STOPPED) {
            val result = session.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM))
            stream = result.getOrNull()
            activeStream = stream
        }

        if (stream == null) {
            Log.e("GlassesManager", "Failed to get stream")
            isCapturing = false
            return null
        }
        
        return try {
            stream.start()
            
            // Warmup
            var ready = false
            for (i in 0 until 30) {
                // dummy
                val state = stream.state.value
                if (state == StreamState.STARTED || state == StreamState.STREAMING) {
                    ready = true
                    break
                }
                delay(200)
            }

            val photo = if (ready) stream.capturePhoto().getOrNull() else null
            
            // Re-enable HUD
            isCapturing = false
            
            // Explicitly hint that the stream is no longer needed to free resources
            // This forces a clean up of any buffered state
            stream.stop()
            
            photo
        } catch (e: Exception) {
            Log.e("GlassesManager", "Capture error", e)
            stream.stop()
            isCapturing = false
            null
        }
    }

    fun showListeningState() {
        if (activeDisplay?.state?.value != DisplayState.STARTED) return
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
