/**
 * Permissions Utility
 * Centralized permission handling for Android and iOS
 */

import { Platform, PermissionsAndroid, Alert } from 'react-native';

export type PermissionType = 'microphone' | 'vibrate';

interface PermissionConfig {
  android: string;
  title: string;
  message: string;
}

const PERMISSION_CONFIGS: Record<PermissionType, PermissionConfig> = {
  microphone: {
    android: PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    title: 'Microphone Permission',
    message: 'VoiceKeyboard needs access to your microphone to transcribe speech.',
  },
  vibrate: {
    // VIBRATE is a normal permission in Android, granted automatically at install
    // No runtime request needed, but we handle it for safety
    android: 'android.permission.VIBRATE',
    title: 'Vibration Permission',
    message: 'VoiceKeyboard uses vibration for haptic feedback.',
  },
};

// Cache for permission status
const permissionCache: Record<PermissionType, boolean | null> = {
  microphone: null,
  vibrate: null,
};

/**
 * Check if a permission is granted
 */
export async function checkPermission(type: PermissionType): Promise<boolean> {
  if (Platform.OS !== 'android') {
    // iOS handles permissions via Info.plist prompts
    return true;
  }

  // Vibrate is a normal permission, always granted after manifest declaration
  if (type === 'vibrate') {
    return true;
  }

  try {
    const config = PERMISSION_CONFIGS[type];
    const result = await PermissionsAndroid.check(config.android as any);
    permissionCache[type] = result;
    return result;
  } catch (error) {
    console.error(`Error checking ${type} permission:`, error);
    return false;
  }
}

/**
 * Request a permission
 */
export async function requestPermission(type: PermissionType): Promise<boolean> {
  if (Platform.OS !== 'android') {
    return true;
  }

  // Vibrate doesn't need runtime permission
  if (type === 'vibrate') {
    return true;
  }

  try {
    const config = PERMISSION_CONFIGS[type];
    const result = await PermissionsAndroid.request(config.android as any, {
      title: config.title,
      message: config.message,
      buttonNeutral: 'Ask Me Later',
      buttonNegative: 'Cancel',
      buttonPositive: 'OK',
    });

    const granted = result === PermissionsAndroid.RESULTS.GRANTED;
    permissionCache[type] = granted;
    return granted;
  } catch (error) {
    console.error(`Error requesting ${type} permission:`, error);
    return false;
  }
}

/**
 * Ensure permission is granted, request if not
 */
export async function ensurePermission(type: PermissionType): Promise<boolean> {
  // Check cache first
  if (permissionCache[type] === true) {
    return true;
  }

  // Check current status
  const hasPermission = await checkPermission(type);
  if (hasPermission) {
    return true;
  }

  // Request permission
  return requestPermission(type);
}

/**
 * Request multiple permissions at once
 */
export async function requestMultiplePermissions(
  types: PermissionType[]
): Promise<Record<PermissionType, boolean>> {
  const results: Record<PermissionType, boolean> = {} as any;

  for (const type of types) {
    results[type] = await ensurePermission(type);
  }

  return results;
}

/**
 * Show alert when permission is denied
 */
export function showPermissionDeniedAlert(type: PermissionType): void {
  const config = PERMISSION_CONFIGS[type];
  Alert.alert(`${config.title} Required`, `${config.message} Please enable it in Settings.`, [
    { text: 'OK' },
  ]);
}

/**
 * Safe vibrate - only vibrates if we can (won't crash on permission issues)
 */
export async function safeVibrate(duration: number = 30): Promise<void> {
  try {
    const { Vibration } = require('react-native');
    Vibration.vibrate(duration);
  } catch (error) {
    // Silently fail - vibration is not critical
    console.log('Vibration not available:', error);
  }
}
