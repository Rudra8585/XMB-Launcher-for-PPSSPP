"""Synthesizes the launcher's built-in default sounds into app/src/main/res/raw.

Everything is generated from sine waves and noise here, so the sounds are original to this
project. Run from the repository root:  python tools/make_sounds.py
"""
import math
import os
import random
import struct
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')


def write(name, samples):
    peak = max(1e-9, max(abs(s) for s in samples))
    gain = 0.85 / peak
    with wave.open(os.path.join(OUT, 'xmb_%s.wav' % name), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s * gain)) * 32767)) for s in samples))


def tone(freq, length, attack, decay, partials=((1, 1.0),), vibrato=0.0):
    """A bell-like tone: sum of partials with a short attack and exponential decay."""
    n = int(length * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / attack) * math.exp(-t / decay)
        f = freq * (1 + vibrato * math.sin(2 * math.pi * 5 * t))
        out.append(env * sum(a * math.sin(2 * math.pi * f * k * t) for k, a in partials))
    return out


def mix(*tracks):
    n = max(len(t) for t, _ in tracks)
    out = [0.0] * n
    for t, offset in tracks:
        start = int(offset * RATE)
        for i, s in enumerate(t):
            if start + i < n:
                out[start + i] += s
    return out


def pad(samples, seconds):
    return samples + [0.0] * int(seconds * RATE)


def echo(samples, delay, feedback, taps):
    """A few soft repeats, for a sense of space."""
    d = int(delay * RATE)
    out = samples + [0.0] * (d * taps)
    for k in range(1, taps + 1):
        g = feedback ** k
        for i, s in enumerate(samples):
            out[i + d * k] += s * g
    return out


random.seed(7)
os.makedirs(OUT, exist_ok=True)

glass = ((1, 1.0), (2, 0.25), (3, 0.08))

# Cursor up / confirm: a short bright glass tick.
write('up', pad(tone(2093, 0.09, 0.002, 0.025, glass), 0.02))
# Cursor down / back: the same tick, a fourth lower.
write('down', pad(tone(1568, 0.09, 0.002, 0.025, glass), 0.02))
# Option menu opens: two rising notes.
write('notice', mix((tone(1319, 0.25, 0.004, 0.06, glass), 0), (tone(1760, 0.3, 0.004, 0.08, glass), 0.06)))
# Option menu closes: the same, falling.
write('notice_back', mix((tone(1760, 0.25, 0.004, 0.06, glass), 0), (tone(1319, 0.3, 0.004, 0.08, glass), 0.06)))

# Power-on: a soft, slowly blooming major-ninth chord with a high shimmer on top.
warm = ((1, 1.0), (2, 0.35), (3, 0.12), (4, 0.05))
chord = [(261.63, 0.0), (329.63, 0.08), (392.0, 0.16), (493.88, 0.24), (587.33, 0.32)]
boot = mix(*[(tone(f, 3.6, 0.6, 1.6, warm, vibrato=0.0015), o) for f, o in chord],
           (tone(1046.5, 2.6, 0.9, 0.9, glass), 0.5), (tone(1568.0, 2.2, 1.0, 0.8, glass), 0.7))
write('boot', echo(boot, 0.23, 0.35, 3))

# Game boot: a quick rising arpeggio that lands on a bright, ringing chord.
arp = [523.25, 659.25, 783.99, 1046.5, 1318.5, 1568.0]
launch = mix(*[(tone(f, 0.5, 0.005, 0.18, glass), i * 0.07) for i, f in enumerate(arp)],
             *[(tone(f, 2.4, 0.05, 0.9, warm), 0.42) for f in (523.25, 659.25, 783.99, 1046.5)])
write('launch', echo(launch, 0.19, 0.3, 3))

print('written to', os.path.normpath(OUT))
