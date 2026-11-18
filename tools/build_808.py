#!/usr/bin/env python3
"""Dựng 3 mẫu 808 (CC0, Stargate pack) cho hàng Bass của Looper: mono 16-bit 44,1 kHz, chuẩn hoá, fade đầu/đuôi.
Dùng: build_808.py <thư mục stargate-sample-pack> <thư mục đích assets/samples>"""
import subprocess, sys, wave
import numpy as np

SR = 44100
# id -> (tệp nguồn, độ dài tối đa (s), fade đuôi (s))
SRC = {
    "bass.808long": ("fugue-state-audio/drums/kicks/sdbkit-sub-a.wav", 1.5, 0.30),
    "bass.808short": ("microlag/One-Shots/Drums/Kick2.wav", 0.43, 0.05),
    "bass.808dist": ("microlag/One-Shots/Drums/Kick1.wav", 0.74, 0.10),
}

def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)

def main(src_dir, out_dir):
    for sid, (rel, max_sec, fade) in SRC.items():
        x = load(f"{src_dir}/{rel}")[: int(max_sec * SR)]
        x -= x.mean()
        nf = min(len(x) - 1, int(fade * SR))
        x[-nf:] *= np.linspace(1.0, 0.0, nf) ** 2
        na = int(0.003 * SR)
        x[:na] *= np.linspace(0.0, 1.0, na)
        x *= 0.9 / np.abs(x).max()
        with wave.open(f"{out_dir}/{sid}.wav", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes((x * 32767).astype("<i2").tobytes())
        print(sid, round(len(x) / SR, 2), "s")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
