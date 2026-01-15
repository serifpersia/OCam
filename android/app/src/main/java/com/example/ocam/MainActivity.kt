package com.example.ocam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ocam.ui.theme.OCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.Socket

// Helper data class for Dropdowns
data class DeviceOption(val id: String, val name: String, val intId: Int = -1)

// --- Custom Matte Colors ---
val MatteBackground = Color(0xFF121212)
val MatteSurface = Color(0xFF1E1E1E)
val MatteTeal = Color(0xFF00796B)
val MatteRed = Color(0xFFD32F2F)
val TextPrimary = Color(0xFFE0E0E0)
val TextSecondary = Color(0xFFA0A0A0)

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            OCamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MatteBackground
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // --- State ---
    var statusText by remember { mutableStateOf("Ready to Stream") }
    var isStreaming by remember { mutableStateOf(false) }

    // Settings
    var isAudioEnabled by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(1.0f) }

    // Lists & Selections
    var cameraOptions by remember { mutableStateOf(listOf<DeviceOption>()) }
    var micOptions by remember { mutableStateOf(listOf<DeviceOption>()) }
    var selectedCamera by remember { mutableStateOf<DeviceOption?>(null) }
    var selectedMic by remember { mutableStateOf<DeviceOption?>(null) }

    // Logic References
    var streamJob by remember { mutableStateOf<Job?>(null) }
    var activeAudioStreamer by remember { mutableStateOf<AudioStreamer?>(null) }

    // Permissions
    var hasPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            if (perms.values.all { it }) {
                hasPermissions = true
                cameraOptions = getCameraList(context)
                micOptions = getMicList(context)
                if (cameraOptions.isNotEmpty()) selectedCamera = cameraOptions[0]
                if (micOptions.isNotEmpty()) selectedMic = micOptions[0]
            }
        }
    )

    // Init Permissions & Lists
    LaunchedEffect(Unit) {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            hasPermissions = true
            cameraOptions = getCameraList(context)
            micOptions = getMicList(context)
            if (cameraOptions.isNotEmpty()) selectedCamera = cameraOptions[0]
            if (micOptions.isNotEmpty()) selectedMic = micOptions[0]
        } else {
            permissionLauncher.launch(perms)
        }
    }

    // Real-time Volume Update
    LaunchedEffect(volume) {
        activeAudioStreamer?.volume = volume
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Header ---
        Text(
            text = "OCam",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        Text(
            text = "TCP Stream Client",
            color = TextSecondary,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (!hasPermissions) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1B1B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Camera & Audio permissions required.",
                    color = Color(0xFFFF8A80),
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        // --- Video Section ---
        SettingsCard(title = "Video Settings") {
            DropdownDeviceSelector(
                label = "Camera Source",
                options = cameraOptions,
                selected = selectedCamera,
                onSelected = { selectedCamera = it },
                enabled = !isStreaming
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Audio Section ---
        SettingsCard(title = "Audio Settings") {
            // Audio Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Send Audio", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Enable microphone stream", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = isAudioEnabled,
                    onCheckedChange = { if (!isStreaming) isAudioEnabled = it },
                    enabled = !isStreaming,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MatteTeal,
                        checkedTrackColor = MatteTeal.copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Microphone Selection (Visible only if Audio enabled)
            val audioControlsAlpha = if (isAudioEnabled) 1f else 0.4f

            Column(modifier = Modifier.alpha(audioControlsAlpha)) {
                DropdownDeviceSelector(
                    label = "Microphone Source",
                    options = micOptions,
                    selected = selectedMic,
                    onSelected = { selectedMic = it },
                    enabled = !isStreaming && isAudioEnabled
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Volume Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gain: ${(volume * 100).toInt()}%", color = TextSecondary, fontSize = 14.sp)
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0f..5f,
                    steps = 49,
                    enabled = isAudioEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = MatteTeal,
                        activeTrackColor = MatteTeal,
                        inactiveTrackColor = Color.Gray
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Action Button ---
        Button(
            onClick = {
                if (isStreaming) {
                    // STOP
                    isStreaming = false
                    statusText = "Stopping..."
                    streamJob?.cancel()
                    activeAudioStreamer = null
                } else {
                    // START
                    if (selectedCamera == null) return@Button
                    isStreaming = true
                    statusText = "Connecting..."
                    streamJob = scope.launch(Dispatchers.IO) {
                        runTCPStreamingSession(
                            context = context,
                            ip = "127.0.0.1",
                            cameraId = selectedCamera!!.id,
                            micId = selectedMic?.intId ?: -1,
                            sendAudio = isAudioEnabled,
                            initialVolume = volume,
                            onAudioStreamerReady = { streamer -> activeAudioStreamer = streamer },
                            onStatusUpdate = { msg -> statusText = msg }
                        )
                        withContext(Dispatchers.Main) {
                            isStreaming = false
                            statusText = "Disconnected / Ready"
                            activeAudioStreamer = null
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) MatteRed else MatteTeal,
                contentColor = Color.White
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = if (isStreaming) "STOP STREAM" else "START STREAM",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status Text
        Box(
            modifier = Modifier
                .background(Color(0xFF252525), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text = statusText, color = Color(0xFFFFD54F), fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

// --- UI Components ---

@Composable
fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MatteSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownDeviceSelector(
    label: String,
    options: List<DeviceOption>,
    selected: DeviceOption?,
    onSelected: (DeviceOption) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) !expanded }
    ) {
        OutlinedTextField(
            value = selected?.name ?: "Select Device",
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = TextSecondary) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                disabledTextColor = TextSecondary,
                focusedBorderColor = MatteTeal,
                unfocusedBorderColor = Color.Gray,
                focusedLabelColor = MatteTeal,
                unfocusedLabelColor = TextSecondary
            ),
            enabled = enabled,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MatteSurface)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name, color = TextPrimary) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

// --- Modifiers ---
fun Modifier.alpha(alpha: Float) = this.then(Modifier.graphicsLayer(alpha = alpha))

// --- Logic ---

suspend fun runTCPStreamingSession(
    context: Context,
    ip: String,
    cameraId: String,
    micId: Int,
    sendAudio: Boolean,
    initialVolume: Float,
    onAudioStreamerReady: (AudioStreamer) -> Unit,
    onStatusUpdate: (String) -> Unit
) {
    val videoPort = 27183
    val audioPort = 27185

    var videoStreamer: CameraStreamer? = null
    var audioStreamer: AudioStreamer? = null
    var controlServer: ControlServer? = null
    var videoSocket: Socket? = null
    var audioSocket: Socket? = null

    try {
        onStatusUpdate("Connecting Video...")

        // 1. Video Connection (Always Active)
        videoSocket = Socket(ip, videoPort)
        val outputStream = DataOutputStream(videoSocket.getOutputStream())

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val nameBytes = ByteArray(64)
        System.arraycopy(deviceName.toByteArray(), 0, nameBytes, 0, minOf(deviceName.length, 64))
        outputStream.write(nameBytes)
        outputStream.writeInt(0x68323634) // H264
        outputStream.writeInt(1280)
        outputStream.writeInt(720)
        outputStream.flush()

        // 2. Audio Connection (Only if Toggle is ON)
        if (sendAudio) {
            onStatusUpdate("Connecting Audio...")
            try {
                audioSocket = Socket(ip, audioPort)
                val audioOut = DataOutputStream(audioSocket.getOutputStream())
                audioOut.writeInt(0x41414320) // AAC
                audioOut.flush()
            } catch (_: Exception) {
                onStatusUpdate("Audio Failed (Video Only)")
            }
        }

        onStatusUpdate("Live Streaming")

        withContext(Dispatchers.Main) {
            // Start Camera
            videoStreamer = CameraStreamer(context, videoSocket, cameraId)
            videoStreamer.start()

            // Start Audio if socket exists and is connected
            if (audioSocket != null && audioSocket.isConnected) {
                AudioStreamer(audioSocket, context, micId).also { audioStreamer = it }
                audioStreamer?.volume = initialVolume
                audioStreamer?.start()
                onAudioStreamerReady(audioStreamer!!)
            }

            // Start Controls
            controlServer = ControlServer(context, videoStreamer)
            controlServer.start()
        }

        // Keep Alive Loop
        while (videoSocket.isConnected && !videoSocket.isOutputShutdown) {
            delay(1000)
        }

    } catch (e: Exception) {
        onStatusUpdate("Error: ${e.message}")
    } finally {
        try {
            controlServer?.stop()
            audioStreamer?.stop()
            videoStreamer?.stop()
            videoSocket?.close()
            audioSocket?.close()
        } catch (_: Exception) { }
    }
}

// --- Device Lookups ---

fun getCameraList(context: Context): List<DeviceOption> {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val options = mutableListOf<DeviceOption>()
    try {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val desc = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Back"
            options.add(DeviceOption(id, "Camera $id ($desc)"))
        }
    } catch (e: Exception) { e.printStackTrace() }
    return options
}

@RequiresApi(Build.VERSION_CODES.P)
fun getMicList(context: Context): List<DeviceOption> {
    val options = mutableListOf<DeviceOption>()
    options.add(DeviceOption("default", "Default Microphone", -1))
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    for (device in devices) {
        val name = "${device.productName} (${device.id})"
        options.add(DeviceOption(device.address, name, device.id))
    }
    return options
}