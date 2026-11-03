package com.rudra.xmb.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Plays a PSMF (ICON1.PMF) animated icon on a Surface, looping.
 * Android cannot open PSMF itself, so the MPEG-PS is demuxed here and the raw H.264 is fed to MediaCodec.
 */
class PmfPlayer {
    private class Session {
        @Volatile var stopRequested = false
    }

    private var session: Session? = null
    // Only one decoder may own the Surface at a time.
    private val surfaceLock = Object()

    fun play(pmf: ByteArray, surface: Surface, onFirstFrame: () -> Unit) {
        stop()
        val s = Session()
        session = s
        Thread({ synchronized(surfaceLock) { if (!s.stopRequested) run(pmf, surface, s, onFirstFrame) } }, "icon1").start()
    }

    /** Stops the current playback; never blocks the caller. */
    fun stop() {
        session?.stopRequested = true
        session = null
    }

    private fun run(pmf: ByteArray, surface: Surface, s: Session, onFirstFrame: () -> Unit) {
        val units = try {
            accessUnits(demuxVideo(pmf))
        } catch (e: Exception) {
            Log.w("Pmf", "Demux failed", e); return
        }
        val sps = units.sps ?: return
        val pps = units.pps ?: return
        if (units.frames.isEmpty()) return

        val (w, h) = H264.spsSize(sps) ?: (144 to 80)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
        }
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.w("Pmf", "No decoder", e); return
        }

        val frameUs = 1_001_000L / 30 // PSMF icons run at 29.97 fps
        val info = MediaCodec.BufferInfo()
        var input = 0
        var loopBaseUs = 0L
        var startNs = System.nanoTime()
        var first = true
        try {
            while (!s.stopRequested) {
                val inIdx = codec.dequeueInputBuffer(2000)
                if (inIdx >= 0) {
                    if (input >= units.frames.size) {
                        input = 0
                        loopBaseUs += units.frames.size * frameUs
                    }
                    val buf = codec.getInputBuffer(inIdx)!!
                    val frame = units.frames[input]
                    buf.clear()
                    buf.put(frame)
                    codec.queueInputBuffer(inIdx, 0, frame.size, loopBaseUs + input * frameUs, 0)
                    input++
                }
                val outIdx = codec.dequeueOutputBuffer(info, 2000)
                if (outIdx >= 0) {
                    val dueNs = startNs + info.presentationTimeUs * 1000
                    val waitMs = (dueNs - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) Thread.sleep(minOf(waitMs, 100))
                    codec.releaseOutputBuffer(outIdx, true)
                    if (first) {
                        first = false
                        startNs = System.nanoTime() - info.presentationTimeUs * 1000
                        onFirstFrame()
                    }
                }
            }
        } catch (e: Exception) {
            if (!s.stopRequested) Log.w("Pmf", "Playback failed", e)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    class Units(val sps: ByteArray?, val pps: ByteArray?, val frames: List<ByteArray>)

    companion object {
        private val START = byteArrayOf(0, 0, 0, 1)

        /** Pulls the H.264 elementary stream (PES id 0xE0) out of a PSMF file. */
        fun demuxVideo(pmf: ByteArray): ByteArray {
            fun be32(o: Int) = ((pmf[o].toInt() and 0xFF) shl 24) or ((pmf[o + 1].toInt() and 0xFF) shl 16) or
                ((pmf[o + 2].toInt() and 0xFF) shl 8) or (pmf[o + 3].toInt() and 0xFF)
            var o = 0
            var end = pmf.size
            if (pmf.size > 16 && String(pmf, 0, 4, Charsets.US_ASCII) == "PSMF") {
                o = be32(8)
                end = minOf(pmf.size, o + be32(12))
            }
            val es = java.io.ByteArrayOutputStream(pmf.size)
            while (o + 4 <= end) {
                if (pmf[o] != 0.toByte() || pmf[o + 1] != 0.toByte() || pmf[o + 2] != 1.toByte()) { o++; continue }
                val id = pmf[o + 3].toInt() and 0xFF
                when {
                    id == 0xBA -> { // MPEG-2 pack header
                        val stuffing = if (o + 13 < end) pmf[o + 13].toInt() and 7 else 0
                        o += 14 + stuffing
                    }
                    id == 0xB9 -> o += 4
                    id >= 0xBB && o + 6 <= end -> {
                        val len = ((pmf[o + 4].toInt() and 0xFF) shl 8) or (pmf[o + 5].toInt() and 0xFF)
                        if (id == 0xE0 && o + 9 <= end) {
                            val hdr = pmf[o + 8].toInt() and 0xFF
                            val payload = o + 9 + hdr
                            val stop = minOf(end, o + 6 + len)
                            if (stop > payload) es.write(pmf, payload, stop - payload)
                        }
                        o += 6 + len
                    }
                    else -> o += 4
                }
            }
            return es.toByteArray()
        }

        /** Splits an Annex-B stream into decoder access units. */
        fun accessUnits(es: ByteArray): Units {
            val nals = ArrayList<IntArray>() // [start of payload, end]
            var i = 0
            var start = -1
            while (i + 3 <= es.size) {
                if (es[i] == 0.toByte() && es[i + 1] == 0.toByte() && es[i + 2] == 1.toByte()) {
                    if (start >= 0) {
                        var e = i
                        while (e > start && es[e - 1] == 0.toByte()) e--
                        nals += intArrayOf(start, e)
                    }
                    start = i + 3
                    i += 3
                } else i++
            }
            if (start >= 0 && start < es.size) nals += intArrayOf(start, es.size)

            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val frames = ArrayList<ByteArray>()
            val cur = java.io.ByteArrayOutputStream()
            var hasSlice = false
            // Non-slice NALs (SPS/PPS/SEI) only end an access unit that already has a slice;
            // otherwise they stay as the prefix of the next one.
            fun flush() {
                if (!hasSlice) return
                frames += cur.toByteArray()
                cur.reset(); hasSlice = false
            }
            for ((s, e) in nals) {
                if (e <= s) continue
                val type = es[s].toInt() and 0x1F
                val firstMbZero = e > s + 1 && (es[s + 1].toInt() and 0x80) != 0
                when (type) {
                    7 -> { if (sps == null) sps = es.copyOfRange(s, e); flush() }
                    8 -> { if (pps == null) pps = es.copyOfRange(s, e); flush() }
                    9, 6 -> flush()
                    1, 5 -> if (hasSlice && firstMbZero) flush()
                }
                if (type == 9) continue
                cur.write(START); cur.write(es, s, e - s)
                if (type == 1 || type == 5) hasSlice = true
            }
            flush()
            return Units(sps, pps, frames)
        }
    }
}

