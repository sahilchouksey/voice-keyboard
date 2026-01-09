package com.voicekeyboard.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared audio recorder for both React Native app and IME service.
 * Records audio at 16kHz mono PCM and converts to WAV format.
 */
class SharedAudioRecorder(private val context: Context) {
    
    companion object {
        private const val TAG = "SharedAudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
    }
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var autoStopJob: Job? = null
    private val audioData = ByteArrayOutputStream()
    
    // Max recording duration in milliseconds (default 5 minutes)
    private var maxDurationMs: Long = 300_000L
    
    // Recording start time for countdown calculation
    private var recordingStartTimeMs: Long = 0L
    private var countdownJob: Job? = null
    
    // Callbacks
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((ByteArray) -> Unit)? = null
    var onAudioData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMaxDurationReached: (() -> Unit)? = null
    var onCountdownTick: ((Int) -> Unit)? = null  // Called with seconds remaining (10, 9, 8...1)
    var onAmplitudeChanged: ((Float) -> Unit)? = null  // Normalized amplitude 0.0-1.0 for waveform visualization
    
    val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }
    
    /**
     * Set the maximum recording duration
     * @param seconds Duration in seconds (10-300)
     */
    fun setMaxDuration(seconds: Int) {
        maxDurationMs = seconds.coerceIn(10, 300) * 1000L
        Log.d(TAG, "Max recording duration set to ${maxDurationMs}ms")
    }
    
    fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun startRecording(): Boolean {
        if (!hasPermission()) {
            onError?.invoke("Microphone permission not granted")
            return false
        }
        
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("Failed to initialize AudioRecord")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            audioData.reset()
            audioRecord?.startRecording()
            isRecording = true
            recordingStartTimeMs = System.currentTimeMillis()
            onRecordingStarted?.invoke()
            
            // Start recording in background
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(bufferSize)
                while (isRecording && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        synchronized(audioData) {
                            audioData.write(buffer, 0, read)
                        }
                        onAudioData?.invoke(buffer.copyOf(read))
                        
                        // Calculate amplitude for waveform visualization
                        val amplitude = calculateAmplitude(buffer, read)
                        withContext(Dispatchers.Main) {
                            onAmplitudeChanged?.invoke(amplitude)
                        }
                    }
                }
            }
            
            // Start auto-stop timer
            autoStopJob = CoroutineScope(Dispatchers.IO).launch {
                delay(maxDurationMs)
                if (isRecording) {
                    Log.d(TAG, "Max recording duration reached (${maxDurationMs}ms), auto-stopping")
                    withContext(Dispatchers.Main) {
                        onMaxDurationReached?.invoke()
                    }
                }
            }
            
            // Start countdown timer for last 10 seconds
            countdownJob = CoroutineScope(Dispatchers.IO).launch {
                val countdownStartMs = maxDurationMs - 10_000L
                if (countdownStartMs > 0) {
                    // Wait until we're 10 seconds from the end
                    delay(countdownStartMs)
                }
                
                // Now count down from 10 (or less if max duration < 10s)
                val startCountdown = minOf(10, (maxDurationMs / 1000).toInt())
                for (secondsRemaining in startCountdown downTo 1) {
                    if (!isRecording || !isActive) break
                    withContext(Dispatchers.Main) {
                        onCountdownTick?.invoke(secondsRemaining)
                    }
                    delay(1000L)
                }
            }
            
            Log.d(TAG, "Recording started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            onError?.invoke("Error starting recording: ${e.message}")
            stopRecording()
            return false
        }
    }
    
    fun stopRecording(): ByteArray? {
        if (!isRecording) {
            return null
        }
        
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        autoStopJob?.cancel()
        autoStopJob = null
        countdownJob?.cancel()
        countdownJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        
        val pcmData = synchronized(audioData) {
            audioData.toByteArray()
        }
        
        val wavData = pcmToWav(pcmData)
        onRecordingStopped?.invoke(wavData)
        
        Log.d(TAG, "Recording stopped, PCM: ${pcmData.size} bytes, WAV: ${wavData.size} bytes")
        return wavData
    }
    
    fun isRecording(): Boolean = isRecording
    
    /**
     * Get the recorded audio as a Base64-encoded WAV string
     */
    fun getBase64Wav(): String? {
        val wavData = stopRecording() ?: return null
        return Base64.encodeToString(wavData, Base64.NO_WRAP)
    }
    
    /**
     * Convert PCM audio data to WAV format
     */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        
        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            
            // RIFF header
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            
            // fmt chunk
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size for PCM
            putShort(1) // AudioFormat (1 = PCM)
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            
            // data chunk
            put("data".toByteArray())
            putInt(pcmData.size)
        }
        
        return header.array() + pcmData
    }
    
    fun release() {
        stopRecording()
    }
    
    /**
     * Calculate normalized amplitude (0.0-1.0) from PCM 16-bit audio data
     * Uses RMS (Root Mean Square) for more accurate amplitude measurement
     */
    private fun calculateAmplitude(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        
        var sum = 0.0
        var sampleCount = 0
        
        // PCM 16-bit: 2 bytes per sample, little-endian
        var i = 0
        while (i < length - 1) {
            // Convert 2 bytes to 16-bit signed sample
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            // Convert to signed
            val signedSample = if (sample > 32767) sample - 65536 else sample
            
            sum += signedSample.toDouble() * signedSample.toDouble()
            sampleCount++
            i += 2
        }
        
        if (sampleCount == 0) return 0f
        
        // Calculate RMS
        val rms = kotlin.math.sqrt(sum / sampleCount)
        
        // Normalize to 0.0-1.0 range (32768 is max value for 16-bit audio)
        // Apply some gain to make quiet sounds more visible
        val normalized = (rms / 32768.0 * 3.0).coerceIn(0.0, 1.0)
        
        return normalized.toFloat()
    }
}
