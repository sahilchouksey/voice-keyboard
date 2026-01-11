package com.voicekeyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voicekeyboard.shared.KeyboardStateManager
import com.voicekeyboard.shared.SharedApiClient
import com.voicekeyboard.shared.SharedAudioRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Voice Keyboard InputMethodService
 * Provides system-wide voice input capability
 */
class VoiceKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    
    companion object {
        private const val TAG = "VoiceKeyboardService"
    }
    
    // Keyboard mode: VOICE for voice input, TEXT for typing
    enum class KeyboardMode { VOICE, TEXT }
    
    private lateinit var audioRecorder: SharedAudioRecorder
    private lateinit var apiClient: SharedApiClient
    private lateinit var stateManager: KeyboardStateManager
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Lifecycle management for Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    
    // Compose state
    private val _recordingState = mutableStateOf(KeyboardStateManager.RecordingState.IDLE)
    private val _partialText = mutableStateOf("")
    private val _errorMessage = mutableStateOf<String?>(null)
    private val _hasPermission = mutableStateOf(false)
    
    // Keyboard mode state - VOICE by default
    private val _keyboardMode = mutableStateOf(KeyboardMode.VOICE)
    
    // Store last recorded audio for retry functionality
    private var lastRecordedAudioBase64: String? = null
    private val _canRetry = mutableStateOf(false)
    
    // Countdown timer for last 10 seconds of recording
    private val _countdownSeconds = mutableStateOf<Int?>(null)
    
    // Audio amplitude for waveform visualization (list of recent amplitudes)
    private val _audioAmplitudes = mutableStateOf(listOf<Float>())
    
    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        // Initialize shared components
        audioRecorder = SharedAudioRecorder(this)
        apiClient = SharedApiClient()
        stateManager = KeyboardStateManager.getInstance(this)
        
        // Configure API client with saved URL
        val savedApiUrl = stateManager.apiUrl
        Log.d(TAG, "Using API URL from SharedPreferences: $savedApiUrl")
        apiClient.setBaseUrl(savedApiUrl)
        
        // Set up audio recorder callbacks
        setupAudioRecorderCallbacks()
        
        // Observe state changes
        observeStateChanges()
        
        Log.d(TAG, "VoiceKeyboardService created")
    }
    
    private fun setupAudioRecorderCallbacks() {
        // Configure max recording duration from settings
        audioRecorder.setMaxDuration(stateManager.maxRecordingDuration)
        
        audioRecorder.onRecordingStarted = {
            _recordingState.value = KeyboardStateManager.RecordingState.LISTENING
            stateManager.setRecordingState(KeyboardStateManager.RecordingState.LISTENING)
            _countdownSeconds.value = null // Reset countdown when starting
            _audioAmplitudes.value = listOf() // Reset waveform
            vibrate()
        }
        
        audioRecorder.onRecordingStopped = { wavData ->
            _recordingState.value = KeyboardStateManager.RecordingState.PROCESSING
            stateManager.setRecordingState(KeyboardStateManager.RecordingState.PROCESSING)
            _errorMessage.value = null // Clear previous errors
            _countdownSeconds.value = null // Clear countdown
            _audioAmplitudes.value = listOf() // Clear waveform when recording stops
            
            // Send to transcription API
            serviceScope.launch {
                val base64 = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
                // Store the audio for potential retry
                lastRecordedAudioBase64 = base64
                Log.d(TAG, "Sending ${wavData.size} bytes to transcription API")
                val result = apiClient.transcribe(base64)
                
                withContext(Dispatchers.Main) {
                    if (result.success && !result.text.isNullOrBlank()) {
                        insertText(result.text)
                        stateManager.setLastTranscription(result.text)
                        _recordingState.value = KeyboardStateManager.RecordingState.IDLE
                        stateManager.setRecordingState(KeyboardStateManager.RecordingState.IDLE)
                        _errorMessage.value = null
                        _canRetry.value = false // Clear retry on success
                        lastRecordedAudioBase64 = null // Clear stored audio on success
                    } else {
                        // Show error state instead of going back to idle
                        val errorMsg = result.error ?: "Transcription failed"
                        Log.e(TAG, "Transcription error: $errorMsg")
                        _errorMessage.value = errorMsg
                        _recordingState.value = KeyboardStateManager.RecordingState.ERROR
                        stateManager.setRecordingState(KeyboardStateManager.RecordingState.ERROR)
                        stateManager.setError(errorMsg)
                        // Enable retry since we have stored audio
                        _canRetry.value = true
                        Log.d(TAG, "Error state: canRetry=${_canRetry.value}, hasStoredAudio=${lastRecordedAudioBase64 != null}, recordingState=${_recordingState.value}")
                    }
                    _partialText.value = ""
                }
            }
        }
        
        // Handle max duration reached - auto-stop and transcribe
        audioRecorder.onMaxDurationReached = {
            Log.d(TAG, "Max recording duration reached, stopping recording")
            _countdownSeconds.value = null
            audioRecorder.stopRecording()
        }
        
        // Countdown callback for last 10 seconds
        audioRecorder.onCountdownTick = { secondsRemaining ->
            Log.d(TAG, "Recording countdown: ${secondsRemaining}s remaining")
            _countdownSeconds.value = secondsRemaining
            // Vibrate on each second during countdown for tactile feedback
            if (stateManager.hapticFeedback) vibrate()
        }
        
        // Amplitude callback for waveform visualization
        audioRecorder.onAmplitudeChanged = { amplitude ->
            // Keep last 16 amplitude values for waveform display
            val current = _audioAmplitudes.value.toMutableList()
            current.add(amplitude)
            if (current.size > 16) {
                current.removeAt(0)
            }
            _audioAmplitudes.value = current
        }
        
        audioRecorder.onError = { error ->
            Log.e(TAG, "Audio recorder error: $error")
            stateManager.setError(error)
            _recordingState.value = KeyboardStateManager.RecordingState.ERROR
            _countdownSeconds.value = null
            _audioAmplitudes.value = listOf() // Clear waveform on error
        }
    }
    
    private fun observeStateChanges() {
        serviceScope.launch {
            stateManager.recordingState.collectLatest { state ->
                _recordingState.value = state
            }
        }
        
        serviceScope.launch {
            stateManager.partialText.collectLatest { text ->
                _partialText.value = text
            }
        }
        
        serviceScope.launch {
            stateManager.errorMessage.collectLatest { error ->
                _errorMessage.value = error
            }
        }
    }
    
    override fun onCreateInputView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        _hasPermission.value = audioRecorder.hasPermission()
        
        // CRITICAL FIX: Set lifecycle owners on the WINDOW'S DECORVIEW, not the ComposeView
        // InputMethodService has a different view hierarchy than Activities.
        // Compose looks up the view tree to find LifecycleOwner but finds a system LinearLayout
        // (parentPanel) that doesn't have one set. Setting on decorView fixes this.
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this@VoiceKeyboardService)
            decorView.setViewTreeViewModelStoreOwner(this@VoiceKeyboardService)
            decorView.setViewTreeSavedStateRegistryOwner(this@VoiceKeyboardService)
            Log.d(TAG, "Set lifecycle owners on window decorView")
        } ?: Log.w(TAG, "Window decorView is null, Compose may crash!")
        
        // Calculate navigation bar height to avoid overlap
        val navBarHeightPx = getNavigationBarHeight()
        val density = resources.displayMetrics.density
        val navBarHeightDp = (navBarHeightPx / density).toInt()
        Log.d(TAG, "Navigation bar height: ${navBarHeightPx}px (${navBarHeightDp}dp)")
        
        // Create ComposeView with proper lifecycle handling for InputMethodService
        val composeView = ComposeView(this).apply {
            // Use DisposeOnDetachedFromWindowOrReleasedFromPool for IME
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme()
                ) {
                    when (_keyboardMode.value) {
                        KeyboardMode.VOICE -> VoiceKeyboardUI(
                            recordingState = _recordingState.value,
                            partialText = _partialText.value,
                            errorMessage = _errorMessage.value,
                            hasPermission = _hasPermission.value,
                            canRetry = _canRetry.value,
                            countdownSeconds = _countdownSeconds.value,
                            audioAmplitudes = _audioAmplitudes.value,
                            navBarHeightDp = navBarHeightDp,
                            onMicTap = { toggleRecording() },
                            onMicHoldStart = { startRecording() },
                            onMicHoldEnd = { stopRecording() },
                            onRetry = { retryTranscription() },
                            onDismissError = { dismissError() },
                            onBackspace = { deleteLastChar() },
                            onBackspaceLongPress = { deleteWord() },
                            // Gboard-style swipe-to-select callbacks
                            onSwipeSelectStart = { startSwipeSelection() },
                            onExtendSelectionLeft = { extendSelectionLeft() },
                            onReduceSelectionRight = { reduceSelectionRight() },
                            onSwipeSelectEnd = { endSwipeSelectionAndDelete() },
                            onEnter = { sendEnterKey() },
                            onSwitchKeyboard = { switchToTextKeyboard() },
                            onOpenSettings = { openSettings() }
                        )
                        KeyboardMode.TEXT -> TextKeyboardUI(
                            navBarHeightDp = navBarHeightDp,
                            onKeyAction = { handleKeyAction(it) },
                            onSwitchToVoice = { switchToVoiceKeyboard() },
                            onBackspaceLongPress = { deleteWord() },
                            onCursorMoveLeft = { moveCursorLeft() },
                            onCursorMoveRight = { moveCursorRight() },
                            // Gboard-style swipe-to-select callbacks (same as voice mode)
                            onSwipeSelectStart = { startSwipeSelection() },
                            onExtendSelectionLeft = { extendSelectionLeft() },
                            onReduceSelectionRight = { reduceSelectionRight() },
                            onSwipeSelectEnd = { endSwipeSelectionAndDelete() }
                        )
                    }
                }
            }
        }
        
        Log.d(TAG, "Input view created")
        return composeView
    }
    
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        _hasPermission.value = audioRecorder.hasPermission()
        stateManager.clearError()
        Log.d(TAG, "Input view started, hasPermission: ${_hasPermission.value}")
    }
    
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopRecordingIfActive()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }
    
    override fun onWindowHidden() {
        super.onWindowHidden()
        stopRecordingIfActive()
    }
    
    override fun onDestroy() {
        stopRecordingIfActive()
        serviceScope.cancel()
        audioRecorder.release()
        store.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
        Log.d(TAG, "VoiceKeyboardService destroyed")
    }
    
    private fun stopRecordingIfActive() {
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()
        }
    }
    
    // Public methods for keyboard actions
    fun toggleRecording() {
        if (!audioRecorder.hasPermission()) {
            _hasPermission.value = false
            openSettings()
            return
        }
        
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()
        } else {
            // Re-read max duration from settings before starting (in case user changed it)
            val maxDuration = stateManager.maxRecordingDuration
            audioRecorder.setMaxDuration(maxDuration)
            Log.d(TAG, "toggleRecording: Starting with maxDuration=${maxDuration}s")
            audioRecorder.startRecording()
        }
    }
    
    fun startRecording() {
        if (!audioRecorder.hasPermission()) {
            _hasPermission.value = false
            openSettings()
            return
        }
        
        if (!audioRecorder.isRecording()) {
            // Re-read max duration from settings before starting (in case user changed it)
            val maxDuration = stateManager.maxRecordingDuration
            audioRecorder.setMaxDuration(maxDuration)
            Log.d(TAG, "startRecording: Starting with maxDuration=${maxDuration}s")
            audioRecorder.startRecording()
        }
    }
    
    fun stopRecording() {
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()
        }
    }
    
    fun isRecording(): Boolean = audioRecorder.isRecording()
    
    /**
     * Retry the last failed transcription
     * Re-sends the stored audio data to the transcription API
     */
    fun retryTranscription() {
        val audioBase64 = lastRecordedAudioBase64
        if (audioBase64 == null) {
            Log.w(TAG, "No audio data available for retry")
            _errorMessage.value = "No audio to retry"
            return
        }
        
        Log.d(TAG, "Retrying transcription with stored audio (${audioBase64.length} base64 chars)")
        
        // Set to processing state
        _recordingState.value = KeyboardStateManager.RecordingState.PROCESSING
        stateManager.setRecordingState(KeyboardStateManager.RecordingState.PROCESSING)
        _errorMessage.value = null
        _canRetry.value = false
        
        serviceScope.launch {
            val result = apiClient.transcribe(audioBase64)
            
            withContext(Dispatchers.Main) {
                if (result.success && !result.text.isNullOrBlank()) {
                    insertText(result.text)
                    stateManager.setLastTranscription(result.text)
                    _recordingState.value = KeyboardStateManager.RecordingState.IDLE
                    stateManager.setRecordingState(KeyboardStateManager.RecordingState.IDLE)
                    _errorMessage.value = null
                    _canRetry.value = false
                    lastRecordedAudioBase64 = null // Clear stored audio on success
                    Log.d(TAG, "Retry successful: ${result.text}")
                } else {
                    // Still failed
                    val errorMsg = result.error ?: "Transcription failed"
                    Log.e(TAG, "Retry failed: $errorMsg")
                    _errorMessage.value = errorMsg
                    _recordingState.value = KeyboardStateManager.RecordingState.ERROR
                    stateManager.setRecordingState(KeyboardStateManager.RecordingState.ERROR)
                    stateManager.setError(errorMsg)
                    _canRetry.value = true // Can still retry
                }
            }
        }
    }
    
    /**
     * Dismiss error state and go back to idle
     * Clears the stored audio data
     */
    fun dismissError() {
        _recordingState.value = KeyboardStateManager.RecordingState.IDLE
        stateManager.setRecordingState(KeyboardStateManager.RecordingState.IDLE)
        _errorMessage.value = null
        _canRetry.value = false
        lastRecordedAudioBase64 = null
        stateManager.clearError()
    }
    
    /**
     * Delete one character or selected text
     * - If text is selected: deletes the entire selection
     * - If no selection: deletes one character before the cursor
     * 
     * This mimics Gboard behavior where pressing backspace on selected text
     * removes the selection, and pressing without selection deletes one char.
     */
    fun deleteLastChar() {
        val connection = currentInputConnection ?: return
        
        // Check if there's selected text
        val selectedText = connection.getSelectedText(0)
        
        if (!selectedText.isNullOrEmpty()) {
            // There's selected text - delete it by replacing with empty string
            // commitText("", 1) replaces the selection with nothing
            connection.commitText("", 1)
            Log.d(TAG, "Deleted selected text: ${selectedText.length} chars")
        } else {
            // No selection - delete one character before cursor
            connection.deleteSurroundingText(1, 0)
        }
        
        if (stateManager.hapticFeedback) vibrate()
    }
    
    /**
     * Delete one word or selected text
     * - If text is selected: deletes the entire selection
     * - If no selection: deletes the word before the cursor
     * 
     * This is triggered by long-press on backspace (single action, not repeating)
     */
    fun deleteWord() {
        val connection = currentInputConnection ?: return
        
        // Check if there's selected text first
        val selectedText = connection.getSelectedText(0)
        
        if (!selectedText.isNullOrEmpty()) {
            // There's selected text - delete it
            connection.commitText("", 1)
            Log.d(TAG, "Deleted selected text: ${selectedText.length} chars")
            if (stateManager.hapticFeedback) vibrate()
            return
        }
        
        // No selection - delete the word before cursor
        val textBefore = connection.getTextBeforeCursor(50, 0)?.toString() ?: return
        
        // Find the start of the last word
        var deleteCount = 0
        var foundWord = false
        for (i in textBefore.length - 1 downTo 0) {
            val char = textBefore[i]
            if (char.isWhitespace()) {
                if (foundWord) break
            } else {
                foundWord = true
            }
            deleteCount++
        }
        
        if (deleteCount > 0) {
            connection.deleteSurroundingText(deleteCount, 0)
        }
        if (stateManager.hapticFeedback) vibrate()
    }
    
    /**
     * Check if there's currently selected text in the input field
     */
    fun hasSelectedText(): Boolean {
        val connection = currentInputConnection ?: return false
        val selectedText = connection.getSelectedText(0)
        return !selectedText.isNullOrEmpty()
    }
    
    // ==================== GBOARD-STYLE SWIPE-TO-SELECT ====================
    
    /**
     * State for tracking swipe-to-select gesture
     */
    private var swipeSelectionActive = false
    private var swipeSelectionStartPos = -1  // Original cursor position when gesture started
    private var swipeSelectionEndPos = -1    // Current end of selection (original cursor pos)
    private var currentWordsSelected = 0
    private var cachedTextBeforeCursor = ""
    private var wordBoundaries = listOf<Int>()  // Positions of word starts before cursor
    
    /**
     * Start the swipe-to-select gesture
     * Called when user begins holding the backspace key
     * Caches text and calculates word boundaries for the gesture
     */
    fun startSwipeSelection() {
        val connection = currentInputConnection ?: return
        
        // Get the text before cursor for word boundary detection
        cachedTextBeforeCursor = connection.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        
        if (cachedTextBeforeCursor.isEmpty()) {
            Log.d(TAG, "SwipeSelect: No text before cursor")
            return
        }
        
        // Calculate word boundaries (positions where words start)
        wordBoundaries = findWordBoundaries(cachedTextBeforeCursor)
        
        // Store cursor position (this will be the END of our selection)
        swipeSelectionEndPos = cachedTextBeforeCursor.length
        swipeSelectionStartPos = swipeSelectionEndPos
        currentWordsSelected = 0
        swipeSelectionActive = true
        
        Log.d(TAG, "SwipeSelect: Started. Text length=${cachedTextBeforeCursor.length}, wordBoundaries=$wordBoundaries")
    }
    
    /**
     * Find word boundaries (start positions of each word) in text
     * Returns list of indices where words start, from right to left (closest to cursor first)
     */
    private fun findWordBoundaries(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        
        val boundaries = mutableListOf<Int>()
        
        // Use regex to find all words (sequences of letters/numbers)
        val wordPattern = "[\\p{L}\\p{N}]+".toRegex()
        val matches = wordPattern.findAll(text).toList()
        
        // Store word start positions in reverse order (closest to cursor first)
        for (match in matches.reversed()) {
            boundaries.add(match.range.first)
        }
        
        Log.d(TAG, "SwipeSelect: Found ${boundaries.size} word boundaries: $boundaries")
        return boundaries
    }
    
    /**
     * Extend selection by one word to the left
     * Called when user swipes left during the gesture
     * Returns true if selection was extended, false if no more words to select
     */
    fun extendSelectionLeft(): Boolean {
        if (!swipeSelectionActive) return false
        
        val connection = currentInputConnection ?: return false
        
        // Check if we can select more words
        if (currentWordsSelected >= wordBoundaries.size) {
            Log.d(TAG, "SwipeSelect: No more words to select (${currentWordsSelected}/${wordBoundaries.size})")
            return false
        }
        
        // Get the start position of the next word to include in selection
        val newStartPos = wordBoundaries[currentWordsSelected]
        swipeSelectionStartPos = newStartPos
        currentWordsSelected++
        
        // Apply the selection via InputConnection
        // setSelection(start, end) highlights text from start to end
        connection.setSelection(newStartPos, swipeSelectionEndPos)
        
        Log.d(TAG, "SwipeSelect: Extended left. Words=$currentWordsSelected, selection=[$newStartPos-$swipeSelectionEndPos]")
        
        // Haptic feedback
        if (stateManager.hapticFeedback) vibrate()
        
        return true
    }
    
    /**
     * Reduce selection by one word (deselect rightmost selected word)
     * Called when user swipes right during the gesture
     * Returns true if selection was reduced, false if no selection left
     */
    fun reduceSelectionRight(): Boolean {
        if (!swipeSelectionActive) return false
        if (currentWordsSelected <= 0) return false
        
        val connection = currentInputConnection ?: return false
        
        currentWordsSelected--
        
        if (currentWordsSelected == 0) {
            // No more selection - move cursor back to original position
            swipeSelectionStartPos = swipeSelectionEndPos
            connection.setSelection(swipeSelectionEndPos, swipeSelectionEndPos)
            Log.d(TAG, "SwipeSelect: Cleared selection")
        } else {
            // Reduce selection to previous word boundary
            val newStartPos = wordBoundaries[currentWordsSelected - 1]
            swipeSelectionStartPos = newStartPos
            connection.setSelection(newStartPos, swipeSelectionEndPos)
            Log.d(TAG, "SwipeSelect: Reduced right. Words=$currentWordsSelected, selection=[$newStartPos-$swipeSelectionEndPos]")
        }
        
        // Haptic feedback
        if (stateManager.hapticFeedback) vibrate()
        
        return true
    }
    
    /**
     * End the swipe-to-select gesture and delete selected text
     * Called when user releases the backspace key
     */
    fun endSwipeSelectionAndDelete() {
        if (!swipeSelectionActive) return
        
        val connection = currentInputConnection ?: return
        
        Log.d(TAG, "SwipeSelect: Ending. Words selected=$currentWordsSelected")
        
        if (currentWordsSelected > 0) {
            // Delete the selected text
            val selectedText = connection.getSelectedText(0)
            connection.commitText("", 1)
            Log.d(TAG, "SwipeSelect: Deleted ${selectedText?.length ?: 0} chars")
            
            // Store deleted text for potential undo (could add to suggestion bar)
            // TODO: Implement undo functionality
        }
        
        // Reset state
        swipeSelectionActive = false
        swipeSelectionStartPos = -1
        swipeSelectionEndPos = -1
        currentWordsSelected = 0
        cachedTextBeforeCursor = ""
        wordBoundaries = emptyList()
    }
    
    /**
     * Cancel the swipe-to-select gesture without deleting
     */
    fun cancelSwipeSelection() {
        if (!swipeSelectionActive) return
        
        val connection = currentInputConnection ?: return
        
        // Clear selection (move cursor to end position)
        connection.setSelection(swipeSelectionEndPos, swipeSelectionEndPos)
        
        Log.d(TAG, "SwipeSelect: Cancelled")
        
        // Reset state
        swipeSelectionActive = false
        swipeSelectionStartPos = -1
        swipeSelectionEndPos = -1
        currentWordsSelected = 0
        cachedTextBeforeCursor = ""
        wordBoundaries = emptyList()
    }
    
    /**
     * Check if swipe selection is currently active
     */
    fun isSwipeSelectionActive(): Boolean = swipeSelectionActive
    
    /**
     * Get the number of words currently selected in swipe gesture
     */
    fun getSwipeSelectionWordCount(): Int = currentWordsSelected
    
    // ==================== END SWIPE-TO-SELECT ====================
    
    fun insertText(text: String) {
        val connection = currentInputConnection ?: return
        
        // Check if we need a space before the text
        val textBefore = connection.getTextBeforeCursor(1, 0)?.toString()
        val needsSpace = !textBefore.isNullOrEmpty() && 
                         !textBefore.endsWith(" ") && 
                         !textBefore.endsWith("\n")
        
        if (needsSpace) {
            connection.commitText(" ", 1)
        }
        connection.commitText(text, 1)
    }
    
    fun sendEnterKey() {
        val connection = currentInputConnection ?: return
        
        if (stateManager.hapticFeedback) vibrate()
        
        // Send actual Enter key event - this works properly in terminals, SSH, and regular text fields
        // Using sendKeyEvent ensures the Enter key behaves like a physical keyboard Enter
        val eventTime = System.currentTimeMillis()
        connection.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        connection.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        
        Log.d(TAG, "sendEnterKey: Sent KEYCODE_ENTER key event")
    }
    
    // ==================== CURSOR MOVEMENT (SPACE BAR JOYSTICK) ====================
    
    /**
     * Detect if current input is a terminal/console context
     * Terminals typically have:
     * - NULL inputType or TYPE_NULL
     * - No extracted text available
     * - Package names containing "termux", "terminal", "console", "ssh", etc.
     */
    private fun isTerminalContext(): Boolean {
        val editorInfo = currentInputEditorInfo ?: return false
        val connection = currentInputConnection ?: return false
        
        // Check package name for known terminal apps
        val packageName = editorInfo.packageName?.lowercase() ?: ""
        val terminalPackages = listOf(
            "termux", "terminal", "console", "ssh", "telnet", 
            "connectbot", "juicessh", "remoter", "serverauditor",
            "vx.connectbot", "org.connectbot", "com.sonelli",
            "shell", "tty", "pty"
        )
        if (terminalPackages.any { packageName.contains(it) }) {
            Log.d(TAG, "Terminal detected by package name: $packageName")
            return true
        }
        
        // Check inputType - terminals often have TYPE_NULL or TYPE_CLASS_TEXT with no variations
        val inputType = editorInfo.inputType
        if (inputType == EditorInfo.TYPE_NULL) {
            Log.d(TAG, "Terminal detected by TYPE_NULL inputType")
            return true
        }
        
        // Check if ExtractedText is unavailable (common for terminals)
        val extracted = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (extracted == null) {
            Log.d(TAG, "Terminal likely - ExtractedText unavailable")
            return true
        }
        
        return false
    }
    
    /**
     * Send cursor movement for terminal contexts.
     * 
     * Uses Ctrl+B (left) / Ctrl+F (right) emacs-style shortcuts instead of DPAD keys.
     * 
     * Why Ctrl+B/F instead of DPAD?
     * - DPAD keys get translated to ANSI escape sequences (\x1b[D for left)
     * - This works for readline (bash/zsh) but NOT for TUI apps like OpenCode
     * - OpenCode uses contenteditable divs that expect browser KeyboardEvents
     * - Ctrl+B/F are emacs shortcuts that work in BOTH:
     *   1. Readline/bash (natively supports emacs mode)
     *   2. OpenCode's OpenTUI Textarea (has keybindings for Ctrl+B/F)
     * 
     * @param keyCode Either KEYCODE_DPAD_LEFT or KEYCODE_DPAD_RIGHT to indicate direction
     */
    private fun sendArrowKeyEvent(keyCode: Int) {
        val connection = currentInputConnection ?: return
        val eventTime = System.currentTimeMillis()
        
        // Use Ctrl+B (left) or Ctrl+F (right) instead of DPAD keys
        // These emacs-style shortcuts work universally in terminals and TUI apps
        val letterKeyCode = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            KeyEvent.KEYCODE_B  // Ctrl+B = move cursor left
        } else {
            KeyEvent.KEYCODE_F  // Ctrl+F = move cursor right
        }
        
        // Send Ctrl+key combination
        val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        connection.sendKeyEvent(KeyEvent(
            eventTime, eventTime, KeyEvent.ACTION_DOWN, letterKeyCode, 0, ctrlMeta
        ))
        connection.sendKeyEvent(KeyEvent(
            eventTime, eventTime, KeyEvent.ACTION_UP, letterKeyCode, 0, ctrlMeta
        ))
        
        if (stateManager.hapticFeedback) vibrate()
    }
    
    /**
     * Move cursor using multiple strategies:
     * 1. setSelection for standard text fields
     * 2. DPAD key events for terminals
     * 3. ANSI escape sequences for TUI apps (fallback)
     */
    private fun moveCursorWithFallback(direction: Int): Boolean {
        val connection = currentInputConnection ?: return false
        
        // For terminals and TUI apps, use key events
        if (isTerminalContext()) {
            sendArrowKeyEvent(direction)
            return true
        }
        
        // For regular text fields, try setSelection first
        val extracted = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (extracted != null) {
            val currentPos = extracted.selectionStart
            val textLength = extracted.text?.length ?: 0
            
            val newPos = if (direction == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (currentPos > 0) currentPos - 1 else return false
            } else {
                if (currentPos < textLength) currentPos + 1 else return false
            }
            
            connection.setSelection(newPos, newPos)
            if (stateManager.hapticFeedback) vibrate()
            return true
        }
        
        // Fallback to key event
        sendArrowKeyEvent(direction)
        return true
    }
    
    /**
     * Move cursor left by one character
     * Uses key events for terminals, setSelection for regular text fields
     * Returns true if the cursor was moved
     */
    fun moveCursorLeft(): Boolean {
        return moveCursorWithFallback(KeyEvent.KEYCODE_DPAD_LEFT)
    }
    
    /**
     * Move cursor right by one character
     * Uses key events for terminals, setSelection for regular text fields
     * Returns true if the cursor was moved
     */
    fun moveCursorRight(): Boolean {
        return moveCursorWithFallback(KeyEvent.KEYCODE_DPAD_RIGHT)
    }
    
    // ==================== END CURSOR MOVEMENT ====================
    
    /**
     * Switch to the text keyboard mode
     */
    private fun switchToTextKeyboard() {
        Log.d(TAG, "Switching to TEXT keyboard mode")
        // Stop any active recording first
        stopRecordingIfActive()
        _keyboardMode.value = KeyboardMode.TEXT
        vibrate()
    }
    
    /**
     * Switch to the voice keyboard mode
     */
    private fun switchToVoiceKeyboard() {
        Log.d(TAG, "Switching to VOICE keyboard mode")
        _keyboardMode.value = KeyboardMode.VOICE
        vibrate()
    }
    
    /**
     * Handle key actions from the text keyboard
     */
    private fun handleKeyAction(action: KeyAction) {
        if (stateManager.hapticFeedback) vibrate()
        
        when (action) {
            is KeyAction.CommitText -> {
                currentInputConnection?.commitText(action.text, 1)
            }
            KeyAction.Delete -> {
                deleteLastChar()
            }
            KeyAction.Enter -> {
                sendEnterKey()
            }
            KeyAction.Space -> {
                currentInputConnection?.commitText(" ", 1)
            }
            KeyAction.SwitchToVoice -> {
                switchToVoiceKeyboard()
            }
            // Shift and SwitchToSymbols are handled internally by TextKeyboardUI
            else -> {}
        }
    }
    
    /**
     * Switch to the next keyboard/IME
     * 
     * Comprehensive implementation that works across all Android versions:
     * - API 28+ (Android P+): Uses InputMethodService.switchToNextInputMethod()
     * - API 16-27: Uses InputMethodManager.switchToNextInputMethod() with token
     * - All versions: Falls back to showing the system IME picker
     * 
     * Based on implementations from FlorisBoard, OpenBoard, and AnySoftKeyboard
     */
    fun switchToNextKeyboard() {
        Log.d(TAG, "=== switchToNextKeyboard called ===")
        Log.d(TAG, "SDK Version: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        
        vibrate() // Immediate feedback
        
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm == null) {
                Log.e(TAG, "InputMethodManager is null!")
                return
            }
            
            // Log available IMEs for debugging
            logAvailableInputMethods(imm)
            
            // Method 1: API 28+ (Android P/Pie and above) - Use InputMethodService methods
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Log.d(TAG, "Trying API 28+ method: InputMethodService.switchToNextInputMethod()")
                
                val shouldSwitch = shouldOfferSwitchingToNextInputMethod()
                Log.d(TAG, "shouldOfferSwitchingToNextInputMethod() = $shouldSwitch")
                
                // Try to switch even if shouldSwitch is false - some devices report incorrectly
                try {
                    val switched = switchToNextInputMethod(false)
                    Log.d(TAG, "switchToNextInputMethod(false) = $switched")
                    if (switched) {
                        Log.d(TAG, "SUCCESS: Switched via InputMethodService.switchToNextInputMethod()")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "InputMethodService.switchToNextInputMethod() threw exception", e)
                }
            }
            
            // Method 2: API 16+ - Use InputMethodManager with window token (deprecated in API 28 but still works)
            Log.d(TAG, "Trying API 16+ method: InputMethodManager.switchToNextInputMethod() with token")
            val token = window?.window?.attributes?.token
            Log.d(TAG, "Window token: ${if (token != null) "available" else "NULL"}")
            
            if (token != null) {
                try {
                    @Suppress("DEPRECATION")
                    val switched = imm.switchToNextInputMethod(token, false)
                    Log.d(TAG, "IMM.switchToNextInputMethod(token, false) = $switched")
                    if (switched) {
                        Log.d(TAG, "SUCCESS: Switched via InputMethodManager.switchToNextInputMethod()")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "InputMethodManager.switchToNextInputMethod() threw exception", e)
                }
                
                // Method 2b: Try switchToLastInputMethod (switches to previous IME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    try {
                        @Suppress("DEPRECATION")
                        val switched = imm.switchToLastInputMethod(token)
                        Log.d(TAG, "IMM.switchToLastInputMethod(token) = $switched")
                        if (switched) {
                            Log.d(TAG, "SUCCESS: Switched via InputMethodManager.switchToLastInputMethod()")
                            return
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "InputMethodManager.switchToLastInputMethod() threw exception", e)
                    }
                }
            }
            
            // Method 3: Try to switch to a specific known IME (like Gboard)
            Log.d(TAG, "Trying to switch to a specific enabled IME")
            if (tryDirectSwitchToOtherIME(imm)) {
                Log.d(TAG, "SUCCESS: Switched via direct IME switch")
                return
            }
            
            // Method 4: Final fallback - show the system IME picker
            Log.d(TAG, "All direct switch methods failed, showing IME picker")
            showSystemInputMethodPicker(imm)
            
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in switchToNextKeyboard", e)
            // Last resort - try to show picker
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
                Log.d(TAG, "Showed picker as last resort")
            } catch (e2: Exception) {
                Log.e(TAG, "Even showInputMethodPicker() failed!", e2)
            }
        }
    }
    
    /**
     * Log all available and enabled input methods for debugging
     */
    private fun logAvailableInputMethods(imm: InputMethodManager) {
        try {
            val enabledMethods = imm.enabledInputMethodList
            Log.d(TAG, "Enabled IMEs count: ${enabledMethods.size}")
            enabledMethods.forEachIndexed { index, imi ->
                val label = imi.loadLabel(packageManager)
                Log.d(TAG, "  [$index] ${imi.id} - $label")
            }
            
            // Get current IME
            val currentImeId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )
            Log.d(TAG, "Current IME: $currentImeId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log input methods", e)
        }
    }
    
    /**
     * Try to directly switch to another enabled IME
     * This is useful when the system's "next" doesn't work but we know other IMEs exist
     */
    private fun tryDirectSwitchToOtherIME(imm: InputMethodManager): Boolean {
        try {
            val enabledMethods = imm.enabledInputMethodList
            if (enabledMethods.size < 2) {
                Log.d(TAG, "Only ${enabledMethods.size} IME(s) enabled, cannot switch")
                return false
            }
            
            // Get current IME ID
            val currentImeId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )
            
            // Find the next IME in the list
            val currentIndex = enabledMethods.indexOfFirst { it.id == currentImeId }
            Log.d(TAG, "Current IME index: $currentIndex")
            
            if (currentIndex >= 0) {
                val nextIndex = (currentIndex + 1) % enabledMethods.size
                val nextIme = enabledMethods[nextIndex]
                Log.d(TAG, "Attempting to switch to: ${nextIme.id}")
                
                // Try to switch using API 28+ method
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        switchInputMethod(nextIme.id)
                        Log.d(TAG, "Called switchInputMethod(${nextIme.id})")
                        return true
                    } catch (e: Exception) {
                        Log.w(TAG, "switchInputMethod() failed", e)
                    }
                }
                
                // Try using deprecated setInputMethod with token
                val token = window?.window?.attributes?.token
                if (token != null) {
                    try {
                        @Suppress("DEPRECATION")
                        imm.setInputMethod(token, nextIme.id)
                        Log.d(TAG, "Called setInputMethod(token, ${nextIme.id})")
                        return true
                    } catch (e: Exception) {
                        Log.w(TAG, "setInputMethod() failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryDirectSwitchToOtherIME failed", e)
        }
        return false
    }
    
    /**
     * Show the system input method picker dialog
     * This is the most reliable fallback that works on all Android versions
     */
    private fun showSystemInputMethodPicker(imm: InputMethodManager) {
        try {
            // InputMethodService.showInputMethodPicker() is the proper way from an IME
            // This calls the same thing but through the service context
            imm.showInputMethodPicker()
            Log.d(TAG, "SUCCESS: Showed system IME picker")
        } catch (e: Exception) {
            Log.e(TAG, "showInputMethodPicker() failed", e)
            
            // Alternative: try showing via intent (works on some devices)
            try {
                val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "Opened IME settings as fallback")
            } catch (e2: Exception) {
                Log.e(TAG, "Even opening IME settings failed", e2)
            }
        }
    }
    
    fun openSettings() {
        // Open main React Native app (SettingsScreen)
        val intent = Intent(this, com.voicekeyboard.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
    
    private fun vibrate() {
        if (!stateManager.hapticFeedback) return
        
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }
    
    /**
     * Get navigation bar height dynamically based on device configuration.
     * Returns 0 if no navigation bar is present (e.g., devices with physical buttons).
     * Adapts to different navigation modes:
     * - Gesture navigation: smaller height (~48px)
     * - 3-button/2-button navigation: larger height (~126px)
     * - No navigation bar: 0px
     */
    private fun getNavigationBarHeight(): Int {
        // Method 1: Use WindowInsets API (API 30+) - most accurate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.window?.decorView?.let { decorView ->
                val windowInsets = decorView.rootWindowInsets
                if (windowInsets != null) {
                    val navBarInsets = windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars())
                    val height = navBarInsets.bottom
                    Log.d(TAG, "Nav bar height (WindowInsets API 30+): ${height}px")
                    return height
                }
            }
        }
        
        // Method 2: Use WindowInsetsCompat for older APIs
        window?.window?.decorView?.let { decorView ->
            val windowInsets = WindowInsetsCompat.toWindowInsetsCompat(
                decorView.rootWindowInsets ?: return@let
            )
            val navBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val height = navBarInsets.bottom
            if (height > 0) {
                Log.d(TAG, "Nav bar height (WindowInsetsCompat): ${height}px")
                return height
            }
        }
        
        // Method 3: Fallback to resource-based detection
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            val height = resources.getDimensionPixelSize(resourceId)
            Log.d(TAG, "Nav bar height (resource fallback): ${height}px")
            return height
        }
        
        Log.d(TAG, "Nav bar height: 0px (no navigation bar detected)")
        return 0
    }
}

