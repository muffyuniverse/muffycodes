#!/usr/bin/env python3
"""Tile PNG screenshots into one labelled contact sheet, using only the standard library.

Why this exists: the model provider refuses a request carrying more than 30 images, and every
image already looked at stays in the request for the rest of the session. Splitting a sweep over
several turns therefore does not help - the limit counts the whole payload. Needing fewer images
does, so several screenshots are packed into one.

There is no ImageMagick and no Pillow in this container and no JVM, so PNG is decoded and
encoded here. Only what these screenshots actually are is supported: 8-bit, non-interlaced, RGB
or RGBA. Anything else is refused loudly rather than silently drawn wrong - a contact sheet is
evidence, and a mis-decoded one would be false evidence.

usage: python3 sheet.py out.png COLS "label=path" ["label=path" ...]
"""
import struct
import sys
import zlib


def read_png(path):
    """Return (width, height, rows) where each row is a list of (r, g, b, a) tuples."""
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
    except OSError as e:
        raise SystemExit(f"cannot read {path}: {e}")
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"not a PNG: {path}")
    pos = 8
    idat = b""
    w = h = depth = ctype = 0
    while pos < len(raw):
        (length,) = struct.unpack(">I", raw[pos:pos + 4])
        kind = raw[pos + 4:pos + 8]
        body = raw[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            interlace = body[12]
            if depth != 8 or interlace != 0 or ctype not in (2, 6):
                raise SystemExit(
                    f"{path}: only 8-bit non-interlaced RGB/RGBA is supported "
                    f"(depth={depth} colour={ctype} interlace={interlace})")
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break

    if w == 0 or h == 0 or ctype == 0:
        raise SystemExit(f"{path}: no IHDR - not a PNG we can read")

    data = zlib.decompress(idat)
    channels = 4 if ctype == 6 else 3
    stride = w * channels
    rows = []
    prev = bytearray(stride)
    off = 0
    for _ in range(h):
        f = data[off]
        off += 1
        line = bytearray(data[off:off + stride])
        off += stride
        # Undo the per-scanline filter. Each predictor refers to the byte one pixel back (a) and
        # the byte one row back (b), or one pixel back on the previous row (c).
        if f == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        elif f != 0:
            raise SystemExit(f"{path}: unknown filter {f}")
        prev = line
        if channels == 4:
            rows.append([tuple(line[i:i + 4]) for i in range(0, stride, 4)])
        else:
            rows.append([tuple(line[i:i + 3]) + (255,) for i in range(0, stride, 3)])
    return w, h, rows


def write_png(path, w, h, rows):
    """Encode rows of (r, g, b) tuples as an 8-bit RGB PNG, filtered with Sub for compressibility."""
    raw = bytearray()
    for row in rows:
        raw.append(1)  # Sub filter
        src = bytearray()
        for px in row:
            src += bytes(px[:3])
        # Sub predicts each byte from the byte one pixel to its left. The subtraction has to read
        # the ORIGINAL left byte, so the filtered bytes go to a separate buffer - writing back into
        # src would have each byte predicted from an already-filtered neighbour, which decodes to
        # vertical stripes of nonsense.
        line = bytearray(src)
        for i in range(3, len(src)):
            line[i] = (src[i] - src[i - 3]) & 0xFF
        raw += line

    def chunk(kind, body):
        return (struct.pack(">I", len(body)) + kind + body
                + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    try:
        with open(path, "wb") as fh:
            fh.write(out)
    except OSError as e:
        raise SystemExit(f"cannot write {path}: {e}")


# A 5x7 bitmap font, enough for scenario numbers and short words. Labels are the only thing here
# that is not evidence from the app, so they are drawn plainly rather than beautifully.
GLYPHS = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["01110", "00100", "00100", "00100", "00100", "00100", "01110"],
    "J": ["00111", "00010", "00010", "00010", "00010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    " ": ["00000"] * 7,
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    ".": ["00000", "00000", "00000", "00000", "00000", "01100", "01100"],
    ":": ["00000", "01100", "01100", "00000", "01100", "01100", "00000"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
}


def draw_text(rows, x, y, text, scale=2, colour=(0, 0, 0)):
    for ch in text.upper():
        glyph = GLYPHS.get(ch, GLYPHS[" "])
        for gy, line in enumerate(glyph):
            for gx, bit in enumerate(line):
                if bit != "1":
                    continue
                for dy in range(scale):
                    for dx in range(scale):
                        px, py = x + gx * scale + dx, y + gy * scale + dy
                        if 0 <= py < len(rows) and 0 <= px < len(rows[py]):
                            rows[py][px] = colour
        x += 6 * scale
    return x


def main(argv):
    if len(argv) < 4:
        raise SystemExit(__doc__)
    out = argv[1]
    try:
        cols = int(argv[2])
    except ValueError:
        raise SystemExit(f"columns must be a number, not {argv[2]!r}")
    if cols < 1:
        raise SystemExit("columns must be at least 1")
    items = []
    for spec in argv[3:]:
        label, _, path = spec.partition("=")
        w, h, rows = read_png(path)
        items.append((label, w, h, rows))

    cell_w = max(i[1] for i in items)
    cell_h = max(i[2] for i in items)
    pad, label_h = 10, 24
    nrows = (len(items) + cols - 1) // cols
    W = cols * cell_w + (cols + 1) * pad
    H = nrows * (cell_h + label_h) + (nrows + 1) * pad

    WHITE, EDGE = (255, 255, 255), (170, 170, 170)
    sheet = [[WHITE] * W for _ in range(H)]

    for i, (label, w, h, rows) in enumerate(items):
        col, row = i % cols, i // cols
        x0 = pad + col * (cell_w + pad)
        y0 = pad + row * (cell_h + label_h + pad)
        draw_text(sheet, x0, y0 + 4, label)
        for y in range(h):
            line = sheet[y0 + label_h + y]
            src = rows[y]
            for x in range(w):
                line[x0 + x] = src[x]
        for x in range(w):
            sheet[y0 + label_h - 1][x0 + x] = EDGE
        for y in range(h + 1):
            sheet[y0 + label_h - 1 + y][x0 - 1 if x0 else 0] = EDGE

    write_png(out, W, H, sheet)
    print(f"{out} {W}x{H} ({len(items)} cells)")


if __name__ == "__main__":
    main(sys.argv)
