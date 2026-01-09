/**
 * API Configuration
 * Platform-aware API URL configuration
 */

import { Platform } from 'react-native';

/**
 * Get the appropriate API base URL based on platform and environment
 *
 * Development environments:
 * - Android Emulator: 10.0.2.2 (special alias for host machine localhost)
 * - iOS Simulator: localhost (shares network with host)
 * - Real devices with ADB reverse: localhost (adb reverse tcp:3002 tcp:3002)
 * - Real devices without ADB: Use your machine's local IP address
 *
 * For real device testing WITH adb reverse proxy:
 *   Run: adb reverse tcp:3002 tcp:3002
 *   Then localhost:3002 on phone maps to localhost:3002 on your Mac
 */

// Set to true when using `adb reverse tcp:3002 tcp:3002`
// NOTE: ADB reverse does NOT work for IME services - they use a different network stack
// So we always use the actual machine IP for real device testing
const USE_ADB_REVERSE = false;

// Your machine's local IP for testing on real devices
// This IP is used by both the RN app and the IME service
const LOCAL_MACHINE_IP = '10.176.39.252';

const API_PORT = 3002;

function getDevApiUrl(): string {
  if (Platform.OS === 'android') {
    // With ADB reverse proxy, localhost on phone maps to localhost on Mac
    if (USE_ADB_REVERSE) {
      return `http://localhost:${API_PORT}`;
    }
    // Without ADB reverse, use the machine's actual IP
    return `http://${LOCAL_MACHINE_IP}:${API_PORT}`;
  }
  // iOS simulator shares network with host
  return `http://localhost:${API_PORT}`;
}

function getProdApiUrl(): string {
  // Replace with your production API URL
  return 'https://your-backend.com';
}

/**
 * API base URL - automatically selects based on environment and platform
 */
export const API_URL = __DEV__ ? getDevApiUrl() : getProdApiUrl();

/**
 * API endpoints
 */
export const API_ENDPOINTS = {
  transcribe: `${API_URL}/transcribe`,
  health: `${API_URL}/health`,
} as const;

/**
 * For real device testing, call this with your machine's IP
 * Returns the URL to use
 *
 * Usage:
 * const url = getApiUrlForRealDevice('192.168.1.50');
 */
export function getApiUrlForRealDevice(machineIp: string): string {
  return `http://${machineIp}:${API_PORT}`;
}

export default { API_URL, API_ENDPOINTS, getApiUrlForRealDevice };
