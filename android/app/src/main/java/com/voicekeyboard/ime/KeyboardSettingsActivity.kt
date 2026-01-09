package com.voicekeyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.voicekeyboard.shared.KeyboardStateManager

/**
 * Settings activity for Voice Keyboard.
 * Handles permission requests and keyboard configuration.
 */
class KeyboardSettingsActivity : ComponentActivity() {
    
    private lateinit var stateManager: KeyboardStateManager
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result will be reflected in UI through recomposition
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        stateManager = KeyboardStateManager.getInstance(this)
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                KeyboardSettingsScreen(
                    stateManager = stateManager,
                    onRequestMicPermission = { requestMicrophonePermission() },
                    onOpenInputMethodSettings = { openInputMethodSettings() },
                    onOpenInputMethodPicker = { openInputMethodPicker() },
                    onBack = { finish() }
                )
            }
        }
    }
    
    private fun requestMicrophonePermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    
    private fun openInputMethodSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }
    
    private fun openInputMethodPicker() {
        val imeManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imeManager.showInputMethodPicker()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(
    stateManager: KeyboardStateManager,
    onRequestMicPermission: () -> Unit,
    onOpenInputMethodSettings: () -> Unit,
    onOpenInputMethodPicker: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    // Check permission status
    val hasMicPermission = remember {
        mutableStateOf(
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Check if keyboard is enabled
    val isKeyboardEnabled = remember {
        mutableStateOf(isKeyboardEnabled(context))
    }
    
    // Settings state
    var apiUrl by remember { mutableStateOf(stateManager.apiUrl) }
    var hapticFeedback by remember { mutableStateOf(stateManager.hapticFeedback) }
    var soundFeedback by remember { mutableStateOf(stateManager.soundFeedback) }
    var autoSend by remember { mutableStateOf(stateManager.autoSend) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Keyboard Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1C1C1E)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Setup Section
            Text(
                text = "Setup",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0A84FF)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            // Microphone Permission
            SettingsCard(
                icon = Icons.Default.Mic,
                title = "Microphone Permission",
                description = if (hasMicPermission.value) "Granted" else "Required for voice input",
                isEnabled = hasMicPermission.value,
                actionButton = if (!hasMicPermission.value) {
                    {
                        Button(onClick = onRequestMicPermission) {
                            Text("Grant")
                        }
                    }
                } else null
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Enable Keyboard
            SettingsCard(
                icon = Icons.Default.Keyboard,
                title = "Enable Keyboard",
                description = if (isKeyboardEnabled.value) "Enabled in system settings" else "Enable in system settings",
                isEnabled = isKeyboardEnabled.value,
                actionButton = {
                    Button(onClick = onOpenInputMethodSettings) {
                        Text(if (isKeyboardEnabled.value) "Settings" else "Enable")
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Select Keyboard
            SettingsCard(
                icon = Icons.Default.SwapHoriz,
                title = "Select Keyboard",
                description = "Switch to Voice Keyboard",
                isEnabled = true,
                actionButton = {
                    Button(onClick = onOpenInputMethodPicker) {
                        Text("Select")
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Server Settings
            Text(
                text = "Server Settings",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0A84FF)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "API URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { 
                            apiUrl = it
                            stateManager.apiUrl = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        placeholder = { Text("http://192.168.1.x:3002") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0A84FF),
                            unfocusedBorderColor = Color(0xFF3A3A3C)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Use your computer's IP address if testing locally",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Preferences
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0A84FF)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
            ) {
                Column {
                    SettingsSwitch(
                        title = "Haptic Feedback",
                        description = "Vibrate on key press",
                        checked = hapticFeedback,
                        onCheckedChange = {
                            hapticFeedback = it
                            stateManager.hapticFeedback = it
                        }
                    )
                    HorizontalDivider(color = Color(0xFF3A3A3C))
                    SettingsSwitch(
                        title = "Sound Feedback",
                        description = "Play sound on key press",
                        checked = soundFeedback,
                        onCheckedChange = {
                            soundFeedback = it
                            stateManager.soundFeedback = it
                        }
                    )
                    HorizontalDivider(color = Color(0xFF3A3A3C))
                    SettingsSwitch(
                        title = "Auto Send",
                        description = "Automatically send after transcription",
                        checked = autoSend,
                        onCheckedChange = {
                            autoSend = it
                            stateManager.autoSend = it
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Voice Keyboard uses Wispr Flow for speech-to-text transcription. " +
                               "Make sure the backend server is running on your network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    icon: ImageVector,
    title: String,
    description: String,
    isEnabled: Boolean,
    actionButton: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isEnabled) Color(0xFF30D158) else Color(0xFFFF453A),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            actionButton?.invoke()
        }
    }
}

@Composable
fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF30D158),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF3A3A3C)
            )
        )
    }
}

private fun isKeyboardEnabled(context: android.content.Context): Boolean {
    val packageName = context.packageName
    val enabledInputMethods = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_INPUT_METHODS
    ) ?: return false
    
    return enabledInputMethods.contains(packageName)
}
