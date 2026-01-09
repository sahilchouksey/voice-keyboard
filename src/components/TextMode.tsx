/**
 * Text Mode Component
 * QWERTY keyboard with numbers/symbols layers and voice button
 */

import React, { useState } from 'react';
import { View, Text, Pressable, StyleSheet, Dimensions } from 'react-native';

const { width: SCREEN_WIDTH } = Dimensions.get('window');

interface TextModeProps {
  onKeyPress: (char: string) => void;
  onBackspace: () => void;
  onEnter: () => void;
  onSwitchToVoice: () => void;
}

type KeyboardMode = 'letters' | 'numbers' | 'symbols';

const LETTER_ROWS = [
  ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
  ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
  ['⇧', 'z', 'x', 'c', 'v', 'b', 'n', 'm', '⌫'],
];

const NUMBER_ROWS = [
  ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
  ['-', '/', ':', ';', '(', ')', '$', '&', '@', '"'],
  ['#+=', '.', ',', '?', '!', "'", '⌫'],
];

const SYMBOL_ROWS = [
  ['[', ']', '{', '}', '#', '%', '^', '*', '+', '='],
  ['_', '\\', '|', '~', '<', '>', '€', '£', '¥', '•'],
  ['123', '.', ',', '?', '!', "'", '⌫'],
];

export function TextMode({ onKeyPress, onBackspace, onEnter, onSwitchToVoice }: TextModeProps) {
  const [isShift, setIsShift] = useState(false);
  const [mode, setMode] = useState<KeyboardMode>('letters');

  const getCurrentRows = () => {
    switch (mode) {
      case 'numbers':
        return NUMBER_ROWS;
      case 'symbols':
        return SYMBOL_ROWS;
      default:
        return LETTER_ROWS;
    }
  };

  const handleKey = (key: string) => {
    // Mode switching keys
    if (key === '?123' || key === '123') {
      setMode('numbers');
      return;
    }
    if (key === '#+=') {
      setMode('symbols');
      return;
    }
    if (key === 'ABC') {
      setMode('letters');
      return;
    }

    // Special keys
    if (key === '⇧') {
      setIsShift(!isShift);
      return;
    }
    if (key === '⌫') {
      onBackspace();
      return;
    }

    // Regular character
    if (mode === 'letters') {
      onKeyPress(isShift ? key.toUpperCase() : key);
      if (isShift) setIsShift(false);
    } else {
      onKeyPress(key);
    }
  };

  const keyWidth = (SCREEN_WIDTH - 24) / 10 - 4;
  const rows = getCurrentRows();

  const isSpecialKey = (key: string) => ['⇧', '⌫', '#+=', '123', '?123', 'ABC'].includes(key);

  const getKeyWidth = (key: string) => {
    if (['⇧', '⌫'].includes(key)) return keyWidth * 1.5;
    if (['#+=', '123'].includes(key)) return keyWidth * 1.5;
    return keyWidth;
  };

  return (
    <View style={styles.container}>
      {rows.map((row, ri) => (
        <View key={ri} style={styles.row}>
          {row.map((key) => (
            <Pressable
              key={key}
              onPress={() => handleKey(key)}
              style={({ pressed }) => [
                styles.key,
                { width: getKeyWidth(key) },
                isSpecialKey(key) && styles.specialKey,
                isShift && key === '⇧' && styles.activeKey,
                pressed && styles.keyPressed,
              ]}
            >
              <Text
                style={[
                  styles.keyText,
                  isShift && key === '⇧' && styles.activeKeyText,
                  isSpecialKey(key) && styles.specialKeyText,
                ]}
              >
                {mode === 'letters' && isShift && !isSpecialKey(key) ? key.toUpperCase() : key}
              </Text>
            </Pressable>
          ))}
        </View>
      ))}

      {/* Bottom row */}
      <View style={styles.row}>
        {/* Mode toggle button */}
        <Pressable
          style={[styles.key, styles.modeKey]}
          onPress={() => setMode(mode === 'letters' ? 'numbers' : 'letters')}
        >
          <Text style={styles.modeText}>{mode === 'letters' ? '?123' : 'ABC'}</Text>
        </Pressable>

        {/* Voice button */}
        <Pressable style={[styles.key, styles.voiceKey]} onPress={onSwitchToVoice}>
          <Text style={styles.voiceIcon}>🎤</Text>
        </Pressable>

        {/* Space */}
        <Pressable style={[styles.key, styles.spaceKey]} onPress={() => onKeyPress(' ')}>
          <Text style={styles.spaceText}>space</Text>
        </Pressable>

        {/* Period button */}
        <Pressable style={[styles.key, styles.modeKey]} onPress={() => onKeyPress('.')}>
          <Text style={styles.modeText}>.</Text>
        </Pressable>

        {/* Enter button */}
        <Pressable style={[styles.key, styles.enterKey]} onPress={onEnter}>
          <Text style={styles.enterIcon}>↵</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#2C2C2E',
    paddingVertical: 8,
    paddingHorizontal: 4,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'center',
    marginVertical: 4,
  },
  key: {
    height: 44,
    marginHorizontal: 2,
    backgroundColor: '#636366',
    borderRadius: 5,
    justifyContent: 'center',
    alignItems: 'center',
  },
  keyPressed: {
    backgroundColor: '#8E8E93',
  },
  keyText: {
    color: '#FFF',
    fontSize: 20,
  },
  specialKey: {
    backgroundColor: '#4A4A4C',
  },
  specialKeyText: {
    fontSize: 14,
  },
  activeKey: {
    backgroundColor: '#FFF',
  },
  activeKeyText: {
    color: '#000',
  },
  modeKey: {
    width: 44,
    backgroundColor: '#4A4A4C',
  },
  modeText: {
    color: '#FFF',
    fontSize: 14,
  },
  voiceKey: {
    width: 44,
    backgroundColor: '#FF3B30',
  },
  voiceIcon: {
    fontSize: 20,
  },
  spaceKey: {
    flex: 1,
    maxWidth: SCREEN_WIDTH * 0.35,
  },
  spaceText: {
    color: '#FFF',
    fontSize: 14,
  },
  enterKey: {
    width: 44,
    backgroundColor: '#007AFF',
  },
  enterIcon: {
    color: '#FFF',
    fontSize: 18,
  },
});

export default TextMode;
