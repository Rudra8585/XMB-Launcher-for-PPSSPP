package com.rudra.xmb.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/** Loops a game's SND0.AT3 with the PSP-style fade in / fade out. */
class Snd0Player {
    companion object {
        const val FADE_OUT_MS = 160L
    }

    private class Session {
        @Volatile var stopRequested = false
    }

    private var session: Session? = null

    fun play(riff: ByteArray) {
        stop()
        val s = Session()
        session = s
        Thread({ run(riff, s) }, "snd0").start()
    }

    /** Asks the current playback to fade out; never blocks the caller. */
    fun stop() {
        session?.stopRequested = true
        session = null
    }

    /** A preview is playing (or about to). */
    val playing: Boolean get() = session != null

    private fun run(riff: ByteArray, s: Session) {
        val dec = AtracDecoder.open(riff) ?: run {
            Log.w("Snd0", "Unsupported SND0 format")
            return
        }
        val chMask = if (dec.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(dec.sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(dec.sampleRate).setChannelMask(chMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            var volume = 0f
            track.setVolume(0f)
            track.play()
            while (!s.stopRequested) {
                val (pcm, n) = dec.next()
                if (n <= 0) break
                volume = minOf(1f, volume + 0.03f) // ~1s fade in
                track.setVolume(volume)
                track.write(pcm, 0, n * dec.channels)
            }
            // Quick fade out, timed rather than per decoded block so it is always over in FADE_OUT_MS:
            // short enough not to overlap what comes next, long enough not to click.
            val steps = 8
            for (i in steps - 1 downTo 0) {
                track.setVolume(volume * i / steps)
                Thread.sleep(FADE_OUT_MS / steps)
            }
            track.pause()
            track.flush()
        } catch (e: Exception) {
            Log.w("Snd0", "Playback failed", e)
        } finally {
            track.release()
            dec.close()
        }
    }
}
