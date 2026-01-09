import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { VoiceKeyboardModule } = NativeModules;

// Create event emitter
const eventEmitter =
  Platform.OS === 'android' && VoiceKeyboardModule
    ? new NativeEventEmitter(VoiceKeyboardModule)
    : null;

// Types
export interface TranscriptionResult {
  success: boolean;
  text: string | null;
  error: string | null;
}

export interface KeyboardSettings {
  apiUrl: string;
  keyboardMode: 'VOICE' | 'TEXT';
  autoSend: boolean;
  hapticFeedback: boolean;
  soundFeedback: boolean;
  maxRecordingDuration: number; // Duration in seconds (10-300)
}

export type RecordingEventCallback = () => void;
export type TranscriptionEventCallback = (result: TranscriptionResult) => void;
export type ErrorEventCallback = (error: { error: string }) => void;

/**
 * Voice Keyboard Native Module
 * Provides access to native keyboard functionality
 */
export const VoiceKeyboard = {
  // ==================== Recording Methods ====================

  /**
   * Start recording audio
   */
  startRecording: async (): Promise<boolean> => {
    if (!VoiceKeyboardModule) {
      console.warn('VoiceKeyboardModule not available');
      return false;
    }
    return VoiceKeyboardModule.startRecording();
  },

  /**
   * Stop recording and get Base64 WAV data
   */
  stopRecording: async (): Promise<string | null> => {
    if (!VoiceKeyboardModule) {
      console.warn('VoiceKeyboardModule not available');
      return null;
    }
    return VoiceKeyboardModule.stopRecording();
  },

  /**
   * Check if currently recording
   */
  isRecording: async (): Promise<boolean> => {
    if (!VoiceKeyboardModule) return false;
    return VoiceKeyboardModule.isRecording();
  },

  /**
   * Check if microphone permission is granted
   */
  hasPermission: async (): Promise<boolean> => {
    if (!VoiceKeyboardModule) return false;
    return VoiceKeyboardModule.hasPermission();
  },

  /**
   * Request microphone permission
   * Returns true if already granted, false if request was sent (check hasPermission after)
   */
  requestMicrophonePermission: async (): Promise<boolean> => {
    if (!VoiceKeyboardModule) return false;
    return VoiceKeyboardModule.requestMicrophonePermission();
  },

  /**
   * Open app settings (for manually granting permissions)
   */
  openAppSettings: (): void => {
    VoiceKeyboardModule?.openAppSettings();
  },

  // ==================== Transcription Methods ====================

  /**
   * Transcribe Base64 audio data
   */
  transcribe: async (base64Audio: string): Promise<TranscriptionResult> => {
    if (!VoiceKeyboardModule) {
      return { success: false, text: null, error: 'Module not available' };
    }
    return VoiceKeyboardModule.transcribe(base64Audio);
  },

  /**
   * Ping the transcription server
   */
  pingServer: async (): Promise<boolean> => {
    if (!VoiceKeyboardModule) return false;
    return VoiceKeyboardModule.pingServer();
  },

  // ==================== Settings Methods ====================

  /**
   * Set the API URL
   */
  setApiUrl: async (url: string): Promise<boolean> => {
    if (!VoiceKeyboardModule) return false;
    return VoiceKeyboardModule.setApiUrl(url);
  },

  /**
   * Get the current API URL
   */
  getApiUrl: async (): Promise<string> => {
    if (!VoiceKeyboardModule) return '';
    return VoiceKeyboardModule.getApiUrl();
  },

  /**
   * Get all settings
   */
  getSettings: async (): Promise<KeyboardSettings | null> => {
    if (!VoiceKeyboardModule) return null;
    return VoiceKeyboardModule.getSettings();
  },

  /**
   * Update settings
   */
  updateSettings: async (settings: Partial<KeyboardSettings>): Promise<boolean> => {
    if (!VoiceKeyboardModule) return false;
    return VoiceKeyboardModule.updateSettings(settings);
  },

  // ==================== Keyboard Management ====================

  /**
   * Check if the keyboard is enabled in system settings
   */
  isKeyboardEnabled: async (): Promise<boolean> => {
    if (!VoiceKeyboardModule) return false;
    return VoiceKeyboardModule.isKeyboardEnabled();
  },

  /**
   * Open system input method settings
   */
  openInputMethodSettings: (): void => {
    VoiceKeyboardModule?.openInputMethodSettings();
  },

  /**
   * Show input method picker
   */
  openInputMethodPicker: (): void => {
    VoiceKeyboardModule?.openInputMethodPicker();
  },

  /**
   * Open keyboard settings activity
   */
  openKeyboardSettings: (): void => {
    VoiceKeyboardModule?.openKeyboardSettings();
  },

  // ==================== Event Listeners ====================

  /**
   * Subscribe to recording started event
   */
  onRecordingStarted: (callback: RecordingEventCallback) => {
    if (!eventEmitter) return { remove: () => {} };
    return eventEmitter.addListener('onRecordingStarted', callback);
  },

  /**
   * Subscribe to transcription result event
   */
  onTranscriptionResult: (callback: TranscriptionEventCallback) => {
    if (!eventEmitter) return { remove: () => {} };
    return eventEmitter.addListener('onTranscriptionResult', callback);
  },

  /**
   * Subscribe to recording error event
   */
  onRecordingError: (callback: ErrorEventCallback) => {
    if (!eventEmitter) return { remove: () => {} };
    return eventEmitter.addListener('onRecordingError', callback);
  },
};

export default VoiceKeyboard;
