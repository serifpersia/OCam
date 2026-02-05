package com.example.ocam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class ControlServer(
    private val context: Context,
    private val streamer: CameraStreamer
) {
    private val port = 27184
    private var running = true
    private var currentSocket: Socket? = null

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        running = true
        GlobalScope.launch(Dispatchers.IO) {
            while (running) {
                try {
                    val socket = Socket("127.0.0.1", port)
                    currentSocket = socket
                    handleConnection(socket)
                } catch (_: Exception) {
                    Thread.sleep(2000)
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            currentSocket?.close()
        } catch (_: Exception) { }
        currentSocket = null
    }

    private fun handleConnection(sock: Socket) {
        val input = DataInputStream(sock.getInputStream())
        val output = DataOutputStream(sock.getOutputStream())

        try {
            while (running && sock.isConnected) {
                val cmdId = input.readByte().toInt()
                val arg1 = input.readInt()
                val arg2 = input.readInt()

                when (cmdId) {
                    0x01 -> streamer.updateConfig(streamer.config.copy(width = arg1, height = arg2))
                    0x02 -> streamer.updateConfig(streamer.config.copy(fps = arg1))
                    0x03 -> streamer.updateConfig(streamer.config.copy(bitrate = arg1))
                    0x04 -> streamer.forceKeyframe()
                    0x05 -> sendCapabilities(output)
                    0x06 -> {
                        val m = streamer.manual
                        m.iso = arg1
                        streamer.updateControls(m)
                    }
                    0x07 -> {
                        val m = streamer.manual
                        m.exposureUs = arg1.toLong()
                        streamer.updateControls(m)
                    }
                    0x08 -> {
                        val m = streamer.manual
                        m.focusDistance = if (arg1 < 0) -1f else (arg1 / 1000f) * 10f
                        streamer.updateControls(m)
                    }
                    0x09 -> {
                        val m = streamer.manual
                        m.flashOn = (arg1 == 1)
                        streamer.updateControls(m)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun sendCapabilities(output: DataOutputStream) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(manager.cameraIdList[0])
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val flashAvail = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        val resPayloadSize = 1 + (sizes.size * 8)
        val extraPayloadSize = 4+4 + 4+4 + 4 + 1
        val totalSize = resPayloadSize + extraPayloadSize

        output.writeByte(0x10)
        output.writeInt(totalSize)

        output.writeByte(sizes.size)
        for (size in sizes) {
            output.writeInt(size.width)
            output.writeInt(size.height)
        }

        output.writeInt(isoRange?.lower ?: 0)
        output.writeInt(isoRange?.upper ?: 0)
        output.writeInt((expRange?.lower ?: 0).toInt() / 1000)
        output.writeInt((expRange?.upper ?: 0).toInt() / 1000)
        output.writeFloat(minFocus)
        output.writeByte(if (flashAvail) 1 else 0)

        output.flush()
    }
}