package com.example.ocam

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.example.ocam.ui.theme.OCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.Socket

class MainActivity : ComponentActivity() {
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

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Initializing...") }
    var hasPermission by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasPermission = isGranted
            if (!isGranted) statusText = "Camera Permission Required"
        }
    )

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runNetworkClient(context) { newStatus ->
                    statusText = newStatus
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(text = statusText, color = Color.White, fontSize = 16.sp)
    }
}

suspend fun runNetworkClient(context: Context, onStatusUpdate: (String) -> Unit) {
    val HOST = "127.0.0.1"
    val VIDEO_PORT = 27183
    var isRunning = true

    while (isRunning) {
        var streamer: CameraStreamer? = null
        var controlServer: ControlServer? = null
        var socket: Socket? = null

        try {
            onStatusUpdate("Connecting to PC ($VIDEO_PORT)...")
            socket = Socket(HOST, VIDEO_PORT)
            val outputStream = DataOutputStream(socket.getOutputStream())

            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val nameBytes = ByteArray(64)
            val nameRaw = deviceName.toByteArray()
            System.arraycopy(nameRaw, 0, nameBytes, 0, minOf(nameRaw.size, 64))
            outputStream.write(nameBytes)

            outputStream.writeInt(0x68323634)
            outputStream.writeInt(1920)
            outputStream.writeInt(1080)
            outputStream.flush()

            onStatusUpdate("Handshake OK. Starting Systems...")

            withContext(Dispatchers.Main) {
                streamer = CameraStreamer(context, socket)
                streamer?.start()

                controlServer = ControlServer(context, streamer!!)
                controlServer?.start()
            }

            onStatusUpdate("LIVE STREAMING\n$deviceName")

            while (socket.isConnected && !socket.isOutputShutdown) {
                delay(1000)
            }

        } catch (e: Exception) {
            onStatusUpdate("Disconnected: ${e.message}\nRetrying...")
        } finally {
            try {
                controlServer?.stop()
                streamer?.stop()
                socket?.close()
            } catch (e: Exception) { }
            delay(3000)
        }
    }
}