@Composable
fun VoiceKeyboardUI(
    recordingState: KeyboardStateManager.RecordingState,
    partialText: String,
    errorMessage: String?,
    hasPermission: Boolean,
    canRetry: Boolean,
    countdownSeconds: Int?,
    audioAmplitudes: List<Float>,
    navBarHeightDp: Int = 0,
    onMicTap: () -> Unit,
    onMicHoldStart: () -> Unit,
    onMicHoldEnd: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLongPress: () -> Unit,
    // Gboard-style swipe-to-select callbacks
    onSwipeSelectStart: () -> Unit,
    onExtendSelectionLeft: () -> Boolean,
    onReduceSelectionRight: () -> Boolean,
    onSwipeSelectEnd: () -> Unit,
    onEnter: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isListening = recordingState == KeyboardStateManager.RecordingState.LISTENING
    val isProcessing = recordingState == KeyboardStateManager.RecordingState.PROCESSING
    val isError = recordingState == KeyboardStateManager.RecordingState.ERROR
    
    // Total height = keyboard content (220dp) + navigation bar padding
    val keyboardContentHeight = 220.dp
    val navBarPadding = navBarHeightDp.dp
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyboardContentHeight + navBarPadding),
        color = Color(0xFF1C1C1E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + navBarPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status text area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        color = Color(0xFF2C2C2E),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!hasPermission) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Microphone permission required",
                            color = Color(0xFFFF453A),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onOpenSettings) {
                            Text("Open Settings", color = Color(0xFF0A84FF))
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Main status text with countdown (skip if ERROR - error UI handles it)
                        if (!isError) {
                            // Use Row layout when listening to show waveform on right
                            if (isListening) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    // Status text on left/center
                                    Text(
                                        text = run {
                                            val countdownText = if (countdownSeconds != null) " (${countdownSeconds}s)" else ""
                                            partialText.ifEmpty { "Listening...$countdownText" }
                                        },
                                        color = if (countdownSeconds != null && countdownSeconds <= 5) {
                                            Color(0xFFFF9F0A) // Orange warning for last 5 seconds
                                        } else {
                                            Color(0xFF30D158) // Green normally
                                        },
                                        fontSize = 16.sp,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Center
                                    )
                                    
                                    // Waveform visualizer on right (faded)
                                    AudioWaveform(
                                        amplitudes = audioAmplitudes,
                                        modifier = Modifier
                                            .width(80.dp)
                                            .height(32.dp)
                                            .alpha(0.6f)
                                    )
                                }
                            } else {
                                // Non-listening states: simple centered text
                                Text(
                                    text = when (recordingState) {
                                        KeyboardStateManager.RecordingState.IDLE -> "Tap or hold mic to speak"
                                        KeyboardStateManager.RecordingState.PROCESSING -> "Processing..."
                                        else -> ""
                                    },
                                    color = when (recordingState) {
                                        KeyboardStateManager.RecordingState.PROCESSING -> Color(0xFFFF9F0A)
                                        else -> Color.White.copy(alpha = 0.8f)
                                    },
                                    fontSize = 16.sp
                                )
                            }
                        }
                        
                        // Show error UI: [Retry] - Error Text - [Dismiss]
                        if (isError) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: Retry button
                                if (canRetry) {
                                    Button(
                                        onClick = onRetry,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF0A84FF)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Retry",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Retry", color = Color.White, fontSize = 13.sp)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(80.dp))
                                }
                                
                                // Center: Error text
                                Text(
                                    text = errorMessage ?: "Error",
                                    color = Color(0xFFFF453A),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    textAlign = TextAlign.Center
                                )
                                
                                // Right: Dismiss button
                                Button(
                                    onClick = onDismissError,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF3A3A3C)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Dismiss", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Control buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch keyboard button (to text mode)
                IconButton(
                    onClick = onSwitchKeyboard,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3A3A3C), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Switch to text keyboard",
                        tint = Color.White
                    )
                }
                
                // Backspace button with Gboard-style swipe-to-select gesture
                GboardStyleBackspaceKey(
                    onDelete = onBackspace,
                    onDeleteWord = onBackspaceLongPress,
                    onSwipeSelectStart = onSwipeSelectStart,
                    onExtendSelectionLeft = onExtendSelectionLeft,
                    onReduceSelectionRight = onReduceSelectionRight,
                    onSwipeSelectEnd = onSwipeSelectEnd
                )
                
                // Microphone button
                VoiceMicButton(
                    isListening = isListening,
                    isProcessing = isProcessing,
                    isError = isError,
                    onTap = onMicTap,
                    onHoldStart = onMicHoldStart,
                    onHoldEnd = onMicHoldEnd
                )
                
                // Enter button
                IconButton(
                    onClick = onEnter,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3A3A3C), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                        contentDescription = "Enter",
                        tint = Color.White
                    )
                }
                
                // Settings button
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3A3A3C), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Audio waveform visualizer that displays amplitude bars
 * Shows a faded waveform on the right side of the status area during recording
 */