/** Tiny SPS parser for the picture size. */
object H264 {
    private class Bits(val b: ByteArray) {
        var pos = 0
        fun bit(): Int { val v = (b[pos shr 3].toInt() shr (7 - (pos and 7))) and 1; pos++; return v }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int { var z = 0; while (bit() == 0 && z < 32) z++; return (1 shl z) - 1 + bits(z) }
        fun se(): Int { val v = ue(); return if (v and 1 == 1) (v + 1) / 2 else -(v / 2) }
    }

    fun spsSize(sps: ByteArray): Pair<Int, Int>? = try {
        // Strip emulation-prevention bytes.
        val raw = java.io.ByteArrayOutputStream()
        var zeros = 0
        for (i in 1 until sps.size) {
            val v = sps[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            zeros = if (v == 0) zeros + 1 else 0
            raw.write(v)
        }
        val r = Bits(raw.toByteArray())
        val profile = r.bits(8); r.bits(16); r.ue()
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            val chroma = r.ue(); if (chroma == 3) r.bit()
            r.ue(); r.ue(); r.bit()
            if (r.bit() == 1) repeat(if (chroma != 3) 8 else 12) { idx ->
                if (r.bit() == 1) {
                    var last = 8; var next = 8
                    repeat(if (idx < 6) 16 else 64) { if (next != 0) { next = (last + r.se() + 256) % 256 }; if (next != 0) last = next }
                }
            }
        }
        r.ue()
        val poc = r.ue()
        if (poc == 0) r.ue() else if (poc == 1) { r.bit(); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        r.ue(); r.bit()
        val wMbs = r.ue() + 1
        val hMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.bit()
        r.bit()
        var w = wMbs * 16
        var h = (2 - frameMbsOnly) * hMapUnits * 16
        if (r.bit() == 1) {
            val l = r.ue(); val rr = r.ue(); val t = r.ue(); val bt = r.ue()
            w -= (l + rr) * 2; h -= (t + bt) * 2 * (2 - frameMbsOnly)
        }
        w to h
    } catch (e: Exception) { null }
}
