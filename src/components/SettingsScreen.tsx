/**
 * Settings Screen - Primary app screen
 * Clean, minimalist design for keyboard configuration
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Switch,
  TextInput,
  ActivityIndicator,
  Linking,
  Alert,
  AppState,
  AppStateStatus,
} from 'react-native';
import { VoiceKeyboard, KeyboardSettings } from '../native/VoiceKeyboard';

// Minimal color palette - professional dark theme
const colors = {
  background: '#000000',
  surface: '#1C1C1E',
  surfaceElevated: '#2C2C2E',
  border: '#38383A',
  textPrimary: '#FFFFFF',
  textSecondary: '#8E8E93',
  textTertiary: '#636366',
  accent: '#FFFFFF',
  success: '#34C759',
  error: '#FF3B30',
  warning: '#FF9500',
};

// Duration preset options for max recording time
const DURATION_PRESETS = [
  { label: '10s', value: 10 },
  { label: '30s', value: 30 },
  { label: '1m', value: 60 },
  { label: '2m', value: 120 },
  { label: '5m', value: 300 },
];

interface SettingsSectionProps {
  title: string;
  children: React.ReactNode;
}

function SettingsSection({ title, children }: SettingsSectionProps) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      <View style={styles.sectionContent}>{children}</View>
    </View>
  );
}

interface SettingsRowProps {
  label: string;
  description?: string;
  onPress?: () => void;
  rightElement?: React.ReactNode;
  showChevron?: boolean;
  isLast?: boolean;
}

function SettingsRow({
  label,
  description,
  onPress,
  rightElement,
  showChevron = false,
  isLast = false,
}: SettingsRowProps) {
  const content = (
    <View style={[styles.row, !isLast && styles.rowBorder]}>
      <View style={styles.rowLeft}>
        <Text style={styles.rowLabel}>{label}</Text>
        {description && <Text style={styles.rowDescription}>{description}</Text>}
      </View>
      <View style={styles.rowRight}>
        {rightElement}
        {showChevron && <Text style={styles.chevron}>›</Text>}
      </View>
    </View>
  );

  if (onPress) {
    return (
      <TouchableOpacity onPress={onPress} activeOpacity={0.6}>
        {content}
      </TouchableOpacity>
    );
  }
  return content;
}

interface StatusBadgeProps {
  status: 'enabled' | 'disabled' | 'checking';
  label?: string;
}

function StatusBadge({ status, label }: StatusBadgeProps) {
  const statusColors = {
    enabled: colors.success,
    disabled: colors.textTertiary,
    checking: colors.textSecondary,
  };

  const statusLabels = {
    enabled: label || 'Enabled',
    disabled: label || 'Disabled',
    checking: 'Checking...',
  };

  return (
    <View style={[styles.badge, { backgroundColor: statusColors[status] + '20' }]}>
      <View style={[styles.badgeDot, { backgroundColor: statusColors[status] }]} />
      <Text style={[styles.badgeText, { color: statusColors[status] }]}>
        {statusLabels[status]}
      </Text>
    </View>
  );
}

interface DurationPickerProps {
  value: number;
  onChange: (value: number) => void;
}

function DurationPicker({ value, onChange }: DurationPickerProps) {
  return (
    <View style={styles.durationPicker}>
      {DURATION_PRESETS.map((preset) => (
        <TouchableOpacity
          key={preset.value}
          style={[styles.durationButton, value === preset.value && styles.durationButtonActive]}
          onPress={() => onChange(preset.value)}
          activeOpacity={0.6}
        >
          <Text
            style={[
              styles.durationButtonText,
              value === preset.value && styles.durationButtonTextActive,
            ]}
          >
            {preset.label}
          </Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

export function SettingsScreen() {
  const [isLoading, setIsLoading] = useState(true);
  const [settings, setSettings] = useState<KeyboardSettings | null>(null);
  const [keyboardEnabled, setKeyboardEnabled] = useState<boolean | null>(null);
  const [micPermission, setMicPermission] = useState<boolean | null>(null);
  const [serverStatus, setServerStatus] = useState<'connected' | 'disconnected' | 'checking'>(
    'checking'
  );
  const [apiUrlInput, setApiUrlInput] = useState('');
  const [isEditingUrl, setIsEditingUrl] = useState(false);

  // Load settings on mount
  useEffect(() => {
    loadSettings();
    checkKeyboardStatus();
    checkMicPermission();
    checkServerConnection();
  }, []);

  // Re-check permission when app comes to foreground (user may have granted in settings)
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (nextAppState: AppStateStatus) => {
      if (nextAppState === 'active') {
        checkMicPermission();
        checkKeyboardStatus();
      }
    });

    return () => {
      subscription.remove();
    };
  }, []);

  const loadSettings = async () => {
    try {
      const currentSettings = await VoiceKeyboard.getSettings();
      setSettings(currentSettings);
      setApiUrlInput(currentSettings?.apiUrl || '');
    } catch (error) {
      console.error('Failed to load settings:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const checkKeyboardStatus = async () => {
    try {
      const enabled = await VoiceKeyboard.isKeyboardEnabled();
      setKeyboardEnabled(enabled);
    } catch (error) {
      console.error('Failed to check keyboard status:', error);
      setKeyboardEnabled(false);
    }
  };

  const checkMicPermission = async () => {
    try {
      const hasPermission = await VoiceKeyboard.hasPermission();
      setMicPermission(hasPermission);
    } catch (error) {
      console.error('Failed to check mic permission:', error);
      setMicPermission(false);
    }
  };

  const handleRequestMicPermission = async () => {
    try {
      const result = await VoiceKeyboard.requestMicrophonePermission();
      if (result) {
        setMicPermission(true);
      } else {
        // Permission request sent, will be updated via AppState listener
        // Or show option to open settings if denied
        setTimeout(async () => {
          const hasPermission = await VoiceKeyboard.hasPermission();
          setMicPermission(hasPermission);
          if (!hasPermission) {
            Alert.alert(
              'Permission Required',
              'Microphone permission is required for voice input. Please grant it in app settings.',
              [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Open Settings', onPress: () => VoiceKeyboard.openAppSettings() },
              ]
            );
          }
        }, 500);
      }
    } catch (error) {
      console.error('Failed to request mic permission:', error);
    }
  };

  const checkServerConnection = async () => {
    setServerStatus('checking');
    try {
      const reachable = await VoiceKeyboard.pingServer();
      setServerStatus(reachable ? 'connected' : 'disconnected');
    } catch (error) {
      setServerStatus('disconnected');
    }
  };

  const updateSetting = useCallback(async (key: keyof KeyboardSettings, value: any) => {
    try {
      await VoiceKeyboard.updateSettings({ [key]: value });
      setSettings((prev) => (prev ? { ...prev, [key]: value } : null));
    } catch (error) {
      console.error(`Failed to update ${key}:`, error);
    }
  }, []);

  const handleSaveApiUrl = async () => {
    if (!apiUrlInput.trim()) {
      Alert.alert('Error', 'Please enter a valid URL');
      return;
    }

    try {
      await VoiceKeyboard.setApiUrl(apiUrlInput.trim());
      setSettings((prev) => (prev ? { ...prev, apiUrl: apiUrlInput.trim() } : null));
      setIsEditingUrl(false);

      // Re-check server connection with new URL
      checkServerConnection();
    } catch (error) {
      Alert.alert('Error', 'Failed to save API URL');
    }
  };

  const handleOpenKeyboardSettings = () => {
    VoiceKeyboard.openInputMethodSettings();
  };

  const handleSwitchKeyboard = () => {
    VoiceKeyboard.openInputMethodPicker();
  };

  if (isLoading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={colors.textSecondary} />
      </View>
    );
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.contentContainer}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>VoiceFlow</Text>
        <Text style={styles.subtitle}>Settings</Text>
      </View>

      {/* Permissions Section - FIRST for visibility when opening from keyboard */}
      <SettingsSection title="PERMISSIONS">
        <SettingsRow
          label="Microphone"
          description={micPermission ? 'Permission granted' : 'Required for voice input'}
          rightElement={
            micPermission ? (
              <StatusBadge status="enabled" label="Granted" />
            ) : (
              <TouchableOpacity
                style={styles.grantButton}
                onPress={handleRequestMicPermission}
                activeOpacity={0.6}
              >
                <Text style={styles.grantButtonText}>Grant</Text>
              </TouchableOpacity>
            )
          }
          isLast
        />
      </SettingsSection>

      {/* Keyboard Status Section */}
      <SettingsSection title="KEYBOARD">
        <SettingsRow
          label="Keyboard Status"
          description={keyboardEnabled ? 'VoiceFlow is enabled' : 'Enable in system settings'}
          rightElement={
            <StatusBadge
              status={
                keyboardEnabled === null ? 'checking' : keyboardEnabled ? 'enabled' : 'disabled'
              }
            />
          }
        />
        <SettingsRow
          label="Enable Keyboard"
          description="Open system input settings"
          onPress={handleOpenKeyboardSettings}
          showChevron
        />
        <SettingsRow
          label="Switch Keyboard"
          description="Choose active input method"
          onPress={handleSwitchKeyboard}
          showChevron
          isLast
        />
      </SettingsSection>

      {/* Server Connection Section */}
      <SettingsSection title="SERVER">
        <SettingsRow
          label="Connection Status"
          rightElement={
            <TouchableOpacity onPress={checkServerConnection} activeOpacity={0.6}>
              <StatusBadge
                status={
                  serverStatus === 'checking'
                    ? 'checking'
                    : serverStatus === 'connected'
                      ? 'enabled'
                      : 'disabled'
                }
                label={
                  serverStatus === 'connected'
                    ? 'Connected'
                    : serverStatus === 'checking'
                      ? 'Checking...'
                      : 'Disconnected'
                }
              />
            </TouchableOpacity>
          }
        />
        <View style={styles.urlRow}>
          <Text style={styles.rowLabel}>API URL</Text>
          {isEditingUrl ? (
            <View style={styles.urlInputContainer}>
              <TextInput
                style={styles.urlInput}
                value={apiUrlInput}
                onChangeText={setApiUrlInput}
                placeholder="http://192.168.1.100:3002"
                placeholderTextColor={colors.textTertiary}
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType="url"
              />
              <View style={styles.urlButtons}>
                <TouchableOpacity
                  style={styles.urlButton}
                  onPress={() => {
                    setApiUrlInput(settings?.apiUrl || '');
                    setIsEditingUrl(false);
                  }}
                >
                  <Text style={styles.urlButtonTextCancel}>Cancel</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.urlButton} onPress={handleSaveApiUrl}>
                  <Text style={styles.urlButtonTextSave}>Save</Text>
                </TouchableOpacity>
              </View>
            </View>
          ) : (
            <TouchableOpacity
              style={styles.urlValueContainer}
              onPress={() => setIsEditingUrl(true)}
              activeOpacity={0.6}
            >
              <Text style={styles.urlValue} numberOfLines={1}>
                {settings?.apiUrl || 'Not configured'}
              </Text>
              <Text style={styles.editText}>Edit</Text>
            </TouchableOpacity>
          )}
        </View>
      </SettingsSection>

      {/* Preferences Section */}
      <SettingsSection title="PREFERENCES">
        <SettingsRow
          label="Haptic Feedback"
          description="Vibrate on key press"
          rightElement={
            <Switch
              value={settings?.hapticFeedback ?? true}
              onValueChange={(value) => updateSetting('hapticFeedback', value)}
              trackColor={{ false: colors.surfaceElevated, true: colors.success + '60' }}
              thumbColor={settings?.hapticFeedback ? colors.success : colors.textSecondary}
            />
          }
        />
        <SettingsRow
          label="Sound Feedback"
          description="Play sound on key press"
          rightElement={
            <Switch
              value={settings?.soundFeedback ?? false}
              onValueChange={(value) => updateSetting('soundFeedback', value)}
              trackColor={{ false: colors.surfaceElevated, true: colors.success + '60' }}
              thumbColor={settings?.soundFeedback ? colors.success : colors.textSecondary}
            />
          }
        />
        <SettingsRow
          label="Auto Send"
          description="Send text automatically after transcription"
          rightElement={
            <Switch
              value={settings?.autoSend ?? false}
              onValueChange={(value) => updateSetting('autoSend', value)}
              trackColor={{ false: colors.surfaceElevated, true: colors.success + '60' }}
              thumbColor={settings?.autoSend ? colors.success : colors.textSecondary}
            />
          }
        />
        <View style={styles.durationRow}>
          <Text style={styles.durationLabel}>Max Recording Duration</Text>
          <Text style={styles.durationDescription}>Auto-stop recording after this time</Text>
          <DurationPicker
            value={settings?.maxRecordingDuration ?? 300}
            onChange={(value) => updateSetting('maxRecordingDuration', value)}
          />
        </View>
      </SettingsSection>

      {/* Quick Actions Section */}
      <SettingsSection title="QUICK ACTIONS">
        <SettingsRow
          label="Test Connection"
          description="Verify server connectivity"
          onPress={checkServerConnection}
          showChevron
        />
        <SettingsRow
          label="Refresh Status"
          description="Check keyboard and server status"
          onPress={() => {
            checkKeyboardStatus();
            checkServerConnection();
          }}
          showChevron
          isLast
        />
      </SettingsSection>

      {/* Info Section */}
      <View style={styles.infoSection}>
        <Text style={styles.infoText}>
          To use VoiceFlow, enable it in system settings and select it as your input method.
        </Text>
        <Text style={styles.versionText}>Version 1.0.0</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  contentContainer: {
    paddingBottom: 40,
  },
  loadingContainer: {
    flex: 1,
    backgroundColor: colors.background,
    justifyContent: 'center',
    alignItems: 'center',
  },
  header: {
    paddingHorizontal: 20,
    paddingTop: 20,
    paddingBottom: 30,
  },
  title: {
    fontSize: 34,
    fontWeight: '700',
    color: colors.textPrimary,
    letterSpacing: -0.5,
  },
  subtitle: {
    fontSize: 17,
    color: colors.textSecondary,
    marginTop: 4,
  },
  section: {
    marginBottom: 24,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '600',
    color: colors.textSecondary,
    letterSpacing: 0.5,
    paddingHorizontal: 20,
    marginBottom: 8,
  },
  sectionContent: {
    backgroundColor: colors.surface,
    marginHorizontal: 16,
    borderRadius: 12,
    overflow: 'hidden',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 14,
    paddingHorizontal: 16,
    minHeight: 54,
  },
  rowBorder: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  rowLeft: {
    flex: 1,
    marginRight: 12,
  },
  rowLabel: {
    fontSize: 17,
    color: colors.textPrimary,
  },
  rowDescription: {
    fontSize: 13,
    color: colors.textSecondary,
    marginTop: 2,
  },
  rowRight: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  chevron: {
    fontSize: 22,
    color: colors.textTertiary,
    marginLeft: 8,
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 12,
  },
  badgeDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    marginRight: 6,
  },
  badgeText: {
    fontSize: 13,
    fontWeight: '500',
  },
  urlRow: {
    paddingVertical: 14,
    paddingHorizontal: 16,
  },
  urlInputContainer: {
    marginTop: 10,
  },
  urlInput: {
    backgroundColor: colors.surfaceElevated,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
    color: colors.textPrimary,
    borderWidth: 1,
    borderColor: colors.border,
  },
  urlButtons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    marginTop: 10,
    gap: 12,
  },
  urlButton: {
    paddingVertical: 8,
    paddingHorizontal: 16,
  },
  urlButtonTextCancel: {
    fontSize: 15,
    color: colors.textSecondary,
  },
  urlButtonTextSave: {
    fontSize: 15,
    color: colors.success,
    fontWeight: '600',
  },
  urlValueContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 8,
    backgroundColor: colors.surfaceElevated,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  urlValue: {
    fontSize: 15,
    color: colors.textSecondary,
    flex: 1,
    marginRight: 12,
  },
  editText: {
    fontSize: 15,
    color: colors.textTertiary,
  },
  infoSection: {
    paddingHorizontal: 20,
    paddingTop: 20,
    alignItems: 'center',
  },
  infoText: {
    fontSize: 13,
    color: colors.textTertiary,
    textAlign: 'center',
    lineHeight: 18,
    paddingHorizontal: 20,
  },
  versionText: {
    fontSize: 13,
    color: colors.textTertiary,
    marginTop: 16,
  },
  durationPicker: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 8,
  },
  durationButton: {
    flex: 1,
    paddingVertical: 10,
    paddingHorizontal: 8,
    borderRadius: 8,
    backgroundColor: colors.surfaceElevated,
    alignItems: 'center',
    justifyContent: 'center',
  },
  durationButtonActive: {
    backgroundColor: colors.success + '30',
    borderWidth: 1,
    borderColor: colors.success,
  },
  durationButtonText: {
    fontSize: 14,
    color: colors.textSecondary,
    fontWeight: '500',
  },
  durationButtonTextActive: {
    color: colors.success,
  },
  durationRow: {
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  durationLabel: {
    fontSize: 17,
    color: colors.textPrimary,
  },
  durationDescription: {
    fontSize: 13,
    color: colors.textSecondary,
    marginTop: 2,
    marginBottom: 8,
  },
  grantButton: {
    backgroundColor: colors.success,
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  grantButtonText: {
    color: colors.textPrimary,
    fontSize: 14,
    fontWeight: '600',
  },
});

export default SettingsScreen;
