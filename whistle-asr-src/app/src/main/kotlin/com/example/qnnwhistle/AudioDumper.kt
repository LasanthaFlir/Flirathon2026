package com.example.qnnwhistle

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility to save audio clips as WAV files for debugging.
 * Saves to public Music/WhistleClips directory so they appear in file picker.
 */
@SuppressLint("StaticFieldLeak")
object AudioDumper {
    private const val TAG = "AudioDumper"
    private var context: Context? = null
    private var counter = 0

    fun init(ctx: Context) {
        context = ctx
    }

    /**
     * Save a 16kHz mono PCM16 array as a WAV file.
     * Saves to public Music/WhistleClips folder so it appears in file picker.
     * Returns the file path for logging.
     */
    fun savePcm(samples: ShortArray, label: String = "audio"): String? {
        val ctx = context ?: run {
            Log.w(TAG, "AudioDumper not initialized")
            return null
        }

        // Save to public Music directory so files appear in media picker
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(musicDir, "WhistleClips")
        if (!dir.exists()) dir.mkdirs()

        val timestamp = SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())
        val filename = "clip_${timestamp}_${label}_${++counter}.wav"
        val file = File(dir, filename)

        try {
            writeWavFile(file, samples, 16000, 1, 16)
            Log.i(TAG, "Saved: ${file.absolutePath} (${samples.size} samples = ${samples.size/16000f}s)")
            
            // Notify media scanner so file appears in picker immediately
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("audio/wav")) { path, uri ->
                Log.i(TAG, "Media scanned: $path -> $uri")
            }
            
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio", e)
            return null
        }
    }

    /**
     * Write PCM16 samples as a WAV file (RIFF format).
     */
    private fun writeWavFile(
        file: File,
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val subchunk2Size = samples.size * blockAlign
        val chunkSize = 36 + subchunk2Size

        file.outputStream().use { os ->
            // RIFF header
            os.write("RIFF".toByteArray())
            writeInt(os, chunkSize)
            os.write("WAVE".toByteArray())

            // fmt subchunk
            os.write("fmt ".toByteArray())
            writeInt(os, 16)  // subchunk1Size
            writeShort(os, 1)  // audioFormat (PCM)
            writeShort(os, channels.toShort())
            writeInt(os, sampleRate)
            writeInt(os, byteRate)
            writeShort(os, blockAlign.toShort())
            writeShort(os, bitsPerSample.toShort())

            // data subchunk
            os.write("data".toByteArray())
            writeInt(os, subchunk2Size)

            // PCM samples (little-endian)
            val buf = ByteBuffer.allocate(samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buf.putShort(s)
            os.write(buf.array())
        }
    }

    private fun writeInt(os: java.io.OutputStream, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        os.write(buf.array())
    }

    private fun writeShort(os: java.io.OutputStream, value: Short) {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(value)
        os.write(buf.array())
    }
}