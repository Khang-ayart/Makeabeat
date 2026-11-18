#!/usr/bin/env python3
"""Dựng bộ mẫu nhạc cụ có cao độ cho sampler: chọn nốt gốc, cắt đầu/đuôi, chuẩn hoá, mono 16-bit 44,1 kHz."""
import json, os, re, subprocess, sys, wave
import numpy as np

SRC = "/home/claude/sfzinstruments"
OUT = sys.argv[1]
SR = 44100
PC = {"c": 0, "d": 2, "e": 4, "f": 5, "g": 7, "a": 9, "b": 11}

def midi(name):
    m = re.fullmatch(r"([a-g])([#b]?)(-?\d+)", name.lower())
    pc = PC[m.group(1)] + (1 if m.group(2) == "#" else -1 if m.group(2) == "b" else 0)
    return (int(m.group(3)) + 1) * 12 + pc

def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").copy()

def process(x, max_sec, peak):
    thr = 0.02 * np.abs(x).max()
    on = int(np.argmax(np.abs(x) > thr))
    x = x[max(0, on - int(0.004 * SR)):]
    n = int(max_sec * SR)
    x = x[:n]
    f = min(len(x), int(0.25 * SR))
    x[-f:] *= np.linspace(1, 0, f) ** 2   # đuôi tắt mượt
    m = np.abs(x).max()
    return (x * (peak / m)).astype(np.float32) if m > 0 else x

def write(path, x):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())

def greedy(notes, gap=3):
    out = []
    for n in sorted(notes):
        if not out or n - out[-1] >= gap: out.append(n)
    return out

def zones_for(roots):
    rs = sorted(roots); res = []
    for i, r in enumerate(rs):
        lo = 0 if i == 0 else (rs[i - 1] + r) // 2 + 1
        hi = 127 if i == len(rs) - 1 else (r + rs[i + 1]) // 2
        res.append((r, lo, hi))
    return res

def build(inst_id, name, gain, layers, finder, long_sec, short_sec, manifest, notes_gap=3):
    """layers: [(layer_id, velLo, velHi, peak)]; finder(layer_id) -> {midi: path}"""
    entry = {"inst": inst_id, "name": name, "gain": gain, "zones": []}
    for lid, vlo, vhi, peak in layers:
        avail = finder(lid)
        roots = greedy(avail.keys(), notes_gap)
        for r, lo, hi in zones_for(roots):
            x = process(decode(avail[r]), long_sec if r < 60 else short_sec, peak)
            rel = f"{name}/{name}_{lid}_{r}.wav"
            write(os.path.join(OUT, rel), x)
            entry["zones"].append({"file": rel, "root": r, "lo": lo, "hi": hi, "velLo": vlo, "velHi": vhi})
    manifest.append(entry)

# Tên nốt trong tệp nguồn không trùng cao độ thật: Splendid Grand Piano ghi C3 = đô giữa (thật cao hơn 1 quãng tám),
# Swagbass ghi theo khuông bass (thật thấp hơn 1 quãng tám). Đo phổ từng tệp xác nhận; guitar khớp tên nên không bù.
PIANO_OFFSET = 12
BASS_OFFSET = -12

def piano_finder(layer):
    d = f"{SRC}/splendidgrandpiano/Samples"
    out = {}
    for f in os.listdir(d):
        m = re.fullmatch(rf"{layer} ([A-G]#?-?\d+)\.flac", f)
        if m: out[midi(m.group(1)) + PIANO_OFFSET] = os.path.join(d, f)
    return out

def glob_finder(folder, pattern, offset=0):
    def find(layer):
        out = {}
        for f in os.listdir(folder):
            m = re.fullmatch(pattern.format(layer=layer), f)
            if m: out[midi(m.group(1)) + offset] = os.path.join(folder, f)
        return out
    return find

manifest = []
build(0, "piano", 0.55, [("Mp", 1, 84, 0.55), ("FF", 85, 127, 0.85)], piano_finder, 6.0, 4.5, manifest)
build(3, "bass", 0.6, [("p", 1, 72, 0.6), ("f", 73, 127, 0.85)],
      glob_finder(f"{SRC}/karoryfer.swagbass/notes", r"([a-g][#b]?\d)_{layer}_rr1\.wav", BASS_OFFSET), 3.5, 3.0, manifest)
build(1, "acoustic", 0.55, [("vl2", 1, 80, 0.55), ("vl4", 81, 127, 0.85)],
      glob_finder(f"{SRC}/karoryfer.shinyguitar/Samples/acoustic", r"([a-g][#b]?\d)_{layer}_rr1_1\.wav"), 4.0, 3.5, manifest)
build(2, "electric", 0.55, [("mp", 1, 80, 0.55), ("f", 81, 127, 0.85)],
      glob_finder(f"{SRC}/karoryfer.emilyguitar/notes", r"([a-g][#b]?\d)_{layer}_rr1\.wav"), 3.5, 3.5, manifest)
json.dump({"version": 1, "instruments": manifest}, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
for e in manifest:
    print(e["name"], len(e["zones"]), "zones")
