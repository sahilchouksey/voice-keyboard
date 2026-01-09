/**
 * Audio Recorder Hook
 * Records audio and returns WAV base64
 */

import { useState, useRef, useCallback, useEffect } from 'react';
import LiveAudioStream from 'react-native-live-audio-stream';
import { Buffer } from 'buffer';
import { ensurePermission, showPermissionDeniedAlert } from '../utils/permissions';

const SAMPLE_RATE = 16000;
const CHANNELS = 1;
const BITS_PER_SAMPLE = 16;

function createWavHeader(dataLength: number): Uint8Array {
  const header = new ArrayBuffer(44);
  const view = new DataView(header);
  const writeString = (offset: number, str: string) => {
    for (let i = 0; i < str.length; i++) {
      view.setUint8(offset + i, str.charCodeAt(i));
    }
  };

  writeString(0, 'RIFF');
  view.setUint32(4, 36 + dataLength, true);
  writeString(8, 'WAVE');
  writeString(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, CHANNELS, true);
  view.setUint32(24, SAMPLE_RATE, true);
  view.setUint32(28, SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8), true);
  view.setUint16(32, CHANNELS * (BITS_PER_SAMPLE / 8), true);
  view.setUint16(34, BITS_PER_SAMPLE, true);
  writeString(36, 'data');
  view.setUint32(40, dataLength, true);

  return new Uint8Array(header);
}

export function useAudioRecorder() {
  const [isRecording, setIsRecording] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [duration, setDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [hasPermission, setHasPermission] = useState<boolean | null>(null);

  const chunksRef = useRef<string[]>([]);
  const startTimeRef = useRef<number>(0);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const isInitRef = useRef(false);

  const initAudioStream = useCallback(() => {
    if (isInitRef.current) return;

    LiveAudioStream.init({
      sampleRate: SAMPLE_RATE,
      channels: CHANNELS,
      bitsPerSample: BITS_PER_SAMPLE,
      audioSource: 6, // VOICE_RECOGNITION
      bufferSize: 4096,
      wavFile: '',
    });

    LiveAudioStream.on('data', (data: string) => {
      chunksRef.current.push(data);
    });

    isInitRef.current = true;
  }, []);

  // Request permission on mount
  useEffect(() => {
    const init = async () => {
      const granted = await ensurePermission('microphone');
      setHasPermission(granted);

      if (granted) {
        initAudioStream();
      }
    };

    init();

    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [initAudioStream]);

  const startRecording = useCallback(async () => {
    setError(null);

    // Ensure microphone permission before recording
    const granted = await ensurePermission('microphone');
    setHasPermission(granted);

    if (!granted) {
      setError('Microphone permission denied');
      showPermissionDeniedAlert('microphone');
      return;
    }

    // Initialize audio stream if not done yet
    initAudioStream();

    chunksRef.current = [];

    try {
      LiveAudioStream.start();
      startTimeRef.current = Date.now();
      setDuration(0);
      intervalRef.current = setInterval(() => {
        setDuration(Math.floor((Date.now() - startTimeRef.current) / 1000));
      }, 100);
      setIsRecording(true);
    } catch (e) {
      console.error('Failed to start recording:', e);
      setError('Failed to start recording');
    }
  }, [initAudioStream]);

  const stopRecording = useCallback(async (): Promise<string | null> => {
    console.log('[AudioRecorder] stopRecording called, isRecording:', isRecording);
    if (!isRecording) return null;
    setIsProcessing(true);

    const stopStart = Date.now();
    try {
      LiveAudioStream.stop();
      console.log('[AudioRecorder] LiveAudioStream.stop() took:', Date.now() - stopStart, 'ms');
    } catch (e) {
      console.error('[AudioRecorder] Error stopping recording:', e);
    }

    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    setIsRecording(false);

    console.log('[AudioRecorder] Chunks count:', chunksRef.current.length);
    if (chunksRef.current.length === 0) {
      setError('No audio recorded');
      setIsProcessing(false);
      return null;
    }

    const processStart = Date.now();
    const pcmBuffers = chunksRef.current.map((c) => Buffer.from(c, 'base64'));
    const totalLen = pcmBuffers.reduce((a, b) => a + b.length, 0);
    console.log('[AudioRecorder] Total PCM length:', totalLen, 'bytes');

    const combinedPcm = Buffer.concat(pcmBuffers, totalLen);
    chunksRef.current = [];

    const header = createWavHeader(combinedPcm.length);
    const wavBuffer = Buffer.concat([Buffer.from(header), combinedPcm]);
    const wavBase64 = wavBuffer.toString('base64');

    console.log('[AudioRecorder] WAV processing took:', Date.now() - processStart, 'ms');
    console.log('[AudioRecorder] WAV base64 length:', wavBase64.length);

    setIsProcessing(false);
    return wavBase64;
  }, [isRecording]);

  return {
    isRecording,
    isProcessing,
    duration,
    startRecording,
    stopRecording,
    error,
    hasPermission,
  };
}
