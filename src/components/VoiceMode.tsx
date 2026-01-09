/**
 * Voice Mode Component
 * Full-screen voice recording with mic button
 */

import React, { useCallback, useState, useEffect } from 'react';
import {
  View,
  StyleSheet,
  Pressable,
  Text,
  Animated,
  ActivityIndicator,
  Alert,
} from 'react-native';
import { useAudioRecorder } from '../hooks/useAudioRecorder';
import { API_ENDPOINTS } from '../config/api';
import { safeVibrate, ensurePermission } from '../utils/permissions';

interface VoiceModeProps {
  onTranscription: (text: string) => void;
  onSwitchToText: () => void;
}

export function VoiceMode({ onTranscription, onSwitchToText }: VoiceModeProps) {
  const {
    isRecording,
    isProcessing,
    duration,
    startRecording,
    stopRecording,
    error,
    hasPermission,
  } = useAudioRecorder();
  const [isTranscribing, setIsTranscribing] = useState(false);
  const [pulseAnim] = useState(new Animated.Value(1));
  const [transcriptionError, setTranscriptionError] = useState<string | null>(null);

  // Request microphone permission on mount
  useEffect(() => {
    ensurePermission('microphone');
  }, []);

  // Show error alert if permission denied
  useEffect(() => {
    if (error) {
      Alert.alert('Recording Error', error);
    }
  }, [error]);

  // Pulse animation when recording
  useEffect(() => {
    if (isRecording) {
      const pulse = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1.15, duration: 600, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1, duration: 600, useNativeDriver: true }),
        ])
      );
      pulse.start();
      return () => pulse.stop();
    }
    pulseAnim.setValue(1);
  }, [isRecording, pulseAnim]);

  const handlePressIn = useCallback(async () => {
    setTranscriptionError(null);
    await safeVibrate(30);
    await startRecording();
  }, [startRecording]);

  const handlePressOut = useCallback(async () => {
    console.log('[VoiceMode] handlePressOut - stopping recording');
    await safeVibrate(30);

    const stopStart = Date.now();
    const audioBase64 = await stopRecording();
    console.log('[VoiceMode] stopRecording took:', Date.now() - stopStart, 'ms');

    if (!audioBase64) {
      console.log('[VoiceMode] No audio data returned');
      return;
    }

    console.log('[VoiceMode] Audio base64 length:', audioBase64.length);
    setIsTranscribing(true);

    try {
      console.log('[VoiceMode] Sending to:', API_ENDPOINTS.transcribe);
      const fetchStart = Date.now();

      const res = await fetch(API_ENDPOINTS.transcribe, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ audio: audioBase64 }),
      });

      console.log('[VoiceMode] Fetch took:', Date.now() - fetchStart, 'ms, status:', res.status);

      if (!res.ok) {
        const errorText = await res.text();
        console.error('[VoiceMode] Server error response:', errorText);
        throw new Error(`Server error: ${res.status}`);
      }

      const data = await res.json();
      console.log('[VoiceMode] Response data:', data);

      if (data.text) {
        onTranscription(data.text);
      } else if (data.error) {
        setTranscriptionError(data.error);
      }
    } catch (e: any) {
      console.error('[VoiceMode] Transcription error:', e);
      setTranscriptionError(e.message || 'Failed to transcribe');
    } finally {
      setIsTranscribing(false);
    }
  }, [stopRecording, onTranscription]);

  const formatDuration = (s: number) =>
    `${Math.floor(s / 60)}:${(s % 60).toString().padStart(2, '0')}`;

  const isLoading = isProcessing || isTranscribing;

  const getHintText = () => {
    if (hasPermission === false) return 'Microphone permission required';
    if (isRecording) return 'Release to transcribe';
    if (isLoading) return 'Transcribing...';
    if (transcriptionError) return transcriptionError;
    return 'Hold to speak';
  };

  return (
    <View style={styles.container}>
      {/* Duration */}
      {(isRecording || isLoading) && (
        <Text style={styles.duration}>
          {isRecording ? formatDuration(duration) : 'Processing...'}
        </Text>
      )}

      {/* Mic Button */}
      <Animated.View style={{ transform: [{ scale: pulseAnim }] }}>
        <Pressable
          onPressIn={handlePressIn}
          onPressOut={handlePressOut}
          disabled={isLoading}
          style={[
            styles.micButton,
            isRecording && styles.micRecording,
            isLoading && styles.micDisabled,
            hasPermission === false && styles.micNoPermission,
          ]}
        >
          {isLoading ? (
            <ActivityIndicator size="large" color="#FFF" />
          ) : (
            <Text style={styles.micIcon}>{hasPermission === false ? '🚫' : '🎤'}</Text>
          )}
        </Pressable>
      </Animated.View>

      <Text style={[styles.hint, transcriptionError && styles.hintError]}>{getHintText()}</Text>

      {/* Switch to Text Mode */}
      <Pressable style={styles.switchButton} onPress={onSwitchToText}>
        <Text style={styles.switchText}>tT</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
    justifyContent: 'center',
    alignItems: 'center',
  },
  duration: {
    color: '#FFF',
    fontSize: 28,
    fontWeight: '200',
    marginBottom: 30,
    fontVariant: ['tabular-nums'],
  },
  micButton: {
    width: 140,
    height: 140,
    borderRadius: 70,
    backgroundColor: '#1C1C1E',
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 3,
    borderColor: '#333',
  },
  micRecording: {
    backgroundColor: '#FF3B30',
    borderColor: '#FF6B6B',
  },
  micDisabled: {
    opacity: 0.6,
  },
  micNoPermission: {
    backgroundColor: '#4A4A4C',
    borderColor: '#666',
  },
  micIcon: {
    fontSize: 50,
  },
  hint: {
    color: '#666',
    fontSize: 14,
    marginTop: 24,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    textAlign: 'center',
    paddingHorizontal: 20,
  },
  hintError: {
    color: '#FF6B6B',
  },
  switchButton: {
    position: 'absolute',
    bottom: 40,
    left: 30,
    width: 50,
    height: 50,
    borderRadius: 25,
    backgroundColor: '#333',
    justifyContent: 'center',
    alignItems: 'center',
  },
  switchText: {
    color: '#FFF',
    fontSize: 18,
    fontWeight: '600',
  },
});

export default VoiceMode;
