package com.example.qnnwhistle

import android.content.Context
import android.net.Uri
import java.io.InputStream

object WavReader {
    data class WavData(val samples: ShortArray, val sampleRate: Int)

    fun read(uri: Uri, context: Context): WavData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseWav(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun readFromPath(filePath: String): WavData? {
        return try {
            java.io.File(filePath).inputStream().use { stream ->
                parseWav(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseWav(stream: InputStream): WavData? {
        val bytes = stream.readBytes()
        if (bytes.size < 44) return null

        val riff = String(bytes, 0, 4)
        if (riff != "RIFF") return null
        val wave = String(bytes, 8, 4)
        if (wave != "WAVE") return null

        var offset = 12
        var sampleRate    = 0
        var numChannels   = 0
        var bitsPerSample = 0
        var dataOffset    = 0
        var dataSize      = 0

        while (offset < bytes.size - 8) {
            val chunkId   = String(bytes, offset, 4)
            val chunkSize = readInt32LE(bytes, offset + 4)
            offset += 8
            when (chunkId) {
                "fmt " -> {
                    numChannels   = readInt16LE(bytes, offset + 2)
                    sampleRate    = readInt32LE(bytes, offset + 4)
                    bitsPerSample = readInt16LE(bytes, offset + 14)
                }
                "data" -> {
                    dataOffset = offset
                    dataSize   = chunkSize
                    break
                }
            }
            offset += chunkSize
        }

        if (dataOffset == 0 || bitsPerSample != 16) return null

        val numFrames = dataSize / (numChannels * 2)
        val mono = ShortArray(numFrames)
        for (i in 0 until numFrames) {
            var sum = 0
            for (ch in 0 until numChannels) {
                val idx = dataOffset + (i * numChannels + ch) * 2
                sum += readInt16LE(bytes, idx)
            }
            mono[i] = (sum / numChannels).toShort()
        }
        return WavData(mono, sampleRate)
    }

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readInt16LE(b: ByteArray, off: Int): Int {
        val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        return if (v >= 32768) v - 65536 else v
    }
}
