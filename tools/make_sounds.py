"""Synthesizes the launcher's built-in sounds into app/src/main/res/raw.

Everything is generated here from oscillators, noise, filters and a synthetic reverb, so the sounds
are original to this project; no recordings are sampled or traced. Needs numpy and scipy.
Run from the repository root:  python tools/make_sounds.py
"""
import os
import wave

import numpy as np
from scipy import signal

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')
rng = np.random.default_rng(11)


def t_axis(seconds):
    return np.arange(int(seconds * RATE)) / RATE


def env(seconds, attack, decay, hold=0.0):
    """Linear attack, optional hold, then exponential decay."""
    t = t_axis(seconds)
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = np.exp(-np.clip(t - attack - hold, 0, None) / decay)
    return a * d


def bandpass(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, hi], btype='band', fs=RATE, output='sos')
    return signal.sosfilt(sos, x)


def lowpass(x, hz, order=2):
    sos = signal.butter(order, hz, btype='low', fs=RATE, output='sos')
    return signal.sosfilt(sos, x)


def highpass(x, hz, order=2):
    sos = signal.butter(order, hz, btype='high', fs=RATE, output='sos')
    return signal.sosfilt(sos, x)


def fm_bell(freq, seconds, decay, ratio=3.5, index=1.8, attack=0.002):
    """A soft glassy bell: FM with the modulation dying away faster than the tone."""
    t = t_axis(seconds)
    mod_env = np.exp(-t / (decay * 0.35))
    mod = index * mod_env * np.sin(2 * np.pi * freq * ratio * t)
    return env(seconds, attack, decay) * np.sin(2 * np.pi * freq * t + mod)


def reverb(x, seconds=1.2, wet=0.25, predelay=0.012):
    """Convolution with decaying, darkening noise: a small, smooth room."""
    n = int(seconds * RATE)
    tail = rng.standard_normal(n) * np.exp(-np.arange(n) / (n / 6.0))
    tail = lowpass(tail, 6000)
    tail[: int(predelay * RATE)] = 0
    tail /= np.sqrt(np.sum(tail ** 2))
    y = signal.fftconvolve(x, tail)
    dry = np.zeros(len(y))
    dry[: len(x)] = x
    return dry * (1 - wet) + y * wet * 3.0


def place(tracks, seconds):
    out = np.zeros(int(seconds * RATE))
    for x, offset in tracks:
        start = int(offset * RATE)
        end = min(len(out), start + len(x))
        out[start:end] += x[: end - start]
    return out


def fade_tail(x, seconds=0.02):
    n = min(len(x), int(seconds * RATE))
    x[-n:] *= np.linspace(1, 0, n)
    return x


def write(name, x, peak):
    x = fade_tail(x.copy())
    x = x / max(1e-9, np.max(np.abs(x))) * peak
    data = (np.clip(x, -1, 1) * 32767).astype('<i2')
    with wave.open(os.path.join(OUT, 'xmb_%s.wav' % name), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


def bead(pitch, bright):
    """Cursor, confirm and back: a small metal bead dropped on a hard surface - a sharp, high "tik"
    that is over almost at once, then a much fainter bounce just after. No ringing tail."""
    def hit(gain):
        s = 0.05
        t = t_axis(s)
        modes = ((1.0, 1.0, 0.009), (1.47, 0.6 * bright, 0.006), (2.09, 0.35 * bright, 0.004))
        ring = sum(a * np.sin(2 * np.pi * pitch * r * t + rng.uniform(0, 6.28)) * env(s, 0.0001, d) for r, a, d in modes)
        click = bandpass(rng.standard_normal(len(t)), pitch * 0.8, min(pitch * 2.6, 20000)) * env(s, 0.00005, 0.0012)
        return (ring * 0.7 + click) * gain
    x = place([(hit(1.0), 0.0), (hit(0.18), 0.028)], 0.09)
    return reverb(highpass(x, 1500), 0.08, 0.05, predelay=0.003)


os.makedirs(OUT, exist_ok=True)

# Cursor up / confirm, and cursor down / back: the same bead drop, the second a little lower and softer.
write('up', bead(6200, 1.0), 0.45)
write('down', bead(5200, 0.8), 0.42)
# Option menu opens / closes: one airy pluck with a breath of noise, up or down.
def airy(f, sweep):
    s = 0.35
    t = t_axis(s)
    tone = np.sin(2 * np.pi * (f * t + sweep * t * t / 2)) * env(s, 0.004, 0.07)
    air = highpass(rng.standard_normal(len(t)), 3000) * env(s, 0.01, 0.04) * 0.15
    return reverb(tone + air, 0.5, 0.25)
write('notice', airy(1175.0, 900.0), 0.5)
write('notice_back', airy(1397.0, -900.0), 0.5)

# Power-on: a slow, warm swell - detuned saw pad opening up through a filter, a soft low root,
# and a few high chimes settling on top - then a long, gentle tail. Not any existing melody.
def pad(freqs, seconds, attack, release):
    t = t_axis(seconds)
    x = np.zeros_like(t)
    for f in freqs:
        for detune in (-0.004, 0.0, 0.0045):
            x += signal.sawtooth(2 * np.pi * f * (1 + detune) * t + rng.uniform(0, 6.28))
    # Filter opens over the swell.
    cutoff = 400 + 3200 * np.clip(t / (attack * 1.4), 0, 1) ** 1.5
    y = np.zeros_like(x)
    block = 512
    zi = None
    for i in range(0, len(x), block):
        sos = signal.butter(2, cutoff[i], btype='low', fs=RATE, output='sos')
        if zi is None:
            zi = signal.sosfilt_zi(sos) * 0
        y[i:i + block], zi = signal.sosfilt(sos, x[i:i + block], zi=zi)
    shape = np.clip(t / attack, 0, 1) ** 2 * np.exp(-np.clip(t - attack, 0, None) / release)
    return y * shape

boot_len = 4.5
chord = [146.83, 220.0, 277.18, 329.63, 440.0]  # D major add9 voicing
swell = pad(chord, boot_len, 1.6, 1.4)
root = np.sin(2 * np.pi * 73.42 * t_axis(boot_len)) * env(boot_len, 1.2, 1.8) * 0.6
chimes = place([(fm_bell(f, 2.5, 0.9, ratio=2.0, index=0.8, attack=0.01) * 0.35, o)
                for f, o in ((1318.5, 1.1), (1760.0, 1.35), (2217.5, 1.6), (1975.5, 1.95))], boot_len)
write('boot', reverb(swell * 0.5 + root + chimes, 2.2, 0.35), 0.8)

# Game boot: a rising breath of air into a bright, open chord that rings out.
launch_len = 3.0
t = t_axis(launch_len)
rise = np.clip(t / 0.5, 0, 1)
air = bandpass(rng.standard_normal(len(t)), 1500, 9000) * rise ** 2 * np.exp(-np.clip(t - 0.5, 0, None) / 0.15) * 0.4
ring = place([(fm_bell(f, 2.5, 0.8, ratio=2.0, index=0.9, attack=0.008) * a, 0.48)
              for f, a in ((587.33, 0.7), (880.0, 0.6), (1174.66, 0.5), (1479.98, 0.4), (2349.32, 0.25))], launch_len)
body = pad([293.66, 440.0, 587.33], launch_len, 0.5, 0.9) * 0.25
write('launch', reverb(air + ring + body, 1.8, 0.3), 0.8)

print('written to', os.path.normpath(OUT))
