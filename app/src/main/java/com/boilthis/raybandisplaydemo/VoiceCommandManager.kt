package com.boilthis.raybandisplaydemo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import java.util.Locale

class VoiceCommandManager(
    private val context: Context,
    private val onCommandDetected: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private val baseIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }

    private var isListening = false
    private var isDictationMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startListening(dictation: Boolean = false) {
        mainHandler.post {
            if (isListening) {
                Log.d("VoiceCommandManager", "Already listening, ignoring start request.")
                return@post
            }
            
            isDictationMode = dictation
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    speechRecognizer?.setRecognitionListener(this)
                }
                
                Log.d("VoiceCommandManager", "Starting speech recognizer (dictation=$dictation)...")
                speechRecognizer?.startListening(baseIntent)
                isListening = true
                onListeningStateChanged(true)
            } catch (e: Exception) {
                Log.e("VoiceCommandManager", "Failed to start recognition", e)
                Toast.makeText(context, "Voice Error: ${e.message}", Toast.LENGTH_LONG).show()
                onListeningStateChanged(false)
                isListening = false
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            Log.d("VoiceCommandManager", "Stopping listener...")
            speechRecognizer?.stopListening()
            isListening = false
            onListeningStateChanged(false)
        }
    }

    fun destroy() {
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d("VoiceCommandManager", "onReadyForSpeech")
    }

    override fun onBeginningOfSpeech() {
        Log.d("VoiceCommandManager", "onBeginningOfSpeech")
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d("VoiceCommandManager", "onEndOfSpeech")
        isListening = false
        onListeningStateChanged(false)
    }

    override fun onError(error: Int) {
        val msg = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions missing"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Busy"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
            else -> "Error $error"
        }
        Log.e("VoiceCommandManager", "onError: $msg ($error)")
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            Toast.makeText(context, "Voice: $msg", Toast.LENGTH_SHORT).show()
        }
        isListening = false
        onListeningStateChanged(false)
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        matches?.firstOrNull()?.let { match ->
            Log.d("VoiceCommandManager", "Final result: $match")
            if (isDictationMode) {
                onCommandDetected("DICTATION:$match")
            } else {
                processText(match)
            }
        }
        isListening = false
        onListeningStateChanged(false)
    }

    private var lastProcessedTime = 0L

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        matches?.firstOrNull()?.let { match ->
            val currentTime = System.currentTimeMillis()
            // Throttle partial result processing to once every 500ms
            if (currentTime - lastProcessedTime > 500) {
                lastProcessedTime = currentTime
                Log.v("VoiceCommandManager", "Partial: $match")
                if (!isDictationMode) {
                    processText(match)
                }
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun processText(text: String) {
        val lowerText = text.lowercase()
        val commands = listOf("capture", "review", "next", "previous", "back", "complete", "done")
        
        for (cmd in commands) {
            if (lowerText.contains(cmd)) {
                onCommandDetected(cmd)
                stopListening()
                return
            }
        }
    }
}
