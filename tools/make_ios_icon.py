#!/usr/bin/env python3
"""
Значок для iPhone — тот же, что у Android (app/src/main/res/drawable/ic_launcher.xml):
рамка видоискателя и объектив, белое на синем. PNG 1024×1024 без прозрачности (iOS сам
скругляет углы). Без сторонних библиотек: геометрия считается по пикселям.

    python3 tools/make_ios_icon.py
"""
import math, os, struct, zlib

N = 1024
K = N / 108.0  # в Android-значке поле 108×108
BLUE = (0x1F, 0x6F, 0xEB)
WHITE = (255, 255, 255)
STROKE = 5 * K / 2  # половина толщины линии

# Отрезки рамки (по углам), как в ic_launcher.xml.
SEGMENTS = [((30, 42), (30, 32)), ((30, 32), (40, 32)), ((68, 32), (78, 32)), ((78, 32), (78, 42)),
            ((78, 66), (78, 76)), ((78, 76), (68, 76)), ((40, 76), (30, 76)), ((30, 76), (30, 66))]
SEGMENTS = [((a[0] * K, a[1] * K), (b[0] * K, b[1] * K)) for a, b in SEGMENTS]
C = 54 * K


def dist_seg(px, py, a, b):
    ax, ay = a; bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def cover(px, py):
    """Насколько пиксель белый (0…1), со сглаживанием по краю."""
    d = min(dist_seg(px, py, a, b) for a, b in SEGMENTS) - STROKE           # рамка (скруглённые концы)
    r = math.hypot(px - C, py - C)
    d = min(d, abs(r - 12 * K) - STROKE)                                   # кольцо объектива
    d = min(d, r - 4 * K)                                                  # точка в центре
    return max(0.0, min(1.0, 0.5 - d))


rows = []
for y in range(N):
    row = bytearray([0])
    for x in range(N):
        a = cover(x + 0.5, y + 0.5)
        row += bytes(round(BLUE[i] * (1 - a) + WHITE[i] * a) for i in range(3))
    rows.append(bytes(row))

def chunk(t, data):
    return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xFFFFFFFF)

png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 2, 0, 0, 0)) + \
      chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
out = os.path.join(os.path.dirname(__file__), "..", "iosApp", "iosApp", "Assets.xcassets", "AppIcon.appiconset", "icon-1024.png")
open(out, "wb").write(png)
print(os.path.normpath(out), len(png), "байт")
