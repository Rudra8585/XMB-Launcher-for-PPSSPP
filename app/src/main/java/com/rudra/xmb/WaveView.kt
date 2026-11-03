package com.rudra.xmb

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The XMB wave background, rendered live in a fragment shader.
 * It also renders the power-on scene behind the "Sony Computer Entertainment" text: two metal hairlines on
 * black that catch moving light, take on the theme colour, then bend down into the waves as the black lifts.
 */
class WaveView(context: Context) : GLSurfaceView(context) {
    private val renderer = WaveRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setPalette(p: Themes.Palette) = queueEvent { renderer.target = floatsOf(p) }

    /** Waves are hidden (but the gradient stays) when the user turns animation off. */
    fun setAnimated(on: Boolean) = queueEvent { renderer.animated = on }

    /** Starts the power-on scene now; it hands over to the normal waves by itself. */
    fun startBoot() {
        renderer.frozenBoot = -1f
        renderer.bootStart = System.nanoTime()
    }

    /** Debug builds: pins the power-on scene [seconds] in, for frame-by-frame comparison. */
    fun freezeBoot(seconds: Float) {
        renderer.frozenBoot = seconds
    }

    /** Skips straight to the resting waves. */
    fun finishBoot() {
        renderer.frozenBoot = -1f
        renderer.bootStart = 0L
    }

    private fun floatsOf(p: Themes.Palette) = floatArrayOf(
        *rgb(p.c0), *rgb(p.c1), *rgb(p.c2),
    )

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

