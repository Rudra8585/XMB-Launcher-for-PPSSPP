package com.rudra.xmb.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes the RIFF-wrapped ATRAC3 / ATRAC3+ found in a game's SND0.AT3. */
class AtracDecoder private constructor(
    private val handle: Long,
    val channels: Int,
    val sampleRate: Int,
    val blockAlign: Int,
    private val data: ByteArray,
    private val dataStart: Int,
    private val dataEnd: Int,
) {
    private var pos = dataStart
    private val pcm = ShortArray(4096 * 2)

    /** Decodes the next frame into [pcmOut]-ready samples, looping at the end. Returns samples per channel. */
    fun next(): Pair<ShortArray, Int> {
        var tries = 0
        while (tries++ < 8) {
            if (pos + blockAlign > dataEnd) pos = dataStart
            val n = nativeDecode(handle, data, pos, blockAlign, pcm)
            pos += blockAlign
            if (n > 0) return pcm to n
        }
        return pcm to 0
    }

    fun close() = nativeClose(handle)

    companion object {
        init {
            System.loadLibrary("xmbnative")
        }

        private val ATRAC3PLUS_GUID = byteArrayOf(
            0xBF.toByte(), 0xAA.toByte(), 0x23, 0xE9.toByte(), 0x58, 0xCB.toByte(), 0x71, 0x44,
            0xA1.toByte(), 0x19, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0xE4.toByte(), 0xCE.toByte(), 0x62,
        )

        fun open(riff: ByteArray): AtracDecoder? {
            val bb = ByteBuffer.wrap(riff).order(ByteOrder.LITTLE_ENDIAN)
            if (riff.size < 12 || bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) return null // RIFF/WAVE
            var o = 12
            var fmtAt = -1
            var fmtLen = 0
            var dataAt = -1
            var dataLen = 0
            while (o + 8 <= riff.size) {
                val id = bb.getInt(o)
                val len = bb.getInt(o + 4)
                when (id) {
                    0x20746D66 -> { fmtAt = o + 8; fmtLen = len } // "fmt "
                    0x61746164 -> { dataAt = o + 8; dataLen = len } // "data"
                }
                o += 8 + len + (len and 1)
                if (len < 0) break
            }
            if (fmtAt < 0 || dataAt < 0) return null
            val tag = bb.getShort(fmtAt).toInt() and 0xFFFF
            val channels = bb.getShort(fmtAt + 2).toInt()
            val rate = bb.getInt(fmtAt + 4)
            val align = bb.getShort(fmtAt + 12).toInt() and 0xFFFF
            val plus = when (tag) {
                0x0270 -> false
                0xFFFE -> fmtLen >= 40 && riff.copyOfRange(fmtAt + 24, fmtAt + 40).contentEquals(ATRAC3PLUS_GUID)
                else -> return null
            }
            if (tag == 0xFFFE && !plus) return null
            val extra = if (!plus && fmtLen >= 18 + 14) riff.copyOfRange(fmtAt + 18, fmtAt + 32) else null
            val handle = nativeOpen(plus, channels, align, extra)
            if (handle == 0L) return null
            val end = minOf(riff.size, dataAt + dataLen)
            return AtracDecoder(handle, channels, rate, align, riff, dataAt, end)
        }

        @JvmStatic private external fun nativeOpen(plus: Boolean, channels: Int, blockAlign: Int, extra: ByteArray?): Long
        @JvmStatic private external fun nativeDecode(handle: Long, frame: ByteArray, offset: Int, length: Int, out: ShortArray): Int
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
