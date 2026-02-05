package com.example.ocam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.math.absoluteValue

// Data class to hold streaming resolution/FPS settings
data class StreamConfig(
    var width: Int = 640,
    var height: Int = 480,
    var fps: Int = 30,
    var bitrate: Int = 1_000_000
)

// Data class to hold manual camera settings
data class ManualControls(
    var iso: Int = 0,             // 0 = Auto
    var exposureUs: Long = 0,     // 0 = Auto
    var focusDistance: Float = -1f, // -1 = Auto Focus
    var flashOn: Boolean = false
)

class CameraStreamer(
    private val context: Context,
    socket: Socket,
    val cameraId: String,
    var onConfigChanged: ((StreamConfig) -> Unit)? = null
) {
    private var cameraDevice: CameraDevice? = null
    private var mediaCodec: MediaCodec? = null
    private var captureSession: CameraCaptureSession? = null
    private var cachedBuilder: CaptureRequest.Builder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val outputStream = DataOutputStream(socket.getOutputStream())
    private var codecSurface: android.view.Surface? = null

    private var startTimestampUs: Long = -1

    var config = StreamConfig()
    var manual = ManualControls()
    private var isStreaming = false
    
    // Optimization: Reusable buffer
    private var sendBuffer = ByteArray(65536)

    private fun validateAndClampConfig() {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()

            // 1. Resolution Check
            val requestedSize = Size(config.width, config.height)
            if (!sizes.contains(requestedSize)) {
                // Find "Best Fit" - largest supported size that doesn't exceed requested area
                val bestSize = sizes
                    .filter { it.width <= config.width && it.height <= config.height }
                    .maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { (it.width - config.width).absoluteValue + (it.height - config.height).absoluteValue }
                    ?: Size(640, 480)
                
                config.width = bestSize.width
                config.height = bestSize.height
            }
        } catch (e: Exception) {
            android.util.Log.e("OCam", "Validation failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isStreaming) return
        try {
            validateAndClampConfig()
            onConfigChanged?.invoke(config) // Notify UI of potentially clamped Res/FPS
            
            startTimestampUs = -1
            startBackgroundThread()
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            setupMediaCodec()

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    stop()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    stop()
                }
            }, backgroundHandler)

            isStreaming = true
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    fun stop() {
        if (!isStreaming) return
        isStreaming = false
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            stopBackgroundThread()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Called by ControlServer to change resolution/bitrate
    fun updateConfig(newConfig: StreamConfig) {
        if (config == newConfig) return
        stop()
        config = newConfig
        onConfigChanged?.invoke(config)
        try {
            start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Called by ControlServer to change ISO/Focus/etc on the fly
    fun updateControls(newManual: ManualControls) {
        manual = newManual
        refreshSession()
    }

    // Called by ControlServer to request an I-Frame
    fun forceKeyframe() {
        try {
            val bundle = android.os.Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mediaCodec?.setParameters(bundle)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupMediaCodec() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // Set VBR mode for better stability on older Qualcomm chips
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec!!.setCallback(object : MediaCodec.Callback() {
            // ... callback stays same ...
            override fun onInputBufferAvailable(codec: MediaCodec, id: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.presentationTimeUs = 0
                        } else {
                            if (startTimestampUs < 0) startTimestampUs = info.presentationTimeUs
                            info.presentationTimeUs -= startTimestampUs
                            if (info.presentationTimeUs < 0) info.presentationTimeUs = 0
                        }
                        sendFrame(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) { stop() }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { stop() }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, backgroundHandler)

        try {
            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            android.util.Log.w("OCam", "MediaCodec config failed, falling back to 30fps safe mode: ${e.message}")
            // Catch-all fallback for very old/buggy encoders
            config.fps = 30
            config.bitrate = 1_000_000
            onConfigChanged?.invoke(config) // Sync back to UI

            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codecSurface = mediaCodec!!.createInputSurface()
    }

    private fun createCaptureSession() {
        try {
            val surface = codecSurface ?: return
            mediaCodec!!.start()

            cachedBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            cachedBuilder!!.addTarget(surface)

            // Apply initial manual controls
            applyManualsToBuilder(cachedBuilder!!)

            cameraDevice!!.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(cachedBuilder!!.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    stop()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    private fun refreshSession() {
        if (captureSession == null || cachedBuilder == null) return
        try {
            applyManualsToBuilder(cachedBuilder!!)
            captureSession!!.setRepeatingRequest(cachedBuilder!!.build(), null, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyManualsToBuilder(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))
        
        if (manual.iso <= 0 && manual.exposureUs <= 0) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }

        // Set Exposure / ISO
        if (manual.iso > 0 || manual.exposureUs > 0) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            if (manual.iso > 0) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manual.iso)
            }
            if (manual.exposureUs > 0) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manual.exposureUs * 1000) // convert us to ns
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        // Set Focus
        if (manual.focusDistance >= 0) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manual.focusDistance)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // Set Flash
        builder.set(CaptureRequest.FLASH_MODE, if (manual.flashOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
    }

    private fun sendFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            // Write PTS (8 bytes)
            outputStream.writeLong(info.presentationTimeUs)
            // Write Size (4 bytes)
            outputStream.writeInt(info.size)

            // Write Data
            if (sendBuffer.size < info.size) {
                 sendBuffer = ByteArray(info.size + 1024)
            }
            
            buffer.position(info.offset)
            buffer.get(sendBuffer, 0, info.size)
            outputStream.write(sendBuffer, 0, info.size)
            outputStream.flush()
        } catch (_: Exception) {
            // Connection lost
            stop()
        }
    }
}