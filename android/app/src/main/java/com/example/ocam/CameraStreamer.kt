package com.example.ocam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
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
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

// Data class to hold streaming resolution/FPS settings
data class StreamConfig(
    var width: Int = 1280,
    var height: Int = 720,
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
    private val cameraId: String // Added: Specific Camera ID to open
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

        try {
            startTimestampUs = -1
            startBackgroundThread()
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // 1. Setup the MediaEncoder first so we have a Surface
            setupMediaCodec()

            // 2. Open the specific Camera ID passed in the constructor
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
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between keyframes
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec!!.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Not used for Surface input
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (outputBuffer != null) {
                        // Reset timestamp logic
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
                } catch (_: Exception) {
                    stop()
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                e.printStackTrace()
                stop()
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // Can handle config changes here if needed
            }
        }, backgroundHandler)

        mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun createCaptureSession() {
        try {
            // Get the Surface from MediaCodec to feed Camera data into
            val inputSurface = mediaCodec!!.createInputSurface()
            mediaCodec!!.start()

            cachedBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            cachedBuilder!!.addTarget(inputSurface)

            // Apply initial manual controls
            applyManualsToBuilder(cachedBuilder!!)

            cameraDevice!!.createCaptureSession(listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
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
        // Set FPS
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))

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
            val bytes = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(bytes)
            outputStream.write(bytes)
            outputStream.flush()
        } catch (_: Exception) {
            // Connection lost
            stop()
        }
    }
}