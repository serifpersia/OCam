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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
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

// --- Custom Matte Palette ---
val MatteBackground = Color(0xFF0D0D0F)
val MatteSurface = Color(0xFF161619)
val MatteAccent = Color(0xFF915FF2) // Vibrant Purple
val MatteSuccess = Color(0xFF00C896) // Emerald
val MatteError = Color(0xFFF25F5F)
val MatteTextPrimary = Color(0xFFF5F5F7)
val MatteTextSecondary = Color(0xFF8E8E93)

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            OCamTheme {
                MainScreen()
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
            .background(MatteBackground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- HEADER ---
        Text(
            text = "OCam",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MatteAccent,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = "Stream Client",
            color = MatteTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- STATUS CARD ---
        SettingsCard(title = "Connection Status", icon = Icons.Filled.NetworkCheck) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isStreaming) MatteSuccess else MatteTextSecondary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "Broadcasting" else "Standby",
                    color = MatteTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = statusText,
                color = MatteTextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (!hasPermissions) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MatteError.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, MatteError.copy(alpha = 0.3f))
            ) {
                Text(
                    "Permissions required to access camera/mic.",
                    color = MatteError,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
            return@Column
        }

        // --- VIDEO SECTION ---
        SettingsCard(title = "Video Source", icon = Icons.Filled.Videocam) {
            DropdownDeviceSelector(
                label = "Camera",
                options = cameraOptions,
                selected = selectedCamera,
                onSelected = { selectedCamera = it },
                enabled = !isStreaming
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- AUDIO SECTION ---
        SettingsCard(title = "Audio Controls", icon = Icons.Filled.Mic) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Audio Feed", color = MatteTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Capture microphone", color = MatteTextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = isAudioEnabled,
                    onCheckedChange = { if (!isStreaming) isAudioEnabled = it },
                    enabled = !isStreaming,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MatteAccent,
                        checkedTrackColor = MatteAccent.copy(alpha = 0.4f),
                        uncheckedThumbColor = MatteTextSecondary,
                        uncheckedTrackColor = MatteSurface
                    )
                )
            }

            if (isAudioEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                
                DropdownDeviceSelector(
                    label = "Input Device",
                    options = micOptions,
                    selected = selectedMic,
                    onSelected = { selectedMic = it },
                    enabled = !isStreaming
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MatteTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Gain: ${(volume * 100).toInt()}%", color = MatteTextSecondary, fontSize = 13.sp)
                }
                
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(
                        thumbColor = MatteAccent,
                        activeTrackColor = MatteAccent,
                        inactiveTrackColor = MatteSurface
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (isStreaming) {
                    isStreaming = false
                    statusText = "Stopping session..."
                    streamJob?.cancel()
                    activeAudioStreamer = null
                } else {
                    if (selectedCamera == null) return@Button
                    isStreaming = true
                    statusText = "Initiating connection..."
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
                            statusText = "Connection closed"
                            activeAudioStreamer = null
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) MatteError else MatteAccent,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = if (isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (isStreaming) "END BROADCAST" else "START BROADCAST",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

// --- REFINED UI COMPONENTS ---

@Composable
fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MatteSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MatteAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    color = MatteTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
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
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.name ?: "Select source",
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = MatteTextSecondary) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MatteTextPrimary,
                unfocusedTextColor = MatteTextPrimary,
                disabledTextColor = MatteTextSecondary,
                focusedBorderColor = MatteAccent,
                unfocusedBorderColor = MatteSurface,
                focusedLabelColor = MatteAccent,
                unfocusedLabelColor = MatteTextSecondary,
                focusedContainerColor = MatteBackground.copy(alpha = 0.5f),
                unfocusedContainerColor = MatteBackground.copy(alpha = 0.5f)
            ),
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
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
                    text = { Text(option.name, color = MatteTextPrimary) },
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
        outputStream.writeInt(640)
        outputStream.writeInt(480)
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
            videoStreamer = CameraStreamer(context, videoSocket, cameraId).apply {
                onConfigChanged = { conf ->
                    val mbps = conf.bitrate / 1_000_000f
                    onStatusUpdate("Live: ${conf.width}x${conf.height} @ ${conf.fps} FPS (%.1f Mbps)".format(mbps))
                }
            }
            videoStreamer?.start()

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