@Composable
fun AudioWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barCount: Int = 16,
    barColor: Color = Color(0xFF30D158) // Green to match listening state
) {
    // Pad amplitudes to barCount if needed
    val paddedAmplitudes = if (amplitudes.size < barCount) {
        List(barCount - amplitudes.size) { 0f } + amplitudes
    } else {
        amplitudes.takeLast(barCount)
    }
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        paddedAmplitudes.forEach { amplitude ->
            // Animate bar height changes for smoother visualization
            val animatedHeight by animateFloatAsState(
                targetValue = amplitude,
                animationSpec = tween(durationMillis = 50),
                label = "bar_height"
            )
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Each bar: minimum height of 4dp, max fills available height
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction = 0.15f + (animatedHeight * 0.85f)) // Min 15%, max 100%
                        .background(
                            color = barColor.copy(alpha = 0.4f + (animatedHeight * 0.6f)),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}

/**
 * Backspace button with Gboard-like repeat-on-hold functionality
 * 
 * Behavior:
 * - Single tap: Delete one character (or selected text)
 * - Hold: Continuously delete characters with acceleration
 * - Initial delay before repeat: 400ms
 * - Repeat interval: starts at 80ms, accelerates to 30ms
 * 
 * This mimics Gboard's behavior where holding backspace starts slow
 * and speeds up the longer you hold it.
 */
@Composable
fun RepeatingBackspaceKey(
    onDelete: () -> Unit,
    onDeleteWord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    var isPressed by remember { mutableStateOf(false) }
    
    // Timing constants (in milliseconds)
    val initialDelayMs = 400L    // Delay before repeat starts (like Gboard)
    val slowRepeatMs = 80L       // Initial repeat interval
    val fastRepeatMs = 30L       // Fast repeat interval after acceleration
    val accelerationThreshold = 8 // Number of deletes before speeding up
    
    Box(
        modifier = modifier
            .size(48.dp)
            .background(Color(0xFF3A3A3C), CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Wait for finger down
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        
                        // Start the repeat delete job
                        repeatJob = coroutineScope.launch {
                            var deleteCount = 0
                            
                            // First delete immediately on press
                            onDelete()
                            deleteCount++
                            
                            // Wait for initial delay before starting repeat
                            delay(initialDelayMs)
                            
                            // Repeat loop with acceleration
                            while (isActive) {
                                onDelete()
                                deleteCount++
                                
                                // Calculate interval with acceleration
                                val interval = if (deleteCount > accelerationThreshold) {
                                    // After threshold, use faster interval
                                    fastRepeatMs
                                } else {
                                    // Gradually speed up
                                    val progress = deleteCount.toFloat() / accelerationThreshold
                                    (slowRepeatMs - (slowRepeatMs - fastRepeatMs) * progress).toLong()
                                }
                                
                                delay(interval)
                            }
                        }
                        
                        // Wait for finger up or cancel
                        waitForUpOrCancellation()
                        
                        // Cancel the repeat job
                        repeatJob?.cancel()
                        repeatJob = null
                        isPressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Delete",
            tint = if (isPressed) Color.White.copy(alpha = 0.7f) else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Gboard-style backspace with swipe-to-select gesture
 * 
 * Behavior:
 * - Tap: Delete one character (or selected text)
 * - Hold (no swipe): Continuously delete characters with acceleration
 * - Hold + Swipe Left: Select words one-by-one (highlighted in editor)
 * - Hold + Swipe Right: Deselect words one-by-one
 * - Release after swipe: Delete all selected text
 * 
 * This mimics Gboard's powerful backspace gesture where you can swipe left
 * to select multiple words for deletion.
 */
@Composable
fun GboardStyleBackspaceKey(
    onDelete: () -> Unit,
    onDeleteWord: () -> Unit,
    onSwipeSelectStart: () -> Unit,
    onExtendSelectionLeft: () -> Boolean,
    onReduceSelectionRight: () -> Boolean,
    onSwipeSelectEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }
    var wordsSelected by remember { mutableIntStateOf(0) }
    var isInSwipeMode by remember { mutableStateOf(false) }
    
    // Timing and gesture constants
    val holdThresholdMs = 200L           // Time to hold before swipe mode activates
    val pixelsPerWord = 40f              // Horizontal distance to select one word
    val swipeActivationThreshold = 15f   // Minimum horizontal movement to enter swipe mode
    
    // Repeat delete constants (when not swiping)
    val initialDelayMs = 400L
    val slowRepeatMs = 80L
    val fastRepeatMs = 30L
    val accelerationThreshold = 8
    
    Box(
        modifier = modifier
            .size(48.dp)
            .background(
                if (isInSwipeMode) Color(0xFF5A5A5C) else Color(0xFF3A3A3C), 
                CircleShape
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for finger down
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    isInSwipeMode = false
                    wordsSelected = 0
                    
                    var cumulativeDragX = 0f
                    var swipeModeActivated = false
                    var holdJobStarted = false
                    var repeatJob: Job? = null
                    
                    // Start a job to handle hold detection and repeat delete
                    val holdDetectionJob = coroutineScope.launch {
                        // First, delete immediately on touch
                        onDelete()
                        
                        // Wait for hold threshold
                        delay(holdThresholdMs)
                        holdJobStarted = true
                        
                        // If we haven't entered swipe mode yet, start repeat delete
                        if (!swipeModeActivated) {
                            // Initialize swipe selection (cache text and word boundaries)
                            onSwipeSelectStart()
                            
                            // Start repeat delete loop
                            repeatJob = coroutineScope.launch {
                                var deleteCount = 1 // Already deleted once
                                delay(initialDelayMs - holdThresholdMs) // Remaining delay
                                
                                while (isActive && !swipeModeActivated) {
                                    onDelete()
                                    deleteCount++
                                    
                                    val interval = if (deleteCount > accelerationThreshold) {
                                        fastRepeatMs
                                    } else {
                                        val progress = deleteCount.toFloat() / accelerationThreshold
                                        (slowRepeatMs - (slowRepeatMs - fastRepeatMs) * progress).toLong()
                                    }
                                    delay(interval)
                                }
                            }
                        }
                    }
                    
                    // Track pointer movement
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            
                            if (change == null || !change.pressed) {
                                // Finger lifted
                                break
                            }
                            
                            // Accumulate horizontal drag
                            val dragDelta = change.positionChange()
                            cumulativeDragX += dragDelta.x
                            
                            // Check if we should enter swipe selection mode
                            if (!swipeModeActivated && holdJobStarted && cumulativeDragX < -swipeActivationThreshold) {
                                // User is swiping left while holding - enter swipe mode
                                swipeModeActivated = true
                                isInSwipeMode = true
                                repeatJob?.cancel() // Stop repeat delete
                                
                                // Note: onSwipeSelectStart was already called when hold was detected
                            }
                            
                            // Handle swipe selection
                            if (swipeModeActivated) {
                                // Calculate how many words should be selected based on drag distance
                                // Negative X = swipe left = select more words
                                val targetWords = ((-cumulativeDragX - swipeActivationThreshold) / pixelsPerWord)
                                    .toInt()
                                    .coerceAtLeast(0)
                                
                                // Extend selection (swipe left)
                                while (wordsSelected < targetWords) {
                                    if (onExtendSelectionLeft()) {
                                        wordsSelected++
                                    } else {
                                        break // No more words to select
                                    }
                                }
                                
                                // Reduce selection (swipe right / back)
                                while (wordsSelected > targetWords && wordsSelected > 0) {
                                    if (onReduceSelectionRight()) {
                                        wordsSelected--
                                    } else {
                                        break
                                    }
                                }
                            }
                            
                            change.consume()
                        }
                    } finally {
                        // Cleanup on release
                        holdDetectionJob.cancel()
                        repeatJob?.cancel()
                        
                        // If we were in swipe mode and have words selected, delete them
                        if (swipeModeActivated && wordsSelected > 0) {
                            onSwipeSelectEnd()
                        } else if (swipeModeActivated) {
                            // Swipe mode was active but no words selected - just cancel
                            // The selection state will be cleaned up by the service
                        }
                        
                        isPressed = false
                        isInSwipeMode = false
                        wordsSelected = 0
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Visual feedback
        if (isInSwipeMode && wordsSelected > 0) {
            // Show word count badge when selecting
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(18.dp)
                    .background(Color(0xFFFF453A), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = wordsSelected.toString(),
                    color = Color.White,
                    fontSize = 10.sp
                )
            }
        }
        
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Delete",
            tint = when {
                isInSwipeMode -> Color(0xFFFF453A) // Red when selecting
                isPressed -> Color.White.copy(alpha = 0.7f)
                else -> Color.White
            },
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Animated microphone button with tap and hold-to-record functionality
 */
@Composable
fun VoiceMicButton(
    isListening: Boolean,
    isProcessing: Boolean,
    isError: Boolean,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var holdStartTime by remember { mutableLongStateOf(0L) }
    
    // Threshold for distinguishing tap vs hold (200ms)
    val holdThresholdMs = 200L
    
    // Animated properties for the listening state
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    
    // Outer ring pulse animation
    val outerRingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_ring_scale"
    )
    
    val outerRingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_ring_alpha"
    )
    
    // Second ring with offset timing
    val secondRingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing, delayMillis = 333),
            repeatMode = RepeatMode.Restart
        ),
        label = "second_ring_scale"
    )
    
    val secondRingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing, delayMillis = 333),
            repeatMode = RepeatMode.Restart
        ),
        label = "second_ring_alpha"
    )
    
    // Subtle button glow/pulse
    val buttonGlow by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "button_glow"
    )
    
    // Button colors
    val buttonColor = when {
        isListening -> Color(0xFFFF453A) // Red when recording
        isProcessing -> Color(0xFFFF9F0A) // Orange when processing
        isError -> Color(0xFFFF453A).copy(alpha = 0.7f) // Dimmed red for error
        else -> Color(0xFF0A84FF) // Blue default
    }
    
    val glowColor = when {
        isListening -> Color(0xFFFF453A)
        isProcessing -> Color(0xFFFF9F0A)
        else -> Color(0xFF0A84FF)
    }
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(90.dp)
    ) {
        // Animated rings (only show when listening)
        if (isListening) {
            // Outer pulsing ring
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(outerRingScale)
                    .alpha(outerRingAlpha)
                    .background(
                        color = Color(0xFFFF453A),
                        shape = CircleShape
                    )
            )
            
            // Second pulsing ring
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(secondRingScale)
                    .alpha(secondRingAlpha)
                    .background(
                        color = Color(0xFFFF453A),
                        shape = CircleShape
                    )
            )
        }
        
        // Main button with glow effect when listening
        Box(
            modifier = Modifier
                .size(72.dp)
                .then(
                    if (isListening) {
                        Modifier.border(
                            width = (2 + buttonGlow * 2).dp,
                            color = glowColor.copy(alpha = 0.3f + buttonGlow * 0.3f),
                            shape = CircleShape
                        )
                    } else Modifier
                )
                .background(
                    color = buttonColor,
                    shape = CircleShape
                )
                .pointerInput(isProcessing) {
                    if (!isProcessing) {
                        detectTapGestures(
                            onPress = {
                                holdStartTime = System.currentTimeMillis()
                                isHolding = true
                                
                                // Wait for release
                                val released = tryAwaitRelease()
                                val holdDuration = System.currentTimeMillis() - holdStartTime
                                
                                if (released) {
                                    if (holdDuration < holdThresholdMs) {
                                        // Short tap - toggle recording
                                        onTap()
                                    } else {
                                        // Was holding - stop recording
                                        onHoldEnd()
                                    }
                                }
                                isHolding = false
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Check if we should start hold recording
            LaunchedEffect(isHolding, holdStartTime) {
                if (isHolding && !isListening && !isProcessing) {
                    delay(holdThresholdMs)
                    if (isHolding) {
                        // Still holding after threshold - start recording
                        onHoldStart()
                    }
                }
            }
            
            when {
                isProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                }
                isListening -> {
                    // Animated stop icon when recording
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop recording",
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Start recording",
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}
