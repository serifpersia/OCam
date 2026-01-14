package com.example.ocam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

data class StreamConfig(
    var width: Int = 1280,
    var height: Int = 720,
    var fps: Int = 30,
    var bitrate: Int = 1_000_000
)

data class ManualControls(
    var iso: Int = 0,
    var exposureUs: Long = 0,
    var focusDistance: Float = -1f,
    var flashOn: Boolean = false
)

class CameraStreamer(
    private val context: Context,
    private val socket: Socket
) {
    private var cameraDevice: CameraDevice? = null
    private var mediaCodec: MediaCodec? = null
    private var captureSession: CameraCaptureSession? = null
    private var cachedBuilder: CaptureRequest.Builder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val outputStream = DataOutputStream(socket.getOutputStream())

    private var startTimestampUs: Long = -1

    var config = StreamConfig()
    var manual = ManualControls()
    private var isStreaming = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (isStreaming) return
        startTimestampUs = -1
        startBackgroundThread()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            setupMediaCodec()
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)
            isStreaming = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isStreaming = false
        try {
            captureSession?.close()
            cameraDevice?.close()
            mediaCodec?.stop()
            mediaCodec?.release()
            stopBackgroundThread()
        } catch (e: Exception) { }
    }

    fun updateConfig(newConfig: StreamConfig) {
        if (config == newConfig) return
        stop()
        config = newConfig
        try { start() } catch (e: Exception) { }
    }

    fun updateControls(newManual: ManualControls) {
        manual = newManual
        refreshSession()
    }

    fun forceKeyframe() {
        try {
            val bundle = android.os.Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mediaCodec?.setParameters(bundle)
        } catch (e: Exception) { }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null } catch (e: Exception) { }
    }

    private fun setupMediaCodec() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec!!.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (outputBuffer != null) {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.presentationTimeUs = 0
                        } else {
                            if (startTimestampUs < 0) startTimestampUs = info.presentationTimeUs
                            info.presentationTimeUs -= startTimestampUs
                            if (info.presentationTimeUs < 0) info.presentationTimeUs = 0
                        }
                        sendFrame(outputBuffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    stop()
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { stop() }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, backgroundHandler)
        mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun createCaptureSession() {
        val inputSurface = mediaCodec!!.createInputSurface()
        mediaCodec!!.start()
        cachedBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        cachedBuilder!!.addTarget(inputSurface)
        applyManualsToBuilder(cachedBuilder!!)

        cameraDevice!!.createCaptureSession(listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    session.setRepeatingRequest(cachedBuilder!!.build(), null, backgroundHandler)
                } catch (e: Exception) { }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun refreshSession() {
        if (captureSession == null || cachedBuilder == null) return
        try {
            applyManualsToBuilder(cachedBuilder!!)
            captureSession!!.setRepeatingRequest(cachedBuilder!!.build(), null, backgroundHandler)
        } catch (e: Exception) { }
    }

    private fun applyManualsToBuilder(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))

        if (manual.iso > 0 || manual.exposureUs > 0) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            if (manual.iso > 0) builder.set(CaptureRequest.SENSOR_SENSITIVITY, manual.iso)
            if (manual.exposureUs > 0) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manual.exposureUs * 1000)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        if (manual.focusDistance >= 0) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manual.focusDistance)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        builder.set(CaptureRequest.FLASH_MODE, if (manual.flashOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
    }

    private fun sendFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            outputStream.writeLong(info.presentationTimeUs)
            outputStream.writeInt(info.size)
            val bytes = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(bytes)
            outputStream.write(bytes)
            outputStream.flush()
        } catch (e: Exception) {
            stop()
        }
    }
}