    private class WaveRenderer : Renderer {
        @Volatile var target = FloatArray(9) { 0.5f }
        @Volatile var animated = true
        @Volatile var bootStart = 0L // 0 = not booting
        @Volatile var frozenBoot = -1f
        private val colors = FloatArray(9) { -1f }
        private var program = 0
        private var width = 1
        private var height = 1
        private val start = System.nanoTime()
        private var frozenTime = 0f
        private lateinit var quad: FloatBuffer

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VERTEX, FRAGMENT)
            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).also { it.position(0) }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w; height = h
            GLES20.glViewport(0, 0, w, h)
        }

        private var lastDraw = 0L

        override fun onDrawFrame(gl: GL10?) {
            // The resting waves drift slowly: 30 fps looks the same as 60 for half the GPU work and
            // battery. The power-on scene runs at full rate; with animation off, a few frames a second
            // are enough to ease between themes.
            val interval = when {
                bootStart != 0L || frozenBoot >= 0f -> 0L
                animated -> 33_000_000L
                else -> 100_000_000L
            }
            val wait = lastDraw + interval - System.nanoTime() - 2_000_000L // swap waits for vsync anyway
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            lastDraw = System.nanoTime()
            // Ease towards a new theme instead of snapping, like the PSP does.
            val t = target
            for (i in 0 until 9) colors[i] = if (colors[i] < 0f) t[i] else colors[i] + (t[i] - colors[i]) * 0.05f
            val time = if (animated) (System.nanoTime() - start) / 1e9f else frozenTime
            frozenTime = time

            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRes"), width.toFloat(), height.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"), time)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uC0"), colors[0], colors[1], colors[2])
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uC1"), colors[3], colors[4], colors[5])
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uC2"), colors[6], colors[7], colors[8])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBright"), daylight())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBoot"), bootTime())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** Seconds into the power-on scene, or a large value once it is over. */
        private fun bootTime(): Float {
            val frozen = frozenBoot
            if (frozen >= 0f) return frozen
            val startAt = bootStart
            if (startAt == 0L) return DONE
            val t = (System.nanoTime() - startAt) / 1e9f
            if (t > BOOT_SECONDS) bootStart = 0L
            return t
        }

        /** The PSP background dims at night and is brightest at midday. */
        private fun daylight(): Float {
            val c = Calendar.getInstance()
            val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
            val d = Math.cos((h - 13.0) / 24.0 * 2 * Math.PI).toFloat() // 1 at 1pm, -1 at 1am
            return 0.80f + 0.20f * (d * 0.5f + 0.5f)
        }

        private fun link(vs: String, fs: String): Int {
            fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }
    }

    companion object {
        /** After this many seconds the power-on scene has fully become the resting waves. */
        private const val BOOT_SECONDS = 6f
        private const val DONE = 1000f

        private const val VERTEX = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); // y = 0 at the top
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // Resting state: a diagonal gradient (dark top-left to bright bottom-right) with translucent ribbons
        // in the lower part, each with a bright rim, a soft glow and a fading sheet below it.
        //
        // uBoot (seconds since power-on, large when done) drives the opening, timed against a real PSP:
        //   0.18  a single hairline flashes across the middle and splits into two that part (to 0.42);
        //         they are metal: two different weights, each catching a light that slides along it
        //   2.0   the lines take on the theme colour while a glow in that colour rises from the bottom
        //   2.45  the lines bend down into the ribbons as the black lifts (to 3.35)
        //   2.6   they are large and fast at first, slowing and shrinking to rest by about 5.6
        private const val FRAGMENT = """
            precision highp float;
            varying vec2 vUv;
            uniform vec2 uRes;
            uniform float uTime;
            uniform vec3 uC0;
            uniform vec3 uC1;
            uniform vec3 uC2;
            uniform float uBright;
            uniform float uBoot;

            float settle() {
                float x = clamp((uBoot - 2.6) / 3.0, 0.0, 1.0);
                return 1.0 - (1.0 - x) * (1.0 - x);
            }

            // s = 0: the big, fast swell the lines bend into; s = 1: the resting waves.
            float curve(float x, float t, float base, float amp, float freq, float phase, float s) {
                float u = 1.0 - s;
                amp *= 1.0 + 2.4 * u;
                t -= 7.0 * u * u; // runs ahead while unsettled, so the waves race, then slow down
                phase += 1.8 * u;
                return base
                    + amp * sin(x * freq + t * 0.28 + phase)
                    + amp * 0.55 * sin(x * freq * 1.9 - t * 0.19 + phase * 1.7)
                    + amp * 0.35 * sin(x * freq * 0.5 + t * 0.11 + phase * 0.3);
            }

            // Brightness along a metal hairline: dim at the screen edges, with a light gliding along it.
            float metal(float u, float t, float dir, float speed, float offset, float grain) {
                float edges = smoothstep(0.0, 0.14, u) * smoothstep(1.0, 0.86, u);
                float pos = fract(offset + dir * t * speed);
                float a = (u - pos) / (0.06 + 0.02 * grain);
                float b = (u - pos + dir) / (0.06 + 0.02 * grain); // the same light wrapping around the end
                float spot = exp(-a * a) + exp(-b * b);
                float sheen = 0.5 + 0.18 * sin(u * (7.0 + 4.0 * grain) + dir * t * (1.7 + 1.3 * grain) + grain * 2.0);
                return edges * (sheen + 1.25 * spot);
            }

            void main() {
                vec2 uv = vUv;
                float aspect = uRes.x / uRes.y;
                float px = 1.0 / uRes.y;
                float bt = uBoot;
                float s = settle();

                float g = clamp(uv.x * 0.42 + uv.y * 0.78 - 0.05, 0.0, 1.0);
                vec3 col = g < 0.5 ? mix(uC0, uC1, g * 2.0) : mix(uC1, uC2, (g - 0.5) * 2.0);
                // Soft light falloff from the upper right, as on the PSP.
                col += vec3(0.05) * smoothstep(0.9, 0.0, distance(uv, vec2(0.85, 0.15)));

                // Black veil with the theme-coloured glow rising from the bottom, lifting as the waves arrive.
                vec3 glowColor = mix(uC0, uC1, 0.5);
                float veil = 1.0 - smoothstep(2.75, 3.35, bt);
                float rise = smoothstep(1.9, 2.8, bt);
                vec3 dark = glowColor * rise * 0.85 * smoothstep(0.35, 1.0, uv.y);
                col = mix(col, dark, veil);

                float morph = smoothstep(2.45, 3.35, bt);
                float split = smoothstep(0.23, 0.42, bt);
                float appear = smoothstep(0.18, 0.24, bt);
                vec3 lineColor = mix(vec3(0.91, 0.93, 0.96), min(mix(uC1, uC2, 0.5) * 1.25, vec3(1.0)), smoothstep(2.0, 2.6, bt));

                float x = uv.x * aspect;
                for (int i = 0; i < 4; i++) {
                    float fi = float(i);
                    float rest = curve(x, uTime + fi * 7.0, 0.60 + fi * 0.035, 0.035 + fi * 0.008, 1.05 + fi * 0.21, fi * 2.1, s);
                    // Ribbons 0 and 2 come from the upper line, 1 and 3 from the lower one.
                    float upper = mod(fi, 2.0) < 0.5 ? 1.0 : 0.0;
                    float lineY = 0.4915 + (upper > 0.5 ? -0.0155 : 0.0155) * split;
                    float y = mix(lineY, rest, morph);
                    float d = uv.y - y;

                    // The hairline look, fading out as it turns into a ribbon.
                    float lineVis = (fi < 1.5 ? appear : 0.0) * (1.0 - morph);
                    if (lineVis > 0.0) {
                        float width = upper > 0.5 ? 0.9 : 1.5;
                        float strength = upper > 0.5 ? 0.75 : 1.0;
                        float m = upper > 0.5 ? metal(uv.x, bt, 1.0, 0.38, 0.05, 0.0) : metal(uv.x, bt, 1.0, 0.29, 0.62, 1.0);
                        float core = exp(-abs(d) / (px * width));
                        float halo = exp(-abs(d) / (px * 7.0)) * 0.25;
                        col += lineColor * (core + halo) * m * strength * lineVis;
                    }

                    // The resting ribbon, growing out of the line.
                    float ribbonVis = fi < 1.5 ? morph : morph * morph;
                    if (ribbonVis > 0.0) {
                        float shimmer = 0.55 + 0.45 * sin(x * 1.3 - uTime * 0.21 + fi * 1.9);
                        float sheet = step(0.0, d) * exp(-d * (9.0 - fi)) * 0.07;
                        float rim = exp(-abs(d) / (px * (1.3 + fi * 0.4))) * 0.35 * shimmer;
                        float glow = exp(-abs(d) / (px * 18.0)) * 0.07;
                        col += vec3(sheet * (1.0 - veil) + rim + glow) * ribbonVis;
                    }
                }
                gl_FragColor = vec4(min(col * mix(uBright, 1.0, veil), vec3(1.0)), 1.0);
            }
        """
    }
}
