package com.voicekeyboard.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.voicekeyboard.MainActivity
import com.voicekeyboard.shared.KeyboardStateManager
import com.voicekeyboard.shared.SharedApiClient
import com.voicekeyboard.shared.SharedAudioRecorder
import kotlinx.coroutines.*

/**
 * Native Module bridge to expose keyboard functionality to React Native
 */
class VoiceKeyboardModule(reactContext: ReactApplicationContext) : 
    ReactContextBaseJavaModule(reactContext) {
    
    companion object {
        const val NAME = "VoiceKeyboardModule"
        const val PERMISSION_REQUEST_CODE = 1001
    }
    
    private val stateManager: KeyboardStateManager by lazy {
        KeyboardStateManager.getInstance(reactApplicationContext)
    }
    
    private val apiClient: SharedApiClient by lazy {
        SharedApiClient().apply {
            setBaseUrl(stateManager.apiUrl)
        }
    }
    
    private val audioRecorder: SharedAudioRecorder by lazy {
        SharedAudioRecorder(reactApplicationContext).apply {
            onRecordingStarted = {
                sendEvent("onRecordingStarted", null)
            }
            onRecordingStopped = { wavData ->
                // Transcribe and send result
                CoroutineScope(Dispatchers.IO).launch {
                    val base64 = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
                    val result = apiClient.transcribe(base64)
                    
                    withContext(Dispatchers.Main) {
                        val params = Arguments.createMap().apply {
                            putBoolean("success", result.success)
                            putString("text", result.text)
                            putString("error", result.error)
                        }
                        sendEvent("onTranscriptionResult", params)
                    }
                }
            }
            onAudioData = { data ->
                // Could emit audio level for visualizations
            }
            onError = { error ->
                val params = Arguments.createMap().apply {
                    putString("error", error)
                }
                sendEvent("onRecordingError", params)
            }
        }
    }
    
    override fun getName(): String = NAME
    
    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }
    
    // ==================== Recording Methods ====================
    
    @ReactMethod
    fun startRecording(promise: Promise) {
        try {
            val success = audioRecorder.startRecording()
            promise.resolve(success)
        } catch (e: Exception) {
            promise.reject("RECORDING_ERROR", e.message)
        }
    }
    
    @ReactMethod
    fun stopRecording(promise: Promise) {
        try {
            val wavData = audioRecorder.stopRecording()
            if (wavData != null) {
                val base64 = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
                promise.resolve(base64)
            } else {
                promise.resolve(null)
            }
        } catch (e: Exception) {
            promise.reject("RECORDING_ERROR", e.message)
        }
    }
    
    @ReactMethod
    fun isRecording(promise: Promise) {
        promise.resolve(audioRecorder.isRecording())
    }
    
    @ReactMethod
    fun hasPermission(promise: Promise) {
        promise.resolve(audioRecorder.hasPermission())
    }
    
    // ==================== Transcription Methods ====================
    
    @ReactMethod
    fun transcribe(base64Audio: String, promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = apiClient.transcribe(base64Audio)
                withContext(Dispatchers.Main) {
                    val response = Arguments.createMap().apply {
                        putBoolean("success", result.success)
                        putString("text", result.text)
                        putString("error", result.error)
                    }
                    promise.resolve(response)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("TRANSCRIPTION_ERROR", e.message)
                }
            }
        }
    }
    
    @ReactMethod
    fun pingServer(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isReachable = apiClient.ping()
                withContext(Dispatchers.Main) {
                    promise.resolve(isReachable)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("PING_ERROR", e.message)
                }
            }
        }
    }
    
    // ==================== Settings Methods ====================
    
    @ReactMethod
    fun setApiUrl(url: String, promise: Promise) {
        try {
            stateManager.apiUrl = url
            apiClient.setBaseUrl(url)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SETTINGS_ERROR", e.message)
        }
    }
    
    @ReactMethod
    fun getApiUrl(promise: Promise) {
        promise.resolve(stateManager.apiUrl)
    }
    
    @ReactMethod
    fun getSettings(promise: Promise) {
        try {
            val settings = Arguments.createMap().apply {
                putString("apiUrl", stateManager.apiUrl)
                putString("keyboardMode", stateManager.keyboardMode.name)
                putBoolean("autoSend", stateManager.autoSend)
                putBoolean("hapticFeedback", stateManager.hapticFeedback)
                putBoolean("soundFeedback", stateManager.soundFeedback)
                putInt("maxRecordingDuration", stateManager.maxRecordingDuration)
            }
            promise.resolve(settings)
        } catch (e: Exception) {
            promise.reject("SETTINGS_ERROR", e.message)
        }
    }
    
    @ReactMethod
    fun updateSettings(settings: ReadableMap, promise: Promise) {
        try {
            if (settings.hasKey("apiUrl")) {
                val url = settings.getString("apiUrl") ?: stateManager.apiUrl
                stateManager.apiUrl = url
                apiClient.setBaseUrl(url)
            }
            if (settings.hasKey("keyboardMode")) {
                val mode = settings.getString("keyboardMode")
                stateManager.keyboardMode = KeyboardStateManager.KeyboardMode.valueOf(mode ?: "VOICE")
            }
            if (settings.hasKey("autoSend")) {
                stateManager.autoSend = settings.getBoolean("autoSend")
            }
            if (settings.hasKey("hapticFeedback")) {
                stateManager.hapticFeedback = settings.getBoolean("hapticFeedback")
            }
            if (settings.hasKey("soundFeedback")) {
                stateManager.soundFeedback = settings.getBoolean("soundFeedback")
            }
            if (settings.hasKey("maxRecordingDuration")) {
                stateManager.maxRecordingDuration = settings.getInt("maxRecordingDuration")
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SETTINGS_ERROR", e.message)
        }
    }
    
    // ==================== Keyboard Management Methods ====================
    
    @ReactMethod
    fun isKeyboardEnabled(promise: Promise) {
        try {
            val packageName = reactApplicationContext.packageName
            val enabledInputMethods = Settings.Secure.getString(
                reactApplicationContext.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: ""
            
            promise.resolve(enabledInputMethods.contains(packageName))
        } catch (e: Exception) {
            promise.reject("KEYBOARD_ERROR", e.message)
        }
    }
    
    @ReactMethod
    fun openInputMethodSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    @ReactMethod
    fun openInputMethodPicker() {
        try {
            val imeManager = reactApplicationContext.getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager
            imeManager.showInputMethodPicker()
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    @ReactMethod
    fun openKeyboardSettings() {
        try {
            // Open main React Native app instead of old KeyboardSettingsActivity
            val intent = Intent(reactApplicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    @ReactMethod
    fun requestMicrophonePermission(promise: Promise) {
        try {
            val activity = reactApplicationContext.currentActivity
            if (activity == null) {
                promise.resolve(false)
                return
            }
            
            if (ContextCompat.checkSelfPermission(
                    reactApplicationContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                promise.resolve(true)
                return
            }
            
            // Request permission
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            
            // Return false - caller should check hasPermission after user responds
            promise.resolve(false)
        } catch (e: Exception) {
            promise.reject("PERMISSION_ERROR", e.message)
        }
    }
    
    @ReactMethod
    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", reactApplicationContext.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    // ==================== Event Listener Registration ====================
    
    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN event emitter
    }
    
    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN event emitter
    }
}
