package com.voicekeyboard.ime

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * QWERTY Text Keyboard UI built with Jetpack Compose
 * Adapted from compose-keyboard-ime (MIT License)
 */

// Key action types
sealed class KeyAction {
    data class CommitText(val text: String) : KeyAction()
    object Delete : KeyAction()
    object Enter : KeyAction()
    object Shift : KeyAction()
    object SwitchToSymbols : KeyAction()
    object SwitchToVoice : KeyAction()
    object Space : KeyAction()
}

// Key types for styling
enum class KeyType {
    NORMAL,      // Regular letter key
    FUNCTIONAL,  // Shift, backspace, enter, symbols
    SPECIAL,     // Mic button, space
}

// Key definition
data class KeyDef(
    val label: String? = null,
    val icon: ImageVector? = null,
    val action: KeyAction,
    val widthWeight: Float = 1f,
    val type: KeyType = KeyType.NORMAL
)

@Composable
fun TextKeyboardUI(
    navBarHeightDp: Int = 0,
    onKeyAction: (KeyAction) -> Unit,
    onSwitchToVoice: () -> Unit,
    onBackspaceLongPress: () -> Unit = {}
) {
    var isShifted by remember { mutableStateOf(false) }
    var isCapsLock by remember { mutableStateOf(false) }
    var isSymbols by remember { mutableStateOf(false) }
    var isSymbolsAlt by remember { mutableStateOf(false) } // For ?123 -> #+=
    
    val keyboardContentHeight = 260.dp
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
                .padding(horizontal = 3.dp, vertical = 6.dp)
                .padding(bottom = navBarPadding)
        ) {
            if (isSymbols) {
                SymbolsKeyboard(
                    isAlt = isSymbolsAlt,
                    onKeyAction = { action ->
                        when (action) {
                            is KeyAction.SwitchToSymbols -> {
                                isSymbolsAlt = !isSymbolsAlt
                            }
                            is KeyAction.Shift -> {
                                isSymbols = false
                            }
                            is KeyAction.SwitchToVoice -> onSwitchToVoice()
                            else -> onKeyAction(action)
                        }
                    },
                    onBackspaceLongPress = onBackspaceLongPress
                )
            } else {
                QwertyKeyboard(
                    isShifted = isShifted || isCapsLock,
                    onKeyAction = { action ->
                        when (action) {
                            is KeyAction.Shift -> {
                                if (isCapsLock) {
                                    isCapsLock = false
                                    isShifted = false
                                } else if (isShifted) {
                                    isCapsLock = true
                                } else {
                                    isShifted = true
                                }
                            }
                            is KeyAction.CommitText -> {
                                onKeyAction(action)
                                if (isShifted && !isCapsLock) {
                                    isShifted = false
                                }
                            }
                            is KeyAction.SwitchToSymbols -> {
                                isSymbols = true
                                isSymbolsAlt = false
                            }
                            is KeyAction.SwitchToVoice -> onSwitchToVoice()
                            else -> onKeyAction(action)
                        }
                    },
                    onBackspaceLongPress = onBackspaceLongPress,
                    isCapsLock = isCapsLock
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.QwertyKeyboard(
    isShifted: Boolean,
    isCapsLock: Boolean,
    onKeyAction: (KeyAction) -> Unit,
    onBackspaceLongPress: () -> Unit
) {
    val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")
    
    // Row 1
    KeyboardRow(modifier = Modifier.weight(1f)) {
        row1.forEach { char ->
            LetterKey(
                letter = if (isShifted) char.uppercase() else char,
                modifier = Modifier.weight(1f),
                onKeyAction = onKeyAction
            )
        }
    }
    
    // Row 2 (slightly indented)
    KeyboardRow(modifier = Modifier.weight(1f)) {
        Spacer(modifier = Modifier.width(16.dp))
        row2.forEach { char ->
            LetterKey(
                letter = if (isShifted) char.uppercase() else char,
                modifier = Modifier.weight(1f),
                onKeyAction = onKeyAction
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
    }
    
    // Row 3 (with shift and backspace)
    KeyboardRow(modifier = Modifier.weight(1f)) {
        // Shift key
        FunctionalKey(
            label = if (isCapsLock) "⇪" else "⇧",
            modifier = Modifier.weight(1.5f),
            isHighlighted = isShifted || isCapsLock,
            onClick = { onKeyAction(KeyAction.Shift) }
        )
        
        row3.forEach { char ->
            LetterKey(
                letter = if (isShifted) char.uppercase() else char,
                modifier = Modifier.weight(1f),
                onKeyAction = onKeyAction
            )
        }
        
        // Backspace key
        FunctionalKey(
            icon = Icons.AutoMirrored.Filled.Backspace,
            modifier = Modifier.weight(1.5f),
            onClick = { onKeyAction(KeyAction.Delete) },
            onLongPress = onBackspaceLongPress
        )
    }
    
    // Row 4 (bottom row)
    KeyboardRow(modifier = Modifier.weight(1f)) {
        // ?123 key
        FunctionalKey(
            label = "?123",
            modifier = Modifier.weight(1.2f),
            onClick = { onKeyAction(KeyAction.SwitchToSymbols) }
        )
        
        // Mic key (switch to voice mode)
        MicKey(
            modifier = Modifier.weight(1f),
            onClick = { onKeyAction(KeyAction.SwitchToVoice) }
        )
        
        // Comma
        LetterKey(
            letter = ",",
            modifier = Modifier.weight(0.8f),
            onKeyAction = onKeyAction
        )
        
        // Space bar
        SpaceKey(
            modifier = Modifier.weight(4f),
            onClick = { onKeyAction(KeyAction.Space) }
        )
        
        // Period
        LetterKey(
            letter = ".",
            modifier = Modifier.weight(0.8f),
            onKeyAction = onKeyAction
        )
        
        // Enter key
        FunctionalKey(
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            modifier = Modifier.weight(1.5f),
            backgroundColor = Color(0xFF007AFF),
            onClick = { onKeyAction(KeyAction.Enter) }
        )
    }
}

@Composable
private fun ColumnScope.SymbolsKeyboard(
    isAlt: Boolean,
    onKeyAction: (KeyAction) -> Unit,
    onBackspaceLongPress: () -> Unit
) {
    val row1 = if (isAlt) listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=")
                else listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2 = if (isAlt) listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•")
                else listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"")
    val row3 = if (isAlt) listOf(".", ",", "?", "!", "'")
                else listOf(".", ",", "?", "!", "'")
    
    // Row 1
    KeyboardRow(modifier = Modifier.weight(1f)) {
        row1.forEach { char ->
            LetterKey(
                letter = char,
                modifier = Modifier.weight(1f),
                onKeyAction = onKeyAction
            )
        }
    }
    
    // Row 2
    KeyboardRow(modifier = Modifier.weight(1f)) {
        row2.forEach { char ->
            LetterKey(
                letter = char,
                modifier = Modifier.weight(1f),
                onKeyAction = onKeyAction
            )
        }
    }
    
    // Row 3
    KeyboardRow(modifier = Modifier.weight(1f)) {
        // Switch symbol page
        FunctionalKey(
            label = if (isAlt) "123" else "#+=",
            modifier = Modifier.weight(1.5f),
            onClick = { onKeyAction(KeyAction.SwitchToSymbols) }
        )
        
        row3.forEach { char ->
            LetterKey(
                letter = char,
                modifier = Modifier.weight(1f),
                onKeyAction = onKeyAction
            )
        }
        
        // Backspace
        FunctionalKey(
            icon = Icons.AutoMirrored.Filled.Backspace,
            modifier = Modifier.weight(1.5f),
            onClick = { onKeyAction(KeyAction.Delete) },
            onLongPress = onBackspaceLongPress
        )
    }
    
    // Row 4 (bottom)
    KeyboardRow(modifier = Modifier.weight(1f)) {
        // ABC key
        FunctionalKey(
            label = "ABC",
            modifier = Modifier.weight(1.2f),
            onClick = { onKeyAction(KeyAction.Shift) } // Reuse shift to go back to ABC
        )
        
        // Mic key
        MicKey(
            modifier = Modifier.weight(1f),
            onClick = { onKeyAction(KeyAction.SwitchToVoice) }
        )
        
        // Comma
        LetterKey(
            letter = ",",
            modifier = Modifier.weight(0.8f),
            onKeyAction = onKeyAction
        )
        
        // Space
        SpaceKey(
            modifier = Modifier.weight(4f),
            onClick = { onKeyAction(KeyAction.Space) }
        )
        
        // Period
        LetterKey(
            letter = ".",
            modifier = Modifier.weight(0.8f),
            onKeyAction = onKeyAction
        )
        
        // Enter
        FunctionalKey(
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            modifier = Modifier.weight(1.5f),
            backgroundColor = Color(0xFF007AFF),
            onClick = { onKeyAction(KeyAction.Enter) }
        )
    }
}

@Composable
private fun KeyboardRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun LetterKey(
    letter: String,
    modifier: Modifier = Modifier,
    onKeyAction: (KeyAction) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(if (isPressed) Color(0xFF5A5A5C) else Color(0xFF3A3A3C))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                onKeyAction(KeyAction.CommitText(letter))
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun FunctionalKey(
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    backgroundColor: Color = Color(0xFF2C2C2E),
    isHighlighted: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    
    val actualBackgroundColor = when {
        isHighlighted -> Color(0xFF007AFF)
        isPressed -> Color(0xFF5A5A5C)
        else -> backgroundColor
    }
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(actualBackgroundColor)
            .then(
                if (onLongPress != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                longPressJob?.cancel()
                                longPressJob = scope.launch {
                                    delay(400) // Long press threshold
                                    onLongPress()
                                    // Repeat while held
                                    while (true) {
                                        delay(50)
                                        onLongPress()
                                    }
                                }
                                tryAwaitRelease()
                                longPressJob?.cancel()
                            },
                            onTap = { onClick() }
                        )
                    }
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick() }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            label != null -> Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MicKey(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(if (isPressed) Color(0xFF34C759).copy(alpha = 0.8f) else Color(0xFF34C759))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Voice Input",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SpaceKey(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(if (isPressed) Color(0xFF5A5A5C) else Color(0xFF3A3A3C))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "space",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
