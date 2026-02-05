package com.example.ocam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

class AudioStreamer(
    socket: Socket,
    private val context: Context,
    private val micId: Int
) {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null

    @Volatile
    private var isStreaming = false

    // Volatile to allow real-time updates from UI thread
    @Volatile
    var volume: Float = 1.0f

    private val outputStream = DataOutputStream(socket.getOutputStream())
    private var streamingThread: Thread? = null

    // Audio Configuration: 48kHz, Stereo, AAC-LC
    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bitrate = 128000 // 128kbps

    // Optimization: Reusable buffers
    private val rawBuffer = ByteArray(4096)
    private var processedBuffer = ByteArray(4096)
    private var sendBuffer = ByteArray(4096)
    private var shortBuffer = ShortArray(2048)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isStreaming) return
        isStreaming = true

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, // Changed to MIC generally, preferred device set below
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBufferSize * 2, 4096)
            )

            // Set specific Microphone if selected
            if (micId != -1) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val targetDevice = devices.find { it.id == micId }
                if (targetDevice != null) {
                    audioRecord?.preferredDevice = targetDevice
                }
            }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec!!.start()
            audioRecord!!.startRecording()

            streamingThread = Thread {
                val bufferInfo = MediaCodec.BufferInfo()
                
                try {
                    while (isStreaming) {
                        // 1. Read Raw Audio
                        val readBytes = audioRecord?.read(rawBuffer, 0, rawBuffer.size) ?: 0

                        if (readBytes > 0) {
                            // 1.5 Apply Volume Gain (PCM manipulation)
                            val inputBuffer = if (volume != 1.0f) {
                                applyGain(rawBuffer, readBytes, volume)
                                processedBuffer
                            } else {
                                rawBuffer
                            }

                            // 2. Queue Input to Encoder
                            val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                            if (inputIndex >= 0) {
                                val codecBuffer = mediaCodec?.getInputBuffer(inputIndex)
                                codecBuffer?.clear()
                                codecBuffer?.put(inputBuffer, 0, readBytes)
                                val pts = System.nanoTime() / 1000
                                mediaCodec?.queueInputBuffer(inputIndex, 0, readBytes, pts, 0)
                            }
                        }

                        // 3. Get Encoded Output
                        var outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                        while (outputIndex >= 0) {
                            val outputBuffer = mediaCodec?.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                sendPacket(outputBuffer, bufferInfo)
                            }
                            mediaCodec?.releaseOutputBuffer(outputIndex, false)
                            outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    closeInternal()
                }
            }
            streamingThread?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    private fun applyGain(audioData: ByteArray, size: Int, gain: Float) {
        val shortCount = size / 2
        if (shortBuffer.size < shortCount) shortBuffer = ShortArray(shortCount + 1024)
        if (processedBuffer.size < size) processedBuffer = ByteArray(size + 1024)

        // bytes to shorts (Little Endian)
        for (i in 0 until shortCount) {
            val byte1 = audioData[i * 2].toInt() and 0xFF
            val byte2 = audioData[i * 2 + 1].toInt() and 0xFF
            shortBuffer[i] = ((byte2 shl 8) or byte1).toShort()
        }

        // apply gain & shorts to bytes
        for (i in 0 until shortCount) {
            var sample = (shortBuffer[i] * gain).toInt()
            // Clamp to 16-bit range
            if (sample > 32767) sample = 32767
            if (sample < -32768) sample = -32768
            
            processedBuffer[i * 2] = (sample and 0xFF).toByte()
            processedBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
    }

    private fun sendPacket(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            outputStream.writeLong(info.presentationTimeUs)
            outputStream.writeInt(info.size)
            
            if (sendBuffer.size < info.size) sendBuffer = ByteArray(info.size + 1024)
            
            buffer.position(info.offset)
            buffer.get(sendBuffer, 0, info.size)
            outputStream.write(sendBuffer, 0, info.size)
            outputStream.flush()
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        if (!isStreaming) return
        isStreaming = false
        try {
            streamingThread?.join(1000)
        } catch (_: Exception) { }
    }

    private fun closeInternal() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) { }
    }
}