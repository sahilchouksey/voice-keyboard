package com.voicekeyboard.shared

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state manager for keyboard settings and state.
 * Used by both React Native app and IME service.
 */
class KeyboardStateManager private constructor(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "voice_keyboard_prefs"
        private const val KEY_API_URL = "api_url"
        private const val KEY_KEYBOARD_MODE = "keyboard_mode"
        private const val KEY_AUTO_SEND = "auto_send"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_SOUND_FEEDBACK = "sound_feedback"
        private const val KEY_MAX_RECORDING_DURATION = "max_recording_duration"
        // Default URL - React Native app should set this via setApiUrl() 
        // The IME will read from SharedPreferences which is shared with the RN app
        private const val DEFAULT_API_URL = "http://localhost:3002"
        // Default max recording duration in seconds (5 minutes = 300 seconds)
        const val DEFAULT_MAX_RECORDING_DURATION = 300
        const val MIN_RECORDING_DURATION = 10
        const val MAX_RECORDING_DURATION = 300
        
        @Volatile
        private var instance: KeyboardStateManager? = null
        
        fun getInstance(context: Context): KeyboardStateManager {
            return instance ?: synchronized(this) {
                instance ?: KeyboardStateManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    enum class KeyboardMode {
        VOICE,
        TEXT
    }
    
    enum class RecordingState {
        IDLE,
        LISTENING,
        PROCESSING,
        ERROR
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Observable state
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()
    
    private val _lastTranscription = MutableStateFlow("")
    val lastTranscription: StateFlow<String> = _lastTranscription.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // API URL as observable StateFlow so IME can react to changes
    private val _apiUrl = MutableStateFlow(prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL)
    val apiUrlFlow: StateFlow<String> = _apiUrl.asStateFlow()
    
    // Settings
    var apiUrl: String
        get() = _apiUrl.value
        set(value) {
            _apiUrl.value = value
            prefs.edit().putString(KEY_API_URL, value).apply()
        }
    
    var keyboardMode: KeyboardMode
        get() = KeyboardMode.valueOf(
            prefs.getString(KEY_KEYBOARD_MODE, KeyboardMode.VOICE.name) ?: KeyboardMode.VOICE.name
        )
        set(value) = prefs.edit().putString(KEY_KEYBOARD_MODE, value.name).apply()
    
    var autoSend: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEND, value).apply()
    
    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()
    
    var soundFeedback: Boolean
        get() = prefs.getBoolean(KEY_SOUND_FEEDBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_FEEDBACK, value).apply()
    
    var maxRecordingDuration: Int
        get() = prefs.getInt(KEY_MAX_RECORDING_DURATION, DEFAULT_MAX_RECORDING_DURATION)
        set(value) {
            val clampedValue = value.coerceIn(MIN_RECORDING_DURATION, MAX_RECORDING_DURATION)
            prefs.edit().putInt(KEY_MAX_RECORDING_DURATION, clampedValue).apply()
        }
    
    // State management methods
    fun setRecordingState(state: RecordingState) {
        _recordingState.value = state
    }
    
    fun setPartialText(text: String) {
        _partialText.value = text
    }
    
    fun setLastTranscription(text: String) {
        _lastTranscription.value = text
    }
    
    fun setError(message: String?) {
        _errorMessage.value = message
        if (message != null) {
            _recordingState.value = RecordingState.ERROR
        }
    }
    
    fun clearError() {
        _errorMessage.value = null
        if (_recordingState.value == RecordingState.ERROR) {
            _recordingState.value = RecordingState.IDLE
        }
    }
    
    fun reset() {
        _recordingState.value = RecordingState.IDLE
        _partialText.value = ""
        _errorMessage.value = null
    }
}
