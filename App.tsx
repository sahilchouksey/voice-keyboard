/**
 * Voice Keyboard App
 * Settings and configuration app for Voice Keyboard IME
 */

import React, { useEffect } from 'react';
import { StatusBar } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { SettingsScreen } from './src/components/SettingsScreen';
import { VoiceKeyboard } from './src/native/VoiceKeyboard';
import { API_URL } from './src/config/api';

function App() {
  // Set API URL in SharedPreferences on app startup
  // This allows the IME service to use the same API URL
  useEffect(() => {
    const initializeApiUrl = async () => {
      try {
        const success = await VoiceKeyboard.setApiUrl(API_URL);
        if (success) {
          console.log('[App] API URL set in SharedPreferences:', API_URL);
        } else {
          console.warn('[App] Failed to set API URL in SharedPreferences');
        }
      } catch (error) {
        console.error('[App] Error setting API URL:', error);
      }
    };
    initializeApiUrl();
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#000000" />
      <SafeAreaView style={{ flex: 1, backgroundColor: '#000000' }} edges={['top']}>
        <SettingsScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

export default